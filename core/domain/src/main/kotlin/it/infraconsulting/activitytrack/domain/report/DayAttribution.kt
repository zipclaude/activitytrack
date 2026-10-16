/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.report

import it.infraconsulting.activitytrack.domain.model.EntryTimeline
import it.infraconsulting.activitytrack.domain.model.Interval
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Assignment of an entry's time to calendar days.
 *
 * If the entry ends no later than [carryOverUntil] on the day after its start
 * day, all its time is attributed to the start day (e.g. night maintenance
 * 22:00-04:30). Otherwise every segment is split at local midnights and each
 * part is attributed to its own calendar day.
 */
data class DayAttributionPolicy(
	val carryOverUntil: LocalTime = LocalTime.of(6, 0),
)

/* A segment part attributed to [day]. */
data class AttributedPart(
	val day: LocalDate,
	val kind: SegmentKind,
	val interval: Interval,
)

object DayAttribution {
	fun attribute(
		timeline: EntryTimeline,
		zone: ZoneId,
		now: Instant,
		policy: DayAttributionPolicy = DayAttributionPolicy(),
	): List<AttributedPart> {
		val first = timeline.firstStart ?: return emptyList()
		val last = timeline.lastEnd(now) ?: return emptyList()
		val startDay = first.atZone(zone).toLocalDate()
		val limit = startDay.plusDays(1).atTime(policy.carryOverUntil).atZone(zone).toInstant()

		val parts = ArrayList<AttributedPart>()
		for (seg in timeline.segments.sortedBy { it.start }) {
			val iv = seg.resolve(now)
			if (iv.isEmpty())
				continue
			if (!last.isAfter(limit))
				parts += AttributedPart(startDay, seg.kind, iv)
			else
				splitAtMidnight(iv, zone).forEach { (d, p) -> parts += AttributedPart(d, seg.kind, p) }
		}
		return parts
	}

	/* Split [iv] at every local midnight it crosses. */
	fun splitAtMidnight(iv: Interval, zone: ZoneId): List<Pair<LocalDate, Interval>> {
		val out = ArrayList<Pair<LocalDate, Interval>>()
		var cur = iv.start
		while (cur.isBefore(iv.end)) {
			val day = cur.atZone(zone).toLocalDate()
			val next = day.plusDays(1).atStartOfDay(zone).toInstant()
			val end = minOf(next, iv.end)
			out += day to Interval(cur, end)
			cur = end
		}
		return out
	}
}
