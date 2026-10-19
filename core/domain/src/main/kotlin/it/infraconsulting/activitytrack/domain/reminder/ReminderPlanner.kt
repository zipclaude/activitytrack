/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.reminder

import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.time.atMinute
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* Reminder-relevant state of an entry. */
data class ReminderEntry(
	val id: Long,
	val status: EntryStatus,
	val mode: SegmentKind?,
	/* last "still working?" yes, or the start of the current mode; last pause reminder while in PAUSE */
	val lastConfirmedMs: Long?,
	val unanswered: Int,
	/* indicative end chosen at start (M22) */
	val plannedEndMs: Long? = null,
)

/* A daily time window in local minutes since midnight; dayOfWeek 1 = Monday. */
data class DayWindow(val dayOfWeek: Int, val startMin: Int, val endMin: Int)

data class ReminderConfig(
	val confirmInterval: Duration,
	val maxConfirmations: Int,	/* 0 = unlimited */
	val pauseInterval: Duration,
	val dayStartDelay: Duration = Duration.ofMinutes(15),
	/* planned to-dos: reminder this long before the planned start */
	val planLead: Duration = Duration.ofMinutes(15),
)

/*
 * A planned, open to-do: its planned [start] and shown [end]; [remindedFor]
 * is the planned start (UTC ms) whose reminder was already shown.
 */
data class ReminderTodo(val id: Long, val start: Instant, val end: Instant, val remindedFor: Long?)

/*
 * Day-start prompt state: the last day it was shown, and the days already
 * handled (an entry started or an absence recorded).
 */
data class DayStartState(val lastShown: LocalDate?, val handled: Set<LocalDate>)

sealed interface Reminder {
	val at: Instant

	/* "Still working on X?" for an ACTIVE WORK/TRAVEL entry */
	data class Confirm(val entryId: Long, override val at: Instant) : Reminder

	/* "Still on pause?" for an entry in PAUSE */
	data class Pause(val entryId: Long, override val at: Instant) : Reminder

	/* end-of-day totals at the end of the day's working hours */
	data class DaySummary(val day: LocalDate, override val at: Instant) : Reminder

	/* "No activity yet": absence / late entry / start, delay after the first working interval starts */
	data class DayStart(val day: LocalDate, override val at: Instant) : Reminder

	/* "Planned at HH:MM": start / postpone / re-plan, lead time before a planned to-do */
	data class Planned(val todoId: Long, override val at: Instant) : Reminder

	/* "X finished?" at the end chosen when starting (M22): stop there or go on */
	data class PlannedEnd(val entryId: Long, override val at: Instant) : Reminder
}

/*
 * Pure reminder schedule (DESIGN §6.1, SPEC §5). For every source it gives
 * the next reminder; those at or before now are due, the earliest later one
 * arms the single alarm.
 *
 * - Confirm: lastConfirmed + interval * (unanswered + 1), also outside
 *   working hours, until maxConfirmations unanswered (0 = unlimited).
 * - Pause: lastConfirmed + interval, where lastConfirmed is moved to the
 *   time of each pause reminder; a time inside the day's lunch break is
 *   postponed to its end (mute).
 * - DaySummary: at the end of the last working interval of the day, once
 *   per day; days without working hours have no summary.
 * - DayStart: first working interval start + delay, once per day, on
 *   working days that are not off (holiday or manual) and not handled yet;
 *   skipped once the day's working hours are over.
 * - Planned: planned start - lead, once per planned start (a new plan or a
 *   postponement reminds again), also outside working hours and on days off;
 *   dropped once the planned block is over.
 * - PlannedEnd: at the end chosen when starting, while the entry is active
 *   (also paused); asked once, then the planned end is cleared.
 */
class ReminderPlanner(
	private val config: ReminderConfig,
	private val lunchBreaks: List<DayWindow>,
	private val schedule: List<DayWindow>,
	private val zone: ZoneId,
	private val isDayOff: (LocalDate) -> Boolean = { false },
) {
	fun planned(
		entries: List<ReminderEntry>,
		lastSummaryDay: LocalDate?,
		now: Instant,
		dayStart: DayStartState? = null,
		todos: List<ReminderTodo> = emptyList(),
	): List<Reminder> {
		val out = ArrayList<Reminder>()
		for (t in todos)
			if (t.remindedFor != t.start.toEpochMilli() && now.isBefore(t.end))
				out += Reminder.Planned(t.id, t.start - config.planLead)
		for (e in entries) {
			if (e.status == EntryStatus.ACTIVE && e.plannedEndMs != null)
				out += Reminder.PlannedEnd(e.id, Instant.ofEpochMilli(e.plannedEndMs))
			if (e.status != EntryStatus.ACTIVE || e.lastConfirmedMs == null)
				continue
			val base = Instant.ofEpochMilli(e.lastConfirmedMs)
			when (e.mode) {
				SegmentKind.WORK, SegmentKind.TRAVEL ->
					if (config.maxConfirmations == 0 || e.unanswered < config.maxConfirmations)
						out += Reminder.Confirm(e.id, base + config.confirmInterval.multipliedBy(e.unanswered + 1L))
				SegmentKind.PAUSE -> out += Reminder.Pause(e.id, outsideLunch(base + config.pauseInterval))
				null -> {}
			}
		}
		summary(lastSummaryDay, now)?.let { out += it }
		dayStart?.let { st -> dayStart(st, now)?.let { out += it } }
		return out
	}

	fun due(
		entries: List<ReminderEntry>,
		lastSummaryDay: LocalDate?,
		now: Instant,
		dayStart: DayStartState? = null,
		todos: List<ReminderTodo> = emptyList(),
	): List<Reminder> =
		planned(entries, lastSummaryDay, now, dayStart, todos).filter { !it.at.isAfter(now) }

	fun next(
		entries: List<ReminderEntry>,
		lastSummaryDay: LocalDate?,
		now: Instant,
		dayStart: DayStartState? = null,
		todos: List<ReminderTodo> = emptyList(),
	): Instant? =
		planned(entries, lastSummaryDay, now, dayStart, todos).map { it.at }.filter { it.isAfter(now) }.minOrNull()

	/* Postpone [t] to the end of the lunch break it falls in, if any. */
	fun outsideLunch(t: Instant): Instant {
		val local = t.atZone(zone)
		val min = local.hour * 60 + local.minute
		val lunch = lunchBreaks.firstOrNull {
			it.dayOfWeek == local.dayOfWeek.value && min >= it.startMin && min < it.endMin
		} ?: return t
		return local.toLocalDate().atMinute(lunch.endMin, zone)
	}

	/* Today's prompt unless shown, handled or too late, otherwise the next working day (within a week). */
	private fun dayStart(st: DayStartState, now: Instant): Reminder.DayStart? {
		val today = now.atZone(zone).toLocalDate()
		for (offset in 0L..7L) {
			val day = today.plusDays(offset)
			if (day == st.lastShown || day in st.handled || isDayOff(day))
				continue
			val windows = schedule.filter { it.dayOfWeek == day.dayOfWeek.value }
			if (windows.isEmpty())
				continue
			if (day == today && !now.isBefore(day.atMinute(windows.maxOf { it.endMin }, zone)))
				continue
			return Reminder.DayStart(day, day.atMinute(windows.minOf { it.startMin }, zone) + config.dayStartDelay)
		}
		return null
	}

	/* Today's summary unless already shown, otherwise the next day with working hours (within a week). */
	private fun summary(lastSummaryDay: LocalDate?, now: Instant): Reminder.DaySummary? {
		val today = now.atZone(zone).toLocalDate()
		for (offset in 0L..7L) {
			val day = today.plusDays(offset)
			if (day == lastSummaryDay)
				continue
			val end = schedule.filter { it.dayOfWeek == day.dayOfWeek.value }.maxOfOrNull { it.endMin } ?: continue
			val at = day.atMinute(end, zone)
			/* a past summary of an earlier day is never shown late */
			if (day == today || at.isAfter(now))
				return Reminder.DaySummary(day, at)
		}
		return null
	}
}
