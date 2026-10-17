/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.model.EntryTimeline
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.report.DailyReport
import it.infraconsulting.activitytrack.domain.report.DayAttributionPolicy
import it.infraconsulting.activitytrack.domain.report.DayTotals
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* Entries with all their segments, as loaded for reports. */
data class EntryWithSegments(val entry: TimeEntryEntity, val segments: List<SegmentEntity>) {
	fun timeline() = EntryTimeline(
		entry.id,
		segments.map { Segment(it.kind, Instant.ofEpochMilli(it.startUtcMs), it.endUtcMs?.let(Instant::ofEpochMilli)) },
	)
}

/*
 * Read side of the reports (A13: rounding and day attribution happen here,
 * never in stored data).
 */
class ReportRepository(
	db: ActivityTrackDatabase,
	private val clock: Clock,
) {
	private val dao = db.entryDao()

	/* Entries touching the local days [from, to] (inclusive), padded by a day for the carry-over rule. */
	suspend fun entriesAround(from: LocalDate, to: LocalDate, zone: ZoneId): List<EntryWithSegments> {
		val start = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
		val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
		val segs = dao.segmentsOfEntriesTouching(start, end).groupBy { it.entryId }
		if (segs.isEmpty())
			return emptyList()
		return dao.entries(segs.keys.toList()).map { EntryWithSegments(it, segs[it.id].orEmpty()) }
	}

	/* Totals of the local days [from, to]; days without time are absent. */
	suspend fun days(
		from: LocalDate,
		to: LocalDate,
		zone: ZoneId,
		rounding: ReportRounding,
		carryOverMin: Int,
	): List<DayTotals> {
		val entries = entriesAround(from, to, zone)
		val policy = DayAttributionPolicy(java.time.LocalTime.MIN.plusMinutes(carryOverMin.toLong()))
		return DailyReport.build(entries.map { it.timeline() }, zone, clock.instant(), rounding, policy)
			.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
	}
}
