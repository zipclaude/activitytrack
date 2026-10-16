/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.report

import it.infraconsulting.activitytrack.domain.model.EntryTimeline
import it.infraconsulting.activitytrack.domain.model.Interval
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Figures of one entry on one day. Pause is the complement of the worked and
 * travelled time within [firstStart, lastEnd]: it includes explicit pauses
 * and the gaps while the entry was suspended.
 */
data class EntryDayLine(
	val entryId: Long,
	val day: LocalDate,
	val firstStart: Instant,
	val lastEnd: Instant,
	val work: Duration,
	val travel: Duration,
	val pause: Duration,
	val roundedWork: Duration,
	val roundedTravel: Duration,
	val roundedPause: Duration,
) {
	val reported: Duration
		get() = roundedWork + roundedTravel
}

/*
 * Totals of one day.
 * solar: wall-clock time covered by WORK or TRAVEL of any entry (overlaps
 *        between concurrent entries are counted once).
 * reported: sum of the rounded WORK and TRAVEL of every entry.
 */
data class DayTotals(
	val day: LocalDate,
	val solar: Duration,
	val reported: Duration,
	val lines: List<EntryDayLine>,
)

object DailyReport {
	fun build(
		timelines: List<EntryTimeline>,
		zone: ZoneId,
		now: Instant,
		rounding: ReportRounding = ReportRounding.DEFAULT,
		policy: DayAttributionPolicy = DayAttributionPolicy(),
	): List<DayTotals> {
		val lines = ArrayList<EntryDayLine>()
		val active = HashMap<LocalDate, MutableList<Interval>>()

		for (t in timelines) {
			val parts = DayAttribution.attribute(t, zone, now, policy)
			for ((day, dayParts) in parts.groupBy { it.day }.toSortedMap()) {
				val work = sum(dayParts.filter { it.kind == SegmentKind.WORK }.map { it.interval })
				val travel = sum(dayParts.filter { it.kind == SegmentKind.TRAVEL }.map { it.interval })
				val first = dayParts.minOf { it.interval.start }
				val last = dayParts.maxOf { it.interval.end }
				val pause = Duration.between(first, last) - work - travel

				lines += EntryDayLine(
					entryId = t.entryId,
					day = day,
					firstStart = first,
					lastEnd = last,
					work = work,
					travel = travel,
					pause = pause,
					roundedWork = rounding.work.apply(work),
					roundedTravel = rounding.travel.apply(travel),
					roundedPause = rounding.pause.apply(pause),
				)
				active.getOrPut(day) { ArrayList() } +=
					dayParts.filter { it.kind != SegmentKind.PAUSE }.map { it.interval }
			}
		}

		return lines.groupBy { it.day }.toSortedMap().map { (day, dayLines) ->
			DayTotals(
				day = day,
				solar = sum(union(active[day].orEmpty())),
				reported = dayLines.fold(Duration.ZERO) { acc, l -> acc + l.reported },
				lines = dayLines.sortedBy { it.firstStart },
			)
		}
	}

	/* Merge overlapping or touching intervals. */
	fun union(intervals: List<Interval>): List<Interval> {
		val sorted = intervals.filter { !it.isEmpty() }.sortedBy { it.start }
		val out = ArrayList<Interval>()
		for (iv in sorted) {
			val tail = out.lastOrNull()
			if (tail != null && !iv.start.isAfter(tail.end))
				out[out.size - 1] = Interval(tail.start, maxOf(tail.end, iv.end))
			else
				out += iv
		}
		return out
	}

	private fun sum(intervals: List<Interval>): Duration =
		intervals.fold(Duration.ZERO) { acc, iv -> acc + iv.duration }
}
