/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.interchange

import it.infraconsulting.activitytrack.domain.interchange.XMaterial
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.interchange.Interchange
import it.infraconsulting.activitytrack.domain.interchange.XClient
import it.infraconsulting.activitytrack.domain.interchange.XCompany
import it.infraconsulting.activitytrack.domain.interchange.XDayOff
import it.infraconsulting.activitytrack.domain.interchange.XEntry
import it.infraconsulting.activitytrack.domain.interchange.XExpense
import it.infraconsulting.activitytrack.domain.interchange.XPreset
import it.infraconsulting.activitytrack.domain.interchange.XRole
import it.infraconsulting.activitytrack.domain.interchange.XSegment
import it.infraconsulting.activitytrack.domain.interchange.XSite
import it.infraconsulting.activitytrack.domain.interchange.XColor
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* TODOS: the TODO activities; ENTRIES: the others (absences included); CALENDAR: the days off */
enum class ExportSection { MASTER_DATA, TODOS, ENTRIES, CALENDAR }

/*
 * What to export (M4): sections, an optional period (activities touching
 * it, days off in it), an optional company and client. Deleted activities
 * stay in the bin; the format is version 2 (ACTIVITY-MODEL.md 6.2).
 */
data class ExportFilter(
	val sections: Set<ExportSection> = ExportSection.entries.toSet(),
	val from: LocalDate? = null,
	val to: LocalDate? = null,
	val companyId: Long? = null,
	val clientId: Long? = null,
	/* site coordinates are master data, still off unless asked (SPEC §6) */
	val siteCoordinates: Boolean = false,
	val zone: ZoneId = ZoneId.systemDefault(),
	/* only entries with this tag (M19); null = all */
	val tag: String? = null,
)

/* Builds an interchange package from the database; location samples never leave. */
class Exporter(private val db: ActivityTrackDatabase) {
	private val master = db.masterDataDao()
	private val entries = db.entryDao()
	private val bulk = db.interchangeDao()

	suspend fun build(f: ExportFilter, generator: String, now: Instant = Instant.now()): Interchange {
		val companies = master.allCompanies().filter { f.companyId == null || it.id == f.companyId }
		val companyIds = companies.map { it.id }.toSet()
		val allClients = master.allClients()
		/* clients of no company (issue #48) belong to every unfiltered export */
		val inScope = { companyId: Long? -> if (companyId == null) f.companyId == null else companyId in companyIds }
		val clients = allClients.filter { inScope(it.companyId) && (f.clientId == null || it.id == f.clientId) }
		val clientIds = clients.map { it.id }.toSet()
		val companyKey = master.allCompanies().associate { it.id to key(it) }
		val clientKey = allClients.associate { it.id to key(it) }
		val roles = master.allRoles().associateBy { it.id }

		var out = Interchange(generator = generator, createdUtc = now.toString())

		if (ExportSection.MASTER_DATA in f.sections) {
			out = out.copy(
				companies = companies.map { XCompany(key(it), it.name, it.archived, XColor.format(it.color)) },
				clients = clients.map { XClient(key(it), it.companyId?.let(companyKey::getValue), it.name, it.archived, XColor.format(it.color)) },
				sites = master.allCompanySites().filter { it.companyId in companyIds && f.clientId == null }.map {
					XSite("company", companyKey.getValue(it.companyId), it.label, it.address,
						it.lat.takeIf { f.siteCoordinates }, it.lon.takeIf { f.siteCoordinates }, it.radiusM)
				} + master.allClientSites().filter { it.clientId in clientIds }.map {
					XSite("client", clientKey.getValue(it.clientId), it.label, it.address,
						it.lat.takeIf { f.siteCoordinates }, it.lon.takeIf { f.siteCoordinates }, it.radiusM,
						companyKey = allClients.first { c -> c.id == it.clientId }.companyId?.let(companyKey::getValue))
				},
				roles = roles.values.map { XRole(it.name, it.archived) },
				presets = bulk.allPresets().filter { it.companyId == null || it.companyId in companyIds }.map {
					XPreset(it.text, it.companyId?.let(companyKey::getValue), it.defaultRoleId?.let { r -> roles[r]?.name })
				},
			)
		}

		if (ExportSection.ENTRIES in f.sections || ExportSection.TODOS in f.sections) {
			val fromMs = f.from?.atStartOfDay(f.zone)?.toInstant()?.toEpochMilli()
			val toMs = f.to?.plusDays(1)?.atStartOfDay(f.zone)?.toInstant()?.toEpochMilli()
			val candidates = bulk.allEntries().filter {
				it.deletedUtcMs == null && (f.companyId == null || it.companyId == f.companyId) && (f.clientId == null || it.clientId == f.clientId) &&
					(if (it.status == ActivityStatus.TODO) ExportSection.TODOS in f.sections else ExportSection.ENTRIES in f.sections)
			}
			val ids = candidates.map { it.id }
			val segments = if (ids.isEmpty()) emptyMap() else bulk.segmentsOf(ids).groupBy { it.entryId }
			val expenses = if (ids.isEmpty()) emptyMap() else bulk.expensesOf(ids).groupBy { it.entryId }
			val materials = if (ids.isEmpty()) emptyMap() else db.materialDao().linesOf(ids).groupBy { it.entryId }
			val refs = if (ids.isEmpty()) emptyMap() else entries.externalRefs(ids).groupBy { it.entryId }
			val tags = if (ids.isEmpty()) emptyMap() else entries.tagsOf(ids).groupBy({ it.entryId }, { it.tag })
			out = out.copy(
				entries = candidates.filter { e ->
					val segs = segments[e.id].orEmpty().filter { !it.planned }
					(f.tag == null || f.tag in tags[e.id].orEmpty()) &&
					/* a TODO activity has no time of its own: not filtered by the period */
					(e.status == ActivityStatus.TODO || (
					/* touching the period: an open segment counts as "until now" */
					(fromMs == null || segs.any { (it.endUtcMs ?: now.toEpochMilli()) > fromMs }) &&
						(toMs == null || segs.any { it.startUtcMs < toMs })))
				}.map { e ->
					XEntry(
						key = e.uid ?: "entry-${e.id}",
						companyKey = e.companyId?.let(companyKey::getValue),
						clientKey = e.clientId?.let(clientKey::getValue),
						role = e.roleId?.let { roles[it]?.name },
						activity = e.activity,
						status = e.status.name,
						kind = e.kind.name,
						closedUtc = e.closedUtcMs?.let { Instant.ofEpochMilli(it).toString() },
						externalKey = e.externalKey,
						dueDate = e.dueEpochDay?.let { d -> LocalDate.ofEpochDay(d).toString() },
						dueTime = e.dueTimeMin?.let { m -> "%02d:%02d".format(m / 60, m % 60) },
						plannedDate = e.planEpochDay?.let { d -> LocalDate.ofEpochDay(d).toString() },
						plannedTime = e.planStartMin?.let { m -> "%02d:%02d".format(m / 60, m % 60) },
						durationMin = e.durationMin,
						priority = e.priority,
						absenceKind = e.absenceKind?.name,
						locality = e.locality,
						locationKind = e.locationKind?.name,
						notes = e.notes,
						zone = e.zoneId,
						segments = segments[e.id].orEmpty().map {
							XSegment(it.kind.name, Instant.ofEpochMilli(it.startUtcMs).toString(), it.endUtcMs?.let { m -> Instant.ofEpochMilli(m).toString() },
								it.source.name, it.kmEstimated, it.kmDeclared, planned = it.planned)
						},
						expenses = expenses[e.id].orEmpty().map {
							XExpense(BigDecimal.valueOf(it.amountCents, 2).toPlainString(), it.description,
								Instant.ofEpochMilli(it.createdUtcMs).toString(), receipt = it.attachmentId != null)
						},
						refs = refs[e.id].orEmpty().map { it.value },
						tags = tags[e.id].orEmpty().sorted(),
						materials = materials[e.id].orEmpty().map { m ->
							XMaterial(
								m.description, m.quantity?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }, m.unit,
								m.unitCostCents?.let { BigDecimal.valueOf(it, 2).toPlainString() },
							)
						},
					)
				},
			)
		}

		if (ExportSection.CALENDAR in f.sections) {
			val inPeriod = { d: Long -> (f.from == null || d >= f.from.toEpochDay()) && (f.to == null || d <= f.to.toEpochDay()) }
			out = out.copy(
				daysOff = bulk.allDaysOff().filter { inPeriod(it.dateEpochDay) }.map { XDayOff(LocalDate.ofEpochDay(it.dateEpochDay).toString(), it.note) },
			)
		}
		return out
	}

	private fun key(c: CompanyEntity) = c.externalCode ?: c.name
	private fun key(c: ClientEntity) = c.externalCode ?: c.name
}
