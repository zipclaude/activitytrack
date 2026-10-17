/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.interchange

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.domain.interchange.Interchange
import it.infraconsulting.activitytrack.domain.interchange.XAbsence
import it.infraconsulting.activitytrack.domain.interchange.XClient
import it.infraconsulting.activitytrack.domain.interchange.XCompany
import it.infraconsulting.activitytrack.domain.interchange.XDayOff
import it.infraconsulting.activitytrack.domain.interchange.XEntry
import it.infraconsulting.activitytrack.domain.interchange.XExpense
import it.infraconsulting.activitytrack.domain.interchange.XPreset
import it.infraconsulting.activitytrack.domain.interchange.XRole
import it.infraconsulting.activitytrack.domain.interchange.XSegment
import it.infraconsulting.activitytrack.domain.interchange.XSite
import it.infraconsulting.activitytrack.domain.interchange.XTodo
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ExportSection { MASTER_DATA, TODOS, ENTRIES, CALENDAR }

/*
 * What to export (M4): sections, an optional period (entries touching it,
 * absences and days off in it), an optional company and client.
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
)

/* Builds an interchange package from the database; location samples never leave. */
class Exporter(private val db: ActivityTrackDatabase) {
	private val master = db.masterDataDao()
	private val entries = db.entryDao()
	private val todos = db.todoDao()
	private val bulk = db.interchangeDao()

	suspend fun build(f: ExportFilter, generator: String, now: Instant = Instant.now()): Interchange {
		val companies = master.allCompanies().filter { f.companyId == null || it.id == f.companyId }
		val companyIds = companies.map { it.id }.toSet()
		val allClients = master.allClients()
		val clients = allClients.filter { it.companyId in companyIds && (f.clientId == null || it.id == f.clientId) }
		val clientIds = clients.map { it.id }.toSet()
		val companyKey = master.allCompanies().associate { it.id to key(it) }
		val clientKey = allClients.associate { it.id to key(it) }
		val todoRows = todos.all()
		val todoKey = todoRows.associate { it.id to key(it) }
		val roles = master.allRoles().associateBy { it.id }

		var out = Interchange(generator = generator, createdUtc = now.toString())

		if (ExportSection.MASTER_DATA in f.sections) {
			out = out.copy(
				companies = companies.map { XCompany(key(it), it.name, it.archived) },
				clients = clients.map { XClient(key(it), companyKey.getValue(it.companyId), it.name, it.archived) },
				sites = master.allCompanySites().filter { it.companyId in companyIds && f.clientId == null }.map {
					XSite("company", companyKey.getValue(it.companyId), it.label, it.address,
						it.lat.takeIf { f.siteCoordinates }, it.lon.takeIf { f.siteCoordinates }, it.radiusM)
				} + master.allClientSites().filter { it.clientId in clientIds }.map {
					XSite("client", clientKey.getValue(it.clientId), it.label, it.address,
						it.lat.takeIf { f.siteCoordinates }, it.lon.takeIf { f.siteCoordinates }, it.radiusM,
						companyKey = allClients.first { c -> c.id == it.clientId }.companyId.let(companyKey::getValue))
				},
				roles = roles.values.map { XRole(it.name, it.archived) },
				presets = bulk.allPresets().filter { it.companyId == null || it.companyId in companyIds }.map {
					XPreset(it.text, it.companyId?.let(companyKey::getValue), it.defaultRoleId?.let { r -> roles[r]?.name })
				},
			)
		}

		if (ExportSection.TODOS in f.sections) {
			out = out.copy(
				todos = todoRows.filter { it.companyId in companyIds && (f.clientId == null || it.clientId == f.clientId) }.map {
					XTodo(
						key = key(it), companyKey = companyKey.getValue(it.companyId), clientKey = it.clientId?.let(clientKey::getValue),
						title = it.title, notes = it.notes, refs = it.refs.lines().filter(String::isNotBlank),
						dueDate = it.dueEpochDay?.let { d -> LocalDate.ofEpochDay(d).toString() },
						dueTime = it.dueTimeMin?.let { m -> "%02d:%02d".format(m / 60, m % 60) },
						priority = it.priority, done = it.done,
					)
				},
			)
		}

		if (ExportSection.ENTRIES in f.sections) {
			val fromMs = f.from?.atStartOfDay(f.zone)?.toInstant()?.toEpochMilli()
			val toMs = f.to?.plusDays(1)?.atStartOfDay(f.zone)?.toInstant()?.toEpochMilli()
			val candidates = bulk.allEntries().filter {
				(f.companyId == null || it.companyId == f.companyId) && (f.clientId == null || it.clientId == f.clientId)
			}
			val ids = candidates.map { it.id }
			val segments = if (ids.isEmpty()) emptyMap() else bulk.segmentsOf(ids).groupBy { it.entryId }
			val expenses = if (ids.isEmpty()) emptyMap() else bulk.expensesOf(ids).groupBy { it.entryId }
			val refs = if (ids.isEmpty()) emptyMap() else entries.externalRefs(ids).groupBy { it.entryId }
			out = out.copy(
				entries = candidates.filter { e ->
					val segs = segments[e.id].orEmpty()
					/* touching the period: an open segment counts as "until now" */
					(fromMs == null || segs.any { (it.endUtcMs ?: now.toEpochMilli()) > fromMs }) &&
						(toMs == null || segs.any { it.startUtcMs < toMs })
				}.map { e ->
					XEntry(
						key = e.uid ?: "entry-${e.id}",
						companyKey = e.companyId?.let(companyKey::getValue),
						clientKey = e.clientId?.let(clientKey::getValue),
						todoKey = e.todoId?.let(todoKey::getValue),
						role = e.roleId?.let { roles[it]?.name },
						activity = e.activity,
						status = e.status.name,
						locality = e.locality,
						locationKind = e.locationKind?.name,
						notes = e.notes,
						zone = e.zoneId,
						segments = segments[e.id].orEmpty().map {
							XSegment(it.kind.name, Instant.ofEpochMilli(it.startUtcMs).toString(), it.endUtcMs?.let { m -> Instant.ofEpochMilli(m).toString() },
								it.source.name, it.kmEstimated, it.kmDeclared)
						},
						expenses = expenses[e.id].orEmpty().map {
							XExpense(BigDecimal.valueOf(it.amountCents, 2).toPlainString(), it.description,
								Instant.ofEpochMilli(it.createdUtcMs).toString(), receipt = it.attachmentId != null)
						},
						refs = refs[e.id].orEmpty().map { it.value },
					)
				},
			)
		}

		if (ExportSection.CALENDAR in f.sections) {
			val inPeriod = { d: Long -> (f.from == null || d >= f.from.toEpochDay()) && (f.to == null || d <= f.to.toEpochDay()) }
			out = out.copy(
				absences = bulk.allAbsences().filter { inPeriod(it.dateEpochDay) }.map {
					XAbsence(LocalDate.ofEpochDay(it.dateEpochDay).toString(), it.kind.name, it.timeMin?.let { m -> "%02d:%02d".format(m / 60, m % 60) })
				},
				daysOff = bulk.allDaysOff().filter { inPeriod(it.dateEpochDay) }.map { XDayOff(LocalDate.ofEpochDay(it.dateEpochDay).toString(), it.note) },
			)
		}
		return out
	}

	private fun key(c: CompanyEntity) = c.externalCode ?: c.name
	private fun key(c: ClientEntity) = c.externalCode ?: c.name
	private fun key(t: TodoEntity) = t.externalKey ?: t.uid
}
