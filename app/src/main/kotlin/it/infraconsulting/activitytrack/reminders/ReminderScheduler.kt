/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.edit
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.reminder.Reminder
import it.infraconsulting.activitytrack.domain.reminder.ReminderConfig
import it.infraconsulting.activitytrack.domain.reminder.ReminderEntry
import it.infraconsulting.activitytrack.domain.reminder.ReminderPlanner
import it.infraconsulting.activitytrack.notify.EntryInfo
import it.infraconsulting.activitytrack.notify.NotificationIds
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Single-alarm reminder scheduler (DESIGN §6.1): the earliest reminder of
 * ReminderPlanner arms one inexact alarm; ReminderReceiver posts what is due
 * and re-arms. Re-armed on every change of entries, settings, lunch breaks
 * or working hours, and on boot / time changes (BootReceiver).
 */
class ReminderScheduler(
	private val context: Context,
	private val container: AppContainer,
) {
	private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
	private val lock = Mutex()

	fun start(scope: CoroutineScope) {
		scope.launch {
			combine(
				container.entries.openEntries,
				container.settings.settings,
				container.masterData.lunchBreaks,
				container.masterData.schedule,
			) { _, _, _, _ -> }.conflate().collect { reschedule() }
		}
	}

	private suspend fun state(): Triple<ReminderPlanner, List<ReminderEntry>, LocalDate?> {
		val s = container.settings.current()
		val zone = ZoneId.systemDefault()
		val planner = ReminderPlanner(
			ReminderConfig(
				Duration.ofMinutes(s.confirmIntervalMin.toLong()),
				s.maxConfirmations,
				Duration.ofMinutes(s.pauseIntervalMin.toLong()),
			),
			container.masterData.lunchBreaks.first().map { DayWindow(it.dayOfWeek, it.startMin, it.endMin) },
			container.masterData.schedule.first().map { DayWindow(it.dayOfWeek, it.startMin, it.endMin) },
			zone,
		)
		val entries = container.entries.openEntries.first().map {
			ReminderEntry(it.id, it.status, it.mode, it.lastConfirmedUtcMs, it.unansweredReminders)
		}
		val last = prefs.getLong(KEY_SUMMARY_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
		return Triple(planner, entries, last)
	}

	suspend fun reschedule() = lock.withLock {
		val (planner, entries, last) = state()
		val now = Instant.now()
		val am = context.getSystemService(AlarmManager::class.java)
		val pi = alarmIntent()
		/* something already due (e.g. after boot): fire soon, still through the receiver */
		val next = if (planner.due(entries, last, now).isNotEmpty()) now.plusSeconds(5) else planner.next(entries, last, now)
		if (next == null) {
			am.cancel(pi)
			return@withLock
		}
		val idle = context.getSystemService(PowerManager::class.java).isDeviceIdleMode
		if (idle)
			am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pi)
		else
			am.setWindow(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), WINDOW_MS, pi)
		Log.d(TAG, "next reminder at $next (idle=$idle)")
	}

	/* Posts the due reminders, records them, re-arms. */
	suspend fun fire() {
		lock.withLock {
			val (planner, entries, last) = state()
			val now = Instant.now()
			val due = planner.due(entries, last, now)
			if (due.isEmpty())
				return@withLock
			val infos = entryInfos()
			val nm = context.getSystemService(NotificationManager::class.java)
			for (r in due) {
				when (r) {
					is Reminder.Confirm -> {
						infos[r.entryId]?.let { nm.notify(NotificationIds.of(it.id, NotificationIds.CONFIRM), ReminderNotifications.confirm(context, it)) }
						container.entries.markReminded(r.entryId)
					}
					is Reminder.Pause -> {
						infos[r.entryId]?.let { nm.notify(NotificationIds.of(it.id, NotificationIds.PAUSE), ReminderNotifications.pause(context, it)) }
						container.entries.markPauseReminded(r.entryId)
					}
					is Reminder.DaySummary -> {
						postSummary(nm, r.day)
						prefs.edit { putLong(KEY_SUMMARY_DAY, r.day.toEpochDay()) }
					}
					/* not planned yet: no DayStartState is passed (M2 step 8) */
					is Reminder.DayStart -> {}
				}
			}
		}
		reschedule()
	}

	private suspend fun entryInfos(): Map<Long, EntryInfo> {
		val clients = container.masterData.clients.first().associateBy { it.id }
		val companies = container.masterData.companies.first().associateBy { it.id }
		val fallback = context.getString(R.string.app_name)
		return container.entries.openEntries.first().associate { e ->
			val title = e.clientId?.let { clients[it]?.name } ?: e.companyId?.let { companies[it]?.name } ?: fallback
			e.id to EntryInfo(e, title, emptyList())
		}
	}

	/* Solar vs reported totals of [day]; nothing when the day has no time. */
	private suspend fun postSummary(nm: NotificationManager, day: LocalDate) {
		val s = container.settings.current()
		val totals = container.reports.days(day, day, ZoneId.systemDefault(), s.rounding, s.carryOverMin).firstOrNull()
			?: return
		nm.notify(
			NotificationIds.of(0, NotificationIds.DAY_SUMMARY),
			ReminderNotifications.daySummary(context, totals.solar, totals.reported),
		)
	}

	private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
		context,
		0,
		Intent(context, ReminderReceiver::class.java),
		PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
	)

	private companion object {
		const val TAG = "ReminderScheduler"
		const val KEY_SUMMARY_DAY = "last_summary_day"
		const val WINDOW_MS = 2 * 60_000L
	}
}
