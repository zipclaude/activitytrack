/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.reminders

import it.infraconsulting.activitytrack.ui.todo.TodoRepository
import it.infraconsulting.activitytrack.domain.todo.nextWorkingDay
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
import it.infraconsulting.activitytrack.data.repo.isAbsence
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.domain.activity.ActivityStateMachine
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.ActivitySegment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
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
				combine(container.entries.absences, container.masterData.daysOff, container.entries.todos) { _, _, _ -> },
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
	 * TODO activities with a plan (the reminder is on the activity's plan,
	 * ACTIVITY-MODEL.md 7). A shown reminder goes away when the activity is
	 * started, ticked, deleted, re-planned or the plan is over.
	 */
	private suspend fun plannedTodos(zone: ZoneId, nm: NotificationManager): List<ReminderTodo> {
		val now = Instant.now()
		val out = ArrayList<ReminderTodo>()
		for (t in container.entries.todos.first()) {
			/* no reminder for a plan by day only (issue #52, answer 3), nor for an absence (issue #158) */
			val p = t.plan()?.takeIf { !it.allDay && !t.isAbsence() }
			val start = p?.start(zone)
			/* after the block (a point: its first hour) a reminder is moot */
			val end = p?.let { maxOf(it.end(zone), it.start(zone).plus(java.time.Duration.ofHours(1))) }
			val live = start != null && end != null && t.status == ActivityStatus.TODO && t.deletedUtcMs == null && now.isBefore(end)
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
						postUnworked(nm, r.day)
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
			/* the segments tell what "Riprendi" resumes after a pause */
			val segments = if (e.mode == SegmentKind.PAUSE) container.entries.segments(e.id).first() else emptyList()
			e.id to EntryInfo(e, title, segments)
		}
	}

	/* The reminder of a planned to-do, recorded against its planned start. */
	private suspend fun postPlanned(nm: NotificationManager, todoId: Long) {
		val t = container.entries.find(todoId)?.takeIf { it.status == ActivityStatus.TODO && it.deletedUtcMs == null } ?: return
		val start = t.plan()?.start(ZoneId.systemDefault())?.toEpochMilli() ?: return
		val client = t.clientId?.let { id -> container.masterData.clients.first().firstOrNull { it.id == id }?.name }
		val company = container.masterData.companies.first().firstOrNull { it.id == t.companyId }?.name
		nm.notify(
			NotificationIds.of(todoId, NotificationIds.PLANNED),
			ReminderNotifications.planned(context, todoId, t.activity.orEmpty(), client ?: company, start, container.settings.current().planSnoozeMin),
		)
		container.entries.markPlanReminded(todoId, start)
	}

	/* The planned to-dos of [day] not worked, with the next working day proposed (issue #52). */
	private suspend fun postUnworked(nm: NotificationManager, day: LocalDate) {
		val zone = ZoneId.systemDefault()
		val todos = container.entries.unworked(day, zone)
		if (todos.isEmpty())
			return
		val weekdays = container.masterData.schedule.first().map { it.dayOfWeek }.toSet()
		val daysOff = container.masterData.daysOffBetween(day, day.plusDays(60))
		val next = nextWorkingDay(day, weekdays) { it in daysOff || ItalianHolidays.isHoliday(it) }
		nm.notify(NotificationIds.of(0, NotificationIds.UNWORKED), ReminderNotifications.unworked(context, todos.map { it.activity.orEmpty() }, todos.map { it.id }, next))
	}

	/* The reported total of [day] (issue #99); nothing when the day has no time. */
	private suspend fun postSummary(nm: NotificationManager, day: LocalDate) {
		val s = container.settings.current()
		val totals = container.reports.days(day, day, ZoneId.systemDefault(), s.rounding, s.carryOverMin).firstOrNull()
			?: return
		nm.notify(
			NotificationIds.of(0, NotificationIds.DAY_SUMMARY),
			ReminderNotifications.daySummary(context, totals.reported),
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
