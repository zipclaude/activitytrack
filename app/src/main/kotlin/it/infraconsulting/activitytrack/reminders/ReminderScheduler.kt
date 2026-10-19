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
import it.infraconsulting.activitytrack.domain.calendar.ItalianHolidays
import it.infraconsulting.activitytrack.domain.reminder.DayStartState
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.reminder.Reminder
import it.infraconsulting.activitytrack.domain.reminder.ReminderConfig
import it.infraconsulting.activitytrack.domain.reminder.ReminderEntry
import it.infraconsulting.activitytrack.domain.reminder.ReminderPlanner
import it.infraconsulting.activitytrack.domain.reminder.ReminderTodo
import it.infraconsulting.activitytrack.data.repo.isOpen
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.domain.model.EntryStatus
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
 * and re-arms. Re-armed on every change of entries, settings, lunch breaks,
 * working hours or to-dos, and on boot / time changes (BootReceiver).
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
				combine(container.entries.absences, container.masterData.daysOff, container.todos.all) { _, _, _ -> },
			) { _, _, _, _, _ -> }.conflate().collect { reschedule() }
		}
	}

	private class Plan(
		val planner: ReminderPlanner,
		val entries: List<ReminderEntry>,
		val lastSummary: LocalDate?,
		val dayStart: DayStartState,
		val todos: List<ReminderTodo>,
	) {
		fun due(now: Instant) = planner.due(entries, lastSummary, now, dayStart, todos)
		fun next(now: Instant) = planner.next(entries, lastSummary, now, dayStart, todos)
	}

	private suspend fun state(): Plan {
		val s = container.settings.current()
		val zone = ZoneId.systemDefault()
		/* the planner looks at most a week ahead */
		val today = LocalDate.now(zone)
		val horizon = today.plusDays(8)
		val daysOff = container.masterData.daysOffBetween(today, horizon)
		val planner = ReminderPlanner(
			ReminderConfig(
				Duration.ofMinutes(s.confirmIntervalMin.toLong()),
				s.maxConfirmations,
				Duration.ofMinutes(s.pauseIntervalMin.toLong()),
				Duration.ofMinutes(s.dayStartDelayMin.toLong()),
				Duration.ofMinutes(s.planLeadMin.toLong()),
			),
			container.masterData.lunchBreaks.first().map { DayWindow(it.dayOfWeek, it.startMin, it.endMin) },
			container.masterData.schedule.first().map { DayWindow(it.dayOfWeek, it.startMin, it.endMin) },
			zone,
			isDayOff = { it in daysOff || ItalianHolidays.isHoliday(it) },
		)
		val entries = container.entries.openEntries.first().map {
			ReminderEntry(it.id, it.status, it.mode, it.lastConfirmedUtcMs, it.unansweredReminders, it.plannedEndUtcMs)
		}
		val last = prefs.getLong(KEY_SUMMARY_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
		val lastDayStart = prefs.getLong(KEY_DAY_START, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
		val handled = container.entries.handledDays(today, horizon, zone)
		/* the day got handled after the prompt (entry, absence): the prompt is moot */
		val nm = context.getSystemService(NotificationManager::class.java)
		if (today in handled)
			nm.cancel(NotificationIds.of(0, NotificationIds.DAY_START))
		return Plan(planner, entries, last, DayStartState(lastDayStart, handled), plannedTodos(zone, nm))
	}

	/*
	 * Open planned to-dos without a running entry of their own. A shown
	 * reminder goes away when the to-do is started, done, re-planned or over.
	 */
	private suspend fun plannedTodos(zone: ZoneId, nm: NotificationManager): List<ReminderTodo> {
		val now = Instant.now()
		val started = container.entries.openEntries.first().filter { it.status == EntryStatus.ACTIVE }.mapNotNull { it.todoId }.toSet()
		val out = ArrayList<ReminderTodo>()
		for (t in container.todos.all.first()) {
			val p = t.plan()
			val start = p?.start(zone)
			/* the block as planned (duration or first hour): a reminder after it is moot */
			val end = p?.end(zone, open = false, now = now)
			val live = start != null && end != null && t.isOpen() && t.id !in started && now.isBefore(end)
			if (live)
				out += ReminderTodo(t.id, start!!, end!!, t.planRemindedUtcMs)
			if (t.planRemindedUtcMs != null && (!live || t.planRemindedUtcMs != start?.toEpochMilli()))
				nm.cancel(NotificationIds.of(t.id, NotificationIds.PLANNED))
		}
		return out
	}

	suspend fun reschedule() = lock.withLock {
		val plan = state()
		val now = Instant.now()
		val am = context.getSystemService(AlarmManager::class.java)
		val pi = alarmIntent()
		/* something already due (e.g. after boot): fire soon, still through the receiver */
		val next = if (plan.due(now).isNotEmpty()) now.plusSeconds(5) else plan.next(now)
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
			val now = Instant.now()
			val due = state().due(now)
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
					is Reminder.DayStart -> {
						nm.notify(NotificationIds.of(0, NotificationIds.DAY_START), ReminderNotifications.dayStart(context, r.day))
						prefs.edit { putLong(KEY_DAY_START, r.day.toEpochDay()) }
					}
					is Reminder.Planned -> postPlanned(nm, r.todoId)
					is Reminder.PlannedEnd -> {
						infos[r.entryId]?.let {
							nm.notify(NotificationIds.of(it.entry.id, NotificationIds.PLANNED_END), ReminderNotifications.plannedEnd(context, it, r.at.toEpochMilli()))
						}
						container.entries.clearPlannedEnd(r.entryId)
					}
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

	/* The reminder of a planned to-do, recorded against its planned start. */
	private suspend fun postPlanned(nm: NotificationManager, todoId: Long) {
		val t = container.todos.find(todoId) ?: return
		val start = t.plan()?.start(ZoneId.systemDefault())?.toEpochMilli() ?: return
		val client = t.clientId?.let { id -> container.masterData.clients.first().firstOrNull { it.id == id }?.name }
		val company = container.masterData.companies.first().firstOrNull { it.id == t.companyId }?.name
		nm.notify(
			NotificationIds.of(todoId, NotificationIds.PLANNED),
			ReminderNotifications.planned(context, todoId, t.title, client ?: company, start, container.settings.current().planSnoozeMin),
		)
		container.todos.markPlanReminded(todoId, start)
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
		const val KEY_DAY_START = "last_day_start"
		const val WINDOW_MS = 2 * 60_000L
	}
}
