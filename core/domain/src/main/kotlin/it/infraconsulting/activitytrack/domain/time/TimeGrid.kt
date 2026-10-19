/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Time selection on a grid of 5-minute cells (backlog 3, owner chose the
 * grid mockup on 2026-10-19): one row per hour, 12 cells per row, a window
 * of [WINDOW_HOURS] rows. Cell i covers minutes [5i, 5i + 5) of the day.
 */
object TimeGrid {
	const val STEP = 5
	const val CELLS = 1440 / STEP
	const val WINDOW_HOURS = 12

	fun cellOf(minute: Int): Int = (minute.coerceIn(0, 1439)) / STEP

	/* The range covered by the cells from [a] to [b] in either order, end exclusive: [start, end). */
	fun range(a: Int, b: Int): IntRange {
		val lo = minOf(a, b).coerceIn(0, CELLS - 1)
		val hi = maxOf(a, b).coerceIn(0, CELLS - 1)
		return lo * STEP until (hi + 1) * STEP
	}

	/* First hour of the window showing [minute] with two hours before it, kept inside the day. */
	fun windowFor(minute: Int): Int = (minute.coerceIn(0, 1439) / 60 - 2).coerceIn(0, 24 - WINDOW_HOURS)

	/* The window moved by [hours], kept inside the day. */
	fun shift(first: Int, hours: Int): Int = (first + hours).coerceIn(0, 24 - WINDOW_HOURS)

	/*
	 * Minutes of [day] covered by the given intervals ([end] null = running
	 * until [now]), clipped to the day and merged: the cells shown as busy.
	 */
	fun busyMinutes(intervals: List<Pair<Instant, Instant?>>, day: LocalDate, zone: ZoneId, now: Instant): List<IntRange> {
		val dayStart = day.atStartOfDay(zone).toInstant()
		val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
		val parts = intervals.mapNotNull { (s, e) ->
			val from = maxOf(s, dayStart)
			val to = minOf(e ?: now, dayEnd)
			if (!to.isAfter(from))
				return@mapNotNull null
			minuteOf(from, day, zone) until minuteOf(to, day, zone).coerceAtLeast(minuteOf(from, day, zone) + 1)
		}.sortedBy { it.first }
		val out = ArrayList<IntRange>()
		for (p in parts) {
			val last = out.lastOrNull()
			if (last != null && p.first <= last.last + 1)
				out[out.size - 1] = last.first..maxOf(last.last, p.last)
			else
				out += p
		}
		return out
	}

	/* A cell is busy if any of its minutes is. */
	fun busyCell(cell: Int, busy: List<IntRange>): Boolean {
		val from = cell * STEP
		val to = from + STEP - 1
		return busy.any { it.first <= to && it.last >= from }
	}

	/* Minute of the day of [t] on [day] (1440 = the next midnight). */
	private fun minuteOf(t: Instant, day: LocalDate, zone: ZoneId): Int {
		if (!t.isBefore(day.plusDays(1).atStartOfDay(zone).toInstant()))
			return 1440
		val z = t.atZone(zone)
		return if (z.toLocalDate() == day) z.hour * 60 + z.minute else 0
	}
}
