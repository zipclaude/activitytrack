/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.time.atMinute
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* One ABSENCE segment: the [index]-th (from 0) of its [day], for the uid suffix `abs/<day>/<index>` (6). */
data class AbsenceInterval(val day: LocalDate, val index: Int, val start: Instant, val end: Instant)

/*
 * The segments of an absence (3.1), one per day:
 * - with a start and an end time: those times on every working day of the
 *   range (a "permesso"); over several days the days without working hours
 *   and the days off are skipped as below (coordinator, 2026-11-11), a
 *   single day is taken as chosen;
 * - without: every working day of the range, covering that weekday's
 *   working hours ([schedule]) minus its lunch break, so two segments when
 *   the break splits them; days without hours and days off are skipped.
 * Times are local wall clock in [zone] (DST days included).
 */
object AbsencePlanner {
	fun plan(
		from: LocalDate,
		to: LocalDate,
		startMin: Int?,
		endMin: Int?,
		schedule: List<DayWindow>,
		lunchBreaks: List<DayWindow>,
		isDayOff: (LocalDate) -> Boolean,
		zone: ZoneId,
	): List<AbsenceInterval> {
		require(!to.isBefore(from)) { "last day $to before the first $from" }
		require((startMin == null) == (endMin == null)) { "a start needs an end and vice versa: $startMin, $endMin" }
		require(startMin == null || (startMin in 0 until 1440 && endMin!! in startMin + 1..1440)) { "times out of range: $startMin-$endMin" }
		val out = ArrayList<AbsenceInterval>()
		var day = from
		while (!day.isAfter(to)) {
			val windows = when {
				startMin == null -> workingWindows(day, schedule, lunchBreaks, isDayOff)
				from == to || isWorkingDay(day, schedule, isDayOff) -> listOf(startMin to endMin!!)
				else -> emptyList()
			}
			windows.forEachIndexed { i, (a, b) -> out += AbsenceInterval(day, i, day.atMinute(a, zone), day.atMinute(b, zone)) }
			day = day.plusDays(1)
		}
		return out
	}

	/* a day with working hours that is not a day off */
	fun isWorkingDay(day: LocalDate, schedule: List<DayWindow>, isDayOff: (LocalDate) -> Boolean): Boolean =
		!isDayOff(day) && schedule.any { it.dayOfWeek == day.dayOfWeek.value && it.startMin < it.endMin }

	/*
	 * The working hours of [day] as (start, end) minutes, in order: the
	 * weekday's windows, merged where they overlap or touch (segments must
	 * not overlap), minus its lunch breaks; none on a day off.
	 */
	fun workingWindows(day: LocalDate, schedule: List<DayWindow>, lunchBreaks: List<DayWindow>, isDayOff: (LocalDate) -> Boolean): List<Pair<Int, Int>> {
		if (isDayOff(day))
			return emptyList()
		val dow = day.dayOfWeek.value
		val lunch = merged(lunchBreaks.filter { it.dayOfWeek == dow })
		val out = ArrayList<Pair<Int, Int>>()
		for ((ws, we) in merged(schedule.filter { it.dayOfWeek == dow })) {
			var cur = ws
			for ((ls, le) in lunch) {
				if (le <= cur || ls >= we)
					continue
				if (ls > cur)
					out += cur to ls
				cur = maxOf(cur, le)
			}
			if (cur < we)
				out += cur to we
		}
		return out
	}

	/* the non-empty windows as disjoint, non-touching (start, end) pairs in order */
	private fun merged(windows: List<DayWindow>): List<Pair<Int, Int>> {
		val out = ArrayList<Pair<Int, Int>>()
		for (w in windows.filter { it.startMin < it.endMin }.sortedBy { it.startMin }) {
			val last = out.lastOrNull()
			if (last != null && w.startMin <= last.second)
				out[out.size - 1] = last.first to maxOf(last.second, w.endMin)
			else
				out += w.startMin to w.endMin
		}
		return out
	}
}
