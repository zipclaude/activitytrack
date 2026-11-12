/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.export

import it.infraconsulting.activitytrack.domain.report.DayTotals
import java.io.OutputStream
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * One exported line: an entry on one day (DESIGN §11). Carries only text
 * and durations; coordinates never reach exports (SPEC §3.2).
 * Durations are the rounded values of the export profile.
 */
data class ExportRow(
	val start: LocalDateTime,
	val end: LocalDateTime,
	val work: Duration,
	val pause: Duration,
	val travel: Duration,
	val company: String,
	val client: String,
	val role: String,
	val activity: String,
	val km: Double?,
	val expensesCents: Long,
	val notes: String,
	val externalRefs: List<String>,
	val locality: String,
	val tags: List<String> = emptyList(),
	/* consumables left (issue #41), EUR cents; on the first day like the expenses */
	val materialsCents: Long = 0,
	/* an ABSENCE row (activity model 1.0): its time, apart from work, travel and pause, and its kind */
	val absence: Duration = Duration.ZERO,
	val absenceKind: String? = null,
)

/* Descriptive data of an entry for exports; km and expenses go on its first exported day. */
data class EntryMeta(
	val company: String,
	val client: String,
	val role: String,
	val activity: String,
	val notes: String,
	val locality: String,
	val externalRefs: List<String>,
	val km: Double?,
	val expensesCents: Long,
	/* normalised, without '#' (M15) */
	val tags: List<String> = emptyList(),
	val materialsCents: Long = 0,
	/* the kind of an ABSENCE activity (ABSENCE, HOLIDAY, LEAVE, SICK), else null */
	val absenceKind: String? = null,
)

/* Exporters compiled into the app, no dynamic loading (A14). */
interface Exporter {
	val id: String
	val mimeType: String
	val extension: String
	fun export(rows: List<ExportRow>, out: OutputStream)
}

object ExportRows {
	/* Rows ordered by start; entries missing from [meta] are skipped. */
	fun build(days: List<DayTotals>, meta: Map<Long, EntryMeta>, zone: ZoneId): List<ExportRow> {
		val firstDay = HashMap<Long, java.time.LocalDate>()
		for (d in days)
			for (l in d.lines)
				firstDay.merge(l.entryId, l.day) { a, b -> minOf(a, b) }
		/* the absences (activity model 1.0): a row each, their time apart from work, travel and pause */
		val absent = days.flatMap { it.absences }.mapNotNull { a ->
			val m = meta[a.entryId] ?: return@mapNotNull null
			(a.firstStart to a.entryId) to ExportRow(
				start = LocalDateTime.ofInstant(a.firstStart, zone), end = LocalDateTime.ofInstant(a.lastEnd, zone),
				work = Duration.ZERO, pause = Duration.ZERO, travel = Duration.ZERO, company = m.company, client = m.client, role = m.role,
				activity = m.activity, km = null, expensesCents = 0, notes = m.notes, externalRefs = m.externalRefs, locality = m.locality,
				tags = m.tags, absence = a.duration, absenceKind = m.absenceKind ?: "ABSENCE",
			)
		}
		val worked = days.flatMap { it.lines }
			.mapNotNull { l ->
				val m = meta[l.entryId] ?: return@mapNotNull null
				val first = firstDay[l.entryId] == l.day
				ExportRow(
					start = LocalDateTime.ofInstant(l.firstStart, zone),
					end = LocalDateTime.ofInstant(l.lastEnd, zone),
					work = l.roundedWork,
					pause = l.roundedPause,
					travel = l.roundedTravel,
					company = m.company,
					client = m.client,
					role = m.role,
					activity = m.activity,
					km = if (first) m.km else null,
					expensesCents = if (first) m.expensesCents else 0,
					notes = m.notes,
					externalRefs = m.externalRefs,
					locality = m.locality,
					tags = m.tags,
					materialsCents = if (first) m.materialsCents else 0,
				).let { (l.firstStart to l.entryId) to it }
			}
		return (worked + absent).sortedWith(compareBy({ it.first.first }, { it.first.second })).map { it.second }
	}
}
