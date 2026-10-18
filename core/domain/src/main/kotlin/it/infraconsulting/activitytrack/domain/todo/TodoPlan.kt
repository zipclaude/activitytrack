/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.todo

import it.infraconsulting.activitytrack.domain.time.atMinute
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * When a to-do is planned (M9, owner decisions 2026-10-18): a local day and
 * start time, apart from the due date; [durationMin] optional. A to-do is
 * open (no plan), planned (a plan) or done.
 */
data class TodoPlan(val day: LocalDate, val startMin: Int, val durationMin: Int? = null) {
	init {
		require(startMin in 0 until 1440) { "start minute out of range: $startMin" }
		require(durationMin == null || durationMin in 1..MAX_DURATION_MIN) { "duration out of range: $durationMin" }
	}

	fun start(zone: ZoneId): Instant = day.atMinute(startMin, zone)

	/*
	 * End shown in the agenda and the calendars: start + duration when given;
	 * otherwise one hour, grown hour by hour while the to-do stays open past
	 * it, up to the end of the planned day (owner: "un'ora e lo allunghi di
	 * ora in ora").
	 */
	fun end(zone: ZoneId, open: Boolean, now: Instant): Instant {
		val start = start(zone)
		durationMin?.let { return start + Duration.ofMinutes(it.toLong()) }
		val first = start + HOUR
		if (!open || !now.isAfter(first))
			return first
		val hours = (Duration.between(start, now).seconds + 3599) / 3600
		val grown = start + HOUR.multipliedBy(hours)
		return minOf(grown, maxOf(first, day.plusDays(1).atStartOfDay(zone).toInstant()))
	}

	/*
	 * The current end while it can still grow: just after it, [end] moves one
	 * hour on if the to-do is still open. null = fixed (duration, done, end of
	 * the day reached).
	 */
	fun nextGrowth(zone: ZoneId, open: Boolean, now: Instant): Instant? {
		if (durationMin != null || !open)
			return null
		val end = end(zone, true, now)
		val limit = maxOf(start(zone) + HOUR, day.plusDays(1).atStartOfDay(zone).toInstant())
		return end.takeIf { it < limit && !it.isBefore(now) }
	}

	/* The same plan [minutes] later, across midnight if needed (DST-safe wall clock). */
	fun postponed(minutes: Int): TodoPlan {
		val t = day.atTime(startMin / 60, startMin % 60).plusMinutes(minutes.toLong())
		return copy(day = t.toLocalDate(), startMin = t.hour * 60 + t.minute)
	}

	companion object {
		val HOUR: Duration = Duration.ofHours(1)
		const val MAX_DURATION_MIN = 24 * 60

		/* Both day and start, or no plan; anything else is not a plan. */
		fun of(epochDay: Long?, startMin: Int?, durationMin: Int?): TodoPlan? =
			if (epochDay == null || startMin == null) null else TodoPlan(LocalDate.ofEpochDay(epochDay), startMin, durationMin)
	}
}
