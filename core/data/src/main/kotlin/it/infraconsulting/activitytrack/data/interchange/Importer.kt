/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.interchange

import it.infraconsulting.activitytrack.data.db.EntryMaterialEntity
import it.infraconsulting.activitytrack.data.db.EntryTagEntity
import it.infraconsulting.activitytrack.domain.tag.Tags
import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityPresetEntity
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.ClientSiteEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.CompanySiteEntity
import it.infraconsulting.activitytrack.data.db.DayOffEntity
import it.infraconsulting.activitytrack.data.db.ExpenseEntity
import it.infraconsulting.activitytrack.data.db.ExternalRefEntity
import it.infraconsulting.activitytrack.data.db.RoleEntity
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.Priority
import it.infraconsulting.activitytrack.domain.interchange.Interchange
import it.infraconsulting.activitytrack.domain.interchange.InterchangeUpgrade
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.interchange.XClient
import it.infraconsulting.activitytrack.domain.interchange.XCompany
import it.infraconsulting.activitytrack.domain.interchange.XEntry
import it.infraconsulting.activitytrack.domain.interchange.XSite
import it.infraconsulting.activitytrack.domain.interchange.XColor
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import it.infraconsulting.activitytrack.domain.model.EntryInvariants
import it.infraconsulting.activitytrack.domain.model.ExternalRefClassifier
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentSource
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/* What an import did, per section ("clients", "entries"...). Never blocking. */
data class ImportReport(
	val inserted: Map<String, Int>,
	val unchanged: Map<String, Int>,
	/* the record exists and differs: the app data was kept (owner decision 2026-10-17) */
	val conflicts: List<ImportNote>,
	/* the record could not be imported (unknown reference, invalid value) */
	val skipped: List<ImportNote>,
) {
	val total get() = inserted.values.sum()
}

data class ImportNote(val section: String, val key: String, val detail: String)

/*
 * Applies an interchange package to the database (M4): idempotent, matched by
 * external code / uid / name, never overwriting app data. One transaction:
 * either the package is applied as reported, or nothing changes on failure.
 * A version 1 file goes through the migration function first
 * (InterchangeUpgrade, ACTIVITY-MODEL.md 6.2): its to-dos and absences
 * arrive as activities, reported under "entries".
 */
class Importer(private val db: ActivityTrackDatabase, private val clock: Clock) {
	private val master = db.masterDataDao()
	private val entries = db.entryDao()
	private val bulk = db.interchangeDao()

	private class Run {
		val inserted = HashMap<String, Int>()
		val unchanged = HashMap<String, Int>()
		val conflicts = ArrayList<ImportNote>()
		val skipped = ArrayList<ImportNote>()
		val companies = HashMap<String, Long>()
		/* company null: clients of no company (issue #48) */
		val clients = HashMap<Pair<Long?, String>, Long>()
		val clientCompany = HashMap<Long, Long?>()

		fun added(section: String) {
			inserted.merge(section, 1, Int::plus)
		}

		fun same(section: String) {
			unchanged.merge(section, 1, Int::plus)
		}

		fun conflict(section: String, key: String, detail: String) {
			conflicts.add(ImportNote(section, key, detail))
		}

		fun skip(section: String, key: String, detail: String) {
			skipped.add(ImportNote(section, key, detail))
		}
	}

	suspend fun import(file: Interchange): ImportReport = db.withTransaction {
		val r = Run()
		val upgraded = InterchangeUpgrade.toV2(
			file, clock.zone,
			schedule = (1..7).flatMap { d -> master.scheduleOf(d).map { DayWindow(it.dayOfWeek, it.startMin, it.endMin) } },
			lunchBreaks = master.observeLunchBreaks().first().map { DayWindow(it.dayOfWeek, it.startMin, it.endMin) },
			daysOff = bulk.allDaysOff().map { LocalDate.ofEpochDay(it.dateEpochDay) }.toSet(),
			now = clock.instant(),
		)
		upgraded.skipped.forEach { (key, why) -> r.skip("todos", key, why) }
		val data = upgraded.data
		data.companies.forEach { company(r, it) }
		data.clients.forEach { client(r, it) }
		data.sites.forEach { site(r, it) }
		data.roles.forEach { x ->
			val existing = master.allRoles().firstOrNull { it.name.equals(x.name.trim(), ignoreCase = true) }
			when {
				existing == null -> { master.insertRole(RoleEntity(name = x.name.trim(), archived = x.archived)); r.added("roles") }
				existing.archived != x.archived -> r.conflict("roles", x.name, "archived")
				else -> r.same("roles")
			}
		}
		data.presets.forEach { x ->
			val companyId = x.companyKey?.let { companyId(r, it) ?: return@forEach r.skip("presets", x.text, "unknown company ${x.companyKey}") }
			val roleId = x.roleName?.let { role(it) }
			val existing = bulk.allPresets().firstOrNull { it.text == x.text.trim() && it.companyId == companyId }
			if (existing == null) {
				master.insertPreset(ActivityPresetEntity(text = x.text.trim(), defaultRoleId = roleId, companyId = companyId))
				r.added("presets")
			} else if (existing.defaultRoleId != roleId)
				r.conflict("presets", x.text, "role")
			else
				r.same("presets")
		}
		data.entries.forEach { entry(r, it) }
		data.daysOff.forEach { x ->
			val day = runCatching { LocalDate.parse(x.date).toEpochDay() }.getOrNull() ?: return@forEach r.skip("daysOff", x.date, "date")
			val existing = bulk.allDaysOff().firstOrNull { it.dateEpochDay == day }
			when {
				existing == null -> { master.upsertDayOff(DayOffEntity(day, x.note?.trim()?.ifEmpty { null })); r.added("daysOff") }
				existing.note != x.note?.trim()?.ifEmpty { null } -> r.conflict("daysOff", x.date, "note")
				else -> r.same("daysOff")
			}
		}
		ImportReport(r.inserted, r.unchanged, r.conflicts, r.skipped)
	}

	/* Company by external code, else by name; the key is the code unless it is just the name. */
	private suspend fun company(r: Run, x: XCompany) {
		val name = x.name.trim()
		val all = master.allCompanies()
		val existing = all.firstOrNull { it.externalCode == x.key }
			?: all.firstOrNull { it.name.equals(name, ignoreCase = true) || it.name.equals(x.key, ignoreCase = true) }
		if (existing == null) {
			val code = x.key.takeIf { it != name }
			r.companies[x.key] = master.insertCompany(CompanyEntity(name = name, archived = x.archived, externalCode = code, color = XColor.parse(x.color)))
			r.added("companies")
			return
		}
		r.companies[x.key] = existing.id
		val diff = listOfNotNull(
			"name".takeIf { existing.name != name },
			"archived".takeIf { existing.archived != x.archived },
			"color".takeIf { x.color != null && existing.color != XColor.parse(x.color) },
		)
		if (diff.isEmpty()) r.same("companies") else r.conflict("companies", x.key, diff.joinToString())
	}

	private suspend fun companyId(r: Run, key: String): Long? =
		r.companies[key] ?: master.allCompanies().let { all ->
			(all.firstOrNull { it.externalCode == key } ?: all.firstOrNull { it.name.equals(key, ignoreCase = true) })?.id
		}?.also { r.companies[key] = it }

	private suspend fun client(r: Run, x: XClient) {
		val companyId = x.companyKey?.let { companyId(r, it) ?: return r.skip("clients", x.key, "unknown company $it") }
		val name = x.name.trim()
		val all = master.allClients().filter { it.companyId == companyId }
		val existing = all.firstOrNull { it.externalCode == x.key }
			?: all.firstOrNull { it.name.equals(name, ignoreCase = true) || it.name.equals(x.key, ignoreCase = true) }
		if (existing == null) {
			val id = master.insertClient(ClientEntity(companyId = companyId, name = name, archived = x.archived, externalCode = x.key.takeIf { it != name }, color = XColor.parse(x.color)))
			r.clients[companyId to x.key] = id
			r.clientCompany[id] = companyId
			r.added("clients")
			return
		}
		r.clients[companyId to x.key] = existing.id
		r.clientCompany[existing.id] = companyId
		val diff = listOfNotNull(
			"name".takeIf { existing.name != name },
			"archived".takeIf { existing.archived != x.archived },
			"color".takeIf { x.color != null && existing.color != XColor.parse(x.color) },
		)
		if (diff.isEmpty()) r.same("clients") else r.conflict("clients", x.key, diff.joinToString())
	}

	/* A client key is unique within its company; without a company the key must match one client only. */
	private suspend fun clientId(r: Run, companyId: Long?, key: String): Long? {
		if (companyId != null)
			r.clients[companyId to key]?.let { return it }
		val all = master.allClients().filter { companyId == null || it.companyId == companyId }
		val matches = all.filter { it.externalCode == key }.ifEmpty { all.filter { it.name.equals(key, ignoreCase = true) } }
		return matches.singleOrNull()?.id
	}

	private suspend fun site(r: Run, x: XSite) {
		val label = x.label.trim()
		val lat = x.lat
		val lon = x.lon
		when (x.ownerKind) {
			"company" -> {
				val companyId = companyId(r, x.ownerKey) ?: return r.skip("sites", label, "unknown company ${x.ownerKey}")
				val existing = master.allCompanySites().firstOrNull { it.companyId == companyId && it.label == label }
				when {
					existing != null -> if (existing.address != x.address) r.conflict("sites", label, "address") else r.same("sites")
					lat == null || lon == null -> r.skip("sites", label, "no coordinates")
					else -> {
						master.insertCompanySite(CompanySiteEntity(companyId = companyId, label = label, lat = lat, lon = lon, radiusM = x.radiusM ?: 100, address = x.address))
						r.added("sites")
					}
				}
			}
			"client" -> {
				val companyId = x.companyKey?.let { companyId(r, it) }
				val clientId = clientId(r, companyId, x.ownerKey) ?: return r.skip("sites", label, "unknown client ${x.ownerKey}")
				val existing = master.allClientSites().firstOrNull { it.clientId == clientId && it.label == label }
				when {
					existing != null -> if (existing.address != x.address) r.conflict("sites", label, "address") else r.same("sites")
					lat == null || lon == null -> r.skip("sites", label, "no coordinates")
					else -> {
						master.insertClientSite(ClientSiteEntity(clientId = clientId, label = label, lat = lat, lon = lon, radiusM = x.radiusM ?: 100, address = x.address))
						r.added("sites")
					}
				}
			}
			else -> r.skip("sites", label, "owner kind ${x.ownerKind}")
		}
	}

	private suspend fun role(name: String): Long =
		master.allRoles().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id
			?: master.insertRole(RoleEntity(name = name.trim()))

	/*
	 * An activity (version 2): matched by its uid (the key as a canonical
	 * UUID), else an imported TODO by (company, external key); an existing
	 * one is compared, never overwritten. A running one is skipped (it would
	 * run on two devices).
	 */
	private suspend fun entry(r: Run, x: XEntry) {
		val companyId = x.companyKey?.let { companyId(r, it) ?: return r.skip("entries", x.key, "unknown company $it") }
		val clientId = x.clientKey?.let { clientId(r, companyId, it) ?: return r.skip("entries", x.key, "unknown client $it") }
		val status = runCatching { ActivityStatus.valueOf(x.status) }.getOrNull() ?: return r.skip("entries", x.key, "status ${x.status}")
		val kind = runCatching { ActivityKind.valueOf(x.kind) }.getOrNull() ?: return r.skip("entries", x.key, "kind ${x.kind}")
		val due = x.dueDate?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() ?: return r.skip("entries", x.key, "due date $it") }
		val dueTime = x.dueTime?.let { minutes(it) ?: return r.skip("entries", x.key, "due time $it") }
		val planDay = x.plannedDate?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() ?: return r.skip("entries", x.key, "planned date $it") }
		val planTime = x.plannedTime?.let { minutes(it) ?: return r.skip("entries", x.key, "planned time $it") }
		/* a planned time needs its date; a date alone plans the whole day (issue #52) */
		if (planDay == null && planTime != null)
			return r.skip("entries", x.key, "planned time without date")
		val duration = x.durationMin?.takeIf { it in 1..TodoPlan.MAX_DURATION_MIN && planTime != null }
			?: x.durationMin?.let { return r.skip("entries", x.key, "duration $it") }
		val existing = bulk.entryByUid(SyncIds.canonical(x.key))
			?: x.externalKey?.let { entries.byExternalKey(clientId?.let { c -> master.client(c)?.companyId } ?: companyId, it) }
		if (existing != null) {
			val diff = listOfNotNull(
				"status".takeIf { existing.status != status },
				"activity".takeIf { existing.activity != x.activity?.trim()?.ifEmpty { null } },
				"segments".takeIf { entries.segments(existing.id).size != x.segments.size },
				"due".takeIf { existing.dueEpochDay != due || existing.dueTimeMin != dueTime },
				"plan".takeIf { existing.planEpochDay != planDay || existing.planStartMin != planTime || existing.durationMin != duration },
				"priority".takeIf { existing.priority != Priority.of(x.priority).value },
				"deleted".takeIf { (existing.deletedUtcMs != null) != (x.deletedUtc != null) },
			)
			if (diff.isEmpty()) r.same("entries") else r.conflict("entries", x.key, diff.joinToString())
			return
		}
		/* another device's running activity would run twice */
		if (status == ActivityStatus.RUNNING || x.segments.any { it.endUtc == null })
			return r.skip("entries", x.key, "running entry")
		if (kind == ActivityKind.ABSENCE && status != ActivityStatus.CLOSED)
			return r.skip("entries", x.key, "absence not closed")
		val zone = runCatching { ZoneId.of(x.zone) }.getOrNull() ?: return r.skip("entries", x.key, "zone ${x.zone}")
		val segs = x.segments.map { s ->
			val sk = runCatching { SegmentKind.valueOf(s.kind) }.getOrNull() ?: return r.skip("entries", x.key, "segment kind ${s.kind}")
			val start = runCatching { Instant.parse(s.startUtc) }.getOrNull() ?: return r.skip("entries", x.key, "segment start ${s.startUtc}")
			val end = runCatching { Instant.parse(s.endUtc!!) }.getOrNull() ?: return r.skip("entries", x.key, "segment end ${s.endUtc}")
			if ((sk == SegmentKind.ABSENCE) != (kind == ActivityKind.ABSENCE))
				return r.skip("entries", x.key, "segment kind ${s.kind} in a $kind")
			val source = runCatching { SegmentSource.valueOf(s.source) }.getOrDefault(SegmentSource.TRACKED)
			SegmentEntity(
				entryId = 0, kind = sk, startUtcMs = start.toEpochMilli(), endUtcMs = end.toEpochMilli(), zoneId = zone.id, source = source,
				kmEstimated = s.kmEstimated, kmDeclared = s.kmDeclared, planned = s.planned,
			)
		}
		val violations = EntryInvariants.violations(status, null, segs.map { Segment(it.kind, Instant.ofEpochMilli(it.startUtcMs), it.endUtcMs?.let(Instant::ofEpochMilli), it.planned) })
		if (violations.isNotEmpty())
			return r.skip("entries", x.key, violations.joinToString())
		val clientCompany = clientId?.let { master.client(it)?.companyId }
		val now = clock.millis()
		fun instant(s: String?) = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
		val real = segs.filter { !it.planned }
		val id = entries.insertEntry(
			TimeEntryEntity(
				uid = SyncIds.canonical(x.key), companyId = clientCompany ?: companyId, clientId = clientId,
				roleId = x.role?.let { role(it) }, activity = x.activity?.trim()?.ifEmpty { null }, status = status, kind = kind,
				locality = x.locality, locationKind = x.locationKind?.let { runCatching { LocationKind.valueOf(it) }.getOrNull() },
				notes = x.notes, zoneId = zone.id, createdUtcMs = real.minOfOrNull { it.startUtcMs } ?: now, updatedUtcMs = now,
				deletedUtcMs = instant(x.deletedUtc),
				closedUtcMs = if (status == ActivityStatus.CLOSED) instant(x.closedUtc) ?: real.mapNotNull { it.endUtcMs }.maxOrNull() ?: now else null,
				externalKey = x.externalKey?.trim()?.ifEmpty { null }, dueEpochDay = due, dueTimeMin = dueTime,
				planEpochDay = planDay, planStartMin = planTime, durationMin = duration, priority = Priority.of(x.priority).value,
				absenceKind = if (kind == ActivityKind.ABSENCE) AbsenceKind.of(x.absenceKind) ?: AbsenceKind.ABSENCE else null,
			),
		)
		segs.forEach { entries.insertSegment(it.copy(entryId = id)) }
		x.expenses.forEach { e ->
			val cents = runCatching { BigDecimal(e.amount).movePointRight(2).longValueExact() }.getOrNull()
			if (cents == null || cents <= 0)
				r.skip("expenses", x.key, "amount ${e.amount}")
			else
				entries.insertExpense(
					ExpenseEntity(entryId = id, amountCents = cents, description = e.description,
						createdUtcMs = runCatching { Instant.parse(e.createdUtc).toEpochMilli() }.getOrDefault(now)),
				)
		}
		/* issue #41: description required; a malformed number is left out, the line kept */
		x.materials.filter { it.description.isNotBlank() }.forEach { m ->
			db.materialDao().insertLine(
				EntryMaterialEntity(
					entryId = id, description = m.description.trim(),
					quantity = m.quantity?.let { runCatching { BigDecimal(it).toDouble() }.getOrNull() }?.takeIf { it > 0 },
					unit = m.unit?.trim()?.ifEmpty { null },
					unitCostCents = m.unitCost?.let { runCatching { BigDecimal(it).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() }
						?.takeIf { it >= 0 },
					createdUtcMs = now,
				),
			)
		}
		x.refs.map(String::trim).filter(String::isNotEmpty).distinct().forEach {
			entries.insertExternalRef(ExternalRefEntity(entryId = id, kind = ExternalRefClassifier.classify(it), value = it))
		}
		entries.insertTags(x.tags.mapNotNull(Tags::normalize).distinct().map { EntryTagEntity(id, it) })
		r.added("entries")
	}

	private fun minutes(hhmm: String): Int? =
		runCatching { LocalTime.parse(hhmm).let { it.hour * 60 + it.minute } }.getOrNull()
}
