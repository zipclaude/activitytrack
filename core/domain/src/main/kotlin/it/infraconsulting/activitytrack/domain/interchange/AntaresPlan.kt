/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import it.infraconsulting.activitytrack.domain.model.EntryTimeline
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.report.DayAttribution
import it.infraconsulting.activitytrack.domain.report.DayAttributionPolicy
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/* A closed segment with the km of a travel. */
data class PlanSegment(val kind: SegmentKind, val start: Instant, val end: Instant, val km: Double? = null)

/*
 * One Antares activity of an entry: [index] numbers the blocks of [day]
 * (a long pause splits the day's work). Times as sent: [from] the first
 * work start, [to] from + the block's length rounded by the work rule.
 */
data class AntaresChunk(
	val day: LocalDate,
	val index: Int,
	val from: Instant,
	val to: Instant,
	val travelMinutes: Long,
	val travelKm: Long,
)

/*
 * How an entry becomes Antares activities (owner, 2026-10-19): one activity
 * per report day (the report's day attribution, night carry-over
 * included), from the first work start to the last work end with the
 * pauses inside (Antares has none) and the length rounded by the work rule;
 * a pause longer than [split] starts a new activity. Travel is not an
 * activity of its own: its rounded minutes and km go to the nearest block
 * of the same day. Days with travel only give nothing.
 */
object AntaresPlan {
	fun chunks(
		segments: List<PlanSegment>,
		zone: ZoneId,
		rounding: ReportRounding,
		policy: DayAttributionPolicy,
		split: Duration,
	): List<AntaresChunk> {
		if (segments.isEmpty())
			return emptyList()
		val timeline = EntryTimeline(0, segments.map { Segment(it.kind, it.start, it.end) })
		val end = segments.maxOf { it.end }
		val parts = DayAttribution.attribute(timeline, zone, end, policy)
		/* km of a travel go with its first part */
		val kmAt = segments.filter { it.kind == SegmentKind.TRAVEL }.associate { it.start to (it.km ?: 0.0) }
		val out = ArrayList<AntaresChunk>()
		for ((day, dayParts) in parts.groupBy { it.day }.toSortedMap()) {
			val work = dayParts.filter { it.kind == SegmentKind.WORK }.map { it.interval }.sortedBy { it.start }
			if (work.isEmpty())
				continue
			/* blocks of work, split by gaps longer than [split] */
			val blocks = ArrayList<MutableList<it.infraconsulting.activitytrack.domain.model.Interval>>()
			for (iv in work) {
				val last = blocks.lastOrNull()
				if (last == null || Duration.between(last.last().end, iv.start) > split)
					blocks += mutableListOf(iv)
				else
					last += iv
			}
			val travelMs = LongArray(blocks.size)
			val km = DoubleArray(blocks.size)
			for (t in dayParts.filter { it.kind == SegmentKind.TRAVEL }) {
				val i = blocks.indices.minBy { b ->
					val from = blocks[b].first().start
					val to = blocks[b].last().end
					when {
						!t.interval.end.isAfter(from) -> Duration.between(t.interval.end, from).toMillis()
						!t.interval.start.isBefore(to) -> Duration.between(to, t.interval.start).toMillis()
						else -> 0L
					}
				}
				travelMs[i] += Duration.between(t.interval.start, t.interval.end).toMillis()
				km[i] += kmAt[t.interval.start] ?: 0.0
			}
			blocks.forEachIndexed { i, b ->
				val from = b.first().start
				val length = rounding.work.apply(Duration.between(from, b.last().end))
				val travel = if (travelMs[i] == 0L) Duration.ZERO else rounding.travel.apply(Duration.ofMillis(travelMs[i]))
				out += AntaresChunk(day, i, from, from + length, travel.toMinutes(), km[i].roundToLong())
			}
		}
		return out
	}
}
