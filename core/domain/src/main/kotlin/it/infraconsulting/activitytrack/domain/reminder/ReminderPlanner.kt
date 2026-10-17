/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.reminder

import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
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
)

/* A daily time window in local minutes since midnight; dayOfWeek 1 = Monday. */
data class DayWindow(val dayOfWeek: Int, val startMin: Int, val endMin: Int)

data class ReminderConfig(
	val confirmInterval: Duration,
	val maxConfirmations: Int,	/* 0 = unlimited */
	val pauseInterval: Duration,
)

sealed interface Reminder {
	val at: Instant

	/* "Still working on X?" for an ACTIVE WORK/TRAVEL entry */
	data class Confirm(val entryId: Long, override val at: Instant) : Reminder

	/* "Still on pause?" for an entry in PAUSE */
	data class Pause(val entryId: Long, override val at: Instant) : Reminder

	/* end-of-day totals at the end of the day's working hours */
	data class DaySummary(val day: LocalDate, override val at: Instant) : Reminder
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
 */
class ReminderPlanner(
	private val config: ReminderConfig,
	private val lunchBreaks: List<DayWindow>,
	private val schedule: List<DayWindow>,
	private val zone: ZoneId,
) {
	fun planned(entries: List<ReminderEntry>, lastSummaryDay: LocalDate?, now: Instant): List<Reminder> {
		val out = ArrayList<Reminder>()
		for (e in entries) {
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
		return out
	}

	fun due(entries: List<ReminderEntry>, lastSummaryDay: LocalDate?, now: Instant): List<Reminder> =
		planned(entries, lastSummaryDay, now).filter { !it.at.isAfter(now) }

	fun next(entries: List<ReminderEntry>, lastSummaryDay: LocalDate?, now: Instant): Instant? =
		planned(entries, lastSummaryDay, now).map { it.at }.filter { it.isAfter(now) }.minOrNull()

	/* Postpone [t] to the end of the lunch break it falls in, if any. */
	fun outsideLunch(t: Instant): Instant {
		val local = t.atZone(zone)
		val min = local.hour * 60 + local.minute
		val lunch = lunchBreaks.firstOrNull {
			it.dayOfWeek == local.dayOfWeek.value && min >= it.startMin && min < it.endMin
		} ?: return t
		return local.toLocalDate().atStartOfDay(zone).plusMinutes(lunch.endMin.toLong()).toInstant()
	}

	/* Today's summary unless already shown, otherwise the next day with working hours (within a week). */
	private fun summary(lastSummaryDay: LocalDate?, now: Instant): Reminder.DaySummary? {
		val today = now.atZone(zone).toLocalDate()
		for (offset in 0L..7L) {
			val day = today.plusDays(offset)
			if (day == lastSummaryDay)
				continue
			val end = schedule.filter { it.dayOfWeek == day.dayOfWeek.value }.maxOfOrNull { it.endMin } ?: continue
			val at = day.atStartOfDay(zone).plusMinutes(end.toLong()).toInstant()
			/* a past summary of an earlier day is never shown late */
			if (day == today || at.isAfter(now))
				return Reminder.DaySummary(day, at)
		}
		return null
	}
}
