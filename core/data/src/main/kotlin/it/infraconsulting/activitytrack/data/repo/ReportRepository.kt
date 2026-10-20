/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.export.EntryMeta
import it.infraconsulting.activitytrack.domain.export.ExportRow
import it.infraconsulting.activitytrack.domain.export.ExportRows
import it.infraconsulting.activitytrack.domain.model.EntryTimeline
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.report.DailyReport
import it.infraconsulting.activitytrack.domain.report.DayAttributionPolicy
import it.infraconsulting.activitytrack.domain.report.DayTotals
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/* Entries with all their segments, as loaded for reports. */
data class EntryWithSegments(val entry: TimeEntryEntity, val segments: List<SegmentEntity>) {
	fun timeline() = EntryTimeline(
		entry.id,
		segments.map { Segment(it.kind, Instant.ofEpochMilli(it.startUtcMs), it.endUtcMs?.let(Instant::ofEpochMilli)) },
	)
}

/* Day totals of a period plus the descriptive data of every entry in it. */
data class Report(val days: List<DayTotals>, val meta: Map<Long, EntryMeta>)

/*
 * Read side of the reports (A13: rounding and day attribution happen here,
 * never in stored data). Coordinates are never read.
 */
class ReportRepository(
	db: ActivityTrackDatabase,
	private val clock: Clock,
) {
	private val dao = db.entryDao()
	private val master = db.masterDataDao()
	private val materialDao = db.materialDao()

	/* Emits on every entry change; screens reload their report on it. */
	val changes = dao.observeLastChange()

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
	): List<DayTotals> = build(entriesAround(from, to, zone), from, to, zone, rounding, carryOverMin)

	suspend fun report(
		from: LocalDate,
		to: LocalDate,
		zone: ZoneId,
		rounding: ReportRounding,
		carryOverMin: Int,
	): Report {
		val entries = entriesAround(from, to, zone)
		val days = build(entries, from, to, zone, rounding, carryOverMin)
		val ids = entries.map { it.entry.id }
		if (ids.isEmpty())
			return Report(days, emptyMap())
		val refs = dao.externalRefs(ids).groupBy({ it.entryId }, { it.value })
		val expenses = dao.expenseTotals(ids).associate { it.entryId to it.cents }
		val materials = if (ids.isEmpty()) emptyMap() else materialDao.linesOf(ids).groupBy { it.entryId }
			.mapValues { (_, lines) -> lines.sumOf { it.totalCents ?: 0 } }
		val tags = dao.tagsOf(ids).groupBy({ it.entryId }, { it.tag }).mapValues { it.value.sorted() }
		val companies = master.allCompanies().associate { it.id to it.name }
		val clients = master.allClients().associate { it.id to it.name }
		val roles = master.allRoles().associate { it.id to it.name }
		val meta = entries.associate { (e, segs) ->
			val km = segs.filter { it.kind == SegmentKind.TRAVEL }.mapNotNull { it.kmDeclared ?: it.kmEstimated }
			e.id to EntryMeta(
				company = e.companyId?.let { companies[it] }.orEmpty(),
				client = e.clientId?.let { clients[it] }.orEmpty(),
				role = e.roleId?.let { roles[it] }.orEmpty(),
				activity = e.activity.orEmpty(),
				notes = e.notes.orEmpty(),
				locality = e.locality.orEmpty(),
				externalRefs = refs[e.id].orEmpty(),
				km = if (km.isEmpty()) null else km.sum(),
				expensesCents = expenses[e.id] ?: 0,
				tags = tags[e.id].orEmpty(),
				materialsCents = materials[e.id] ?: 0,
			)
		}
		return Report(days, meta)
	}

	suspend fun exportRows(
		from: LocalDate,
		to: LocalDate,
		zone: ZoneId,
		rounding: ReportRounding,
		carryOverMin: Int,
	): List<ExportRow> {
		val r = report(from, to, zone, rounding, carryOverMin)
		return ExportRows.build(r.days, r.meta, zone)
	}

	private fun build(
		entries: List<EntryWithSegments>,
		from: LocalDate,
		to: LocalDate,
		zone: ZoneId,
		rounding: ReportRounding,
		carryOverMin: Int,
	): List<DayTotals> {
		val policy = DayAttributionPolicy(LocalTime.MIN.plusMinutes(carryOverMin.toLong()))
		return DailyReport.build(entries.map { it.timeline() }, zone, clock.instant(), rounding, policy)
			.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
	}
}
