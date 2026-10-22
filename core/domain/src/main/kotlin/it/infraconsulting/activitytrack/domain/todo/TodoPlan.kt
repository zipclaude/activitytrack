/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.todo

import it.infraconsulting.activitytrack.domain.time.atMinute
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * When a to-do is planned (M9, owner decisions 2026-10-18; issue #52,
 * owner 2026-10-22): a local day, apart from the due date, and
 * - no start: the whole day (agenda strip, all-day calendar event, no
 *   reminder, overdue from the next day);
 * - a start without [durationMin]: a point in time, no length;
 * - a start and a duration: a block.
 * A to-do is open (no plan), planned (a plan) or done.
 */
data class TodoPlan(val day: LocalDate, val startMin: Int? = null, val durationMin: Int? = null) {
	init {
		require(startMin == null || startMin in 0 until 1440) { "start minute out of range: $startMin" }
		require(durationMin == null || (startMin != null && durationMin in 1..MAX_DURATION_MIN)) { "duration out of range: $durationMin" }
	}

	/* the whole day, no time */
	val allDay: Boolean get() = startMin == null

	/* The planned start; the start of the day for a whole-day plan. */
	fun start(zone: ZoneId): Instant = day.atMinute(startMin ?: 0, zone)

	/* The end: start + duration; the start itself for a point; the next midnight for a whole day. */
	fun end(zone: ZoneId): Instant = when {
		startMin == null -> day.plusDays(1).atStartOfDay(zone).toInstant()
		durationMin != null -> start(zone) + Duration.ofMinutes(durationMin.toLong())
		else -> start(zone)
	}

	/* Over: after its end; a whole day from the next day (issue #52, answer 4). */
	fun over(now: Instant, zone: ZoneId): Boolean = if (allDay) !now.isBefore(end(zone)) else now.isAfter(end(zone))

	/* The same plan [minutes] later, across midnight if needed (DST-safe wall clock); a whole day moves by days. */
	fun postponed(minutes: Int): TodoPlan {
		if (startMin == null)
			return copy(day = day.plusDays(maxOf(1L, (minutes + 1439L) / 1440)))
		val t = day.atTime(startMin / 60, startMin % 60).plusMinutes(minutes.toLong())
		return copy(day = t.toLocalDate(), startMin = t.hour * 60 + t.minute)
	}

	/*
	 * "Postpone" on the reminder (owner, 2026-10-18): [minutes] after the
	 * planned start, or after [now] (next whole minute) once the start has
	 * passed, so a late answer does not bring the reminder straight back.
	 */
	fun postponed(minutes: Int, now: Instant, zone: ZoneId): TodoPlan {
		if (startMin == null || !now.isAfter(start(zone)))
			return postponed(minutes)
		val local = now.atZone(zone).toLocalDateTime()
		val next = local.truncatedTo(ChronoUnit.MINUTES).let { if (it == local) it else it.plusMinutes(1) }
		return copy(day = next.toLocalDate(), startMin = next.hour * 60 + next.minute).postponed(minutes)
	}

	companion object {
		const val MAX_DURATION_MIN = 24 * 60

		/* A day makes a plan; the start and the duration are optional (a duration needs a start). */
		fun of(epochDay: Long?, startMin: Int?, durationMin: Int?): TodoPlan? =
			epochDay?.let { TodoPlan(LocalDate.ofEpochDay(it), startMin, durationMin?.takeIf { startMin != null }) }
	}
}

/*
 * A due date has passed (owner, 2026-10-18: a passed deadline is flagged
 * like a planned block that is over): after the due time, or after the due
 * day when no time is given.
 */
fun dueOverdue(dueDay: LocalDate?, dueMin: Int?, now: Instant, zone: ZoneId): Boolean {
	dueDay ?: return false
	val limit = if (dueMin != null) dueDay.atMinute(dueMin.coerceIn(0, 1440), zone) else dueDay.plusDays(1).atStartOfDay(zone).toInstant()
	return now.isAfter(limit)
}

/*
 * When a to-do next asks for attention: its planned start, else its due
 * time (a due date without time: the start of that day); null = neither.
 */
fun todoMoment(plan: TodoPlan?, dueDay: LocalDate?, dueMin: Int?, zone: ZoneId): Instant? =
	plan?.start(zone) ?: dueDay?.atMinute((dueMin ?: 0).coerceIn(0, 1440), zone)

/* In the next [hours] (owner, 2026-10-19: not limited to the current day), overdue included. */
fun withinHours(moment: Instant?, now: Instant, hours: Long = 24): Boolean =
	moment != null && !moment.isAfter(now.plus(Duration.ofHours(hours)))

/*
 * The working day after [day] (issue #52, the re-plan proposed at the end
 * of the working hours): a weekday with working hours that is not off.
 * Without any working weekday, simply the next day.
 */
fun nextWorkingDay(day: LocalDate, workingWeekdays: Set<Int>, isDayOff: (LocalDate) -> Boolean): LocalDate {
	if (workingWeekdays.isEmpty())
		return day.plusDays(1)
	var d = day.plusDays(1)
	repeat(366) {
		if (d.dayOfWeek.value in workingWeekdays && !isDayOff(d))
			return d
		d = d.plusDays(1)
	}
	return day.plusDays(1)
}
