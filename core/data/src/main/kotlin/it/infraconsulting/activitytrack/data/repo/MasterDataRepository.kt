/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import it.infraconsulting.activitytrack.domain.settings.Ordering
import it.infraconsulting.activitytrack.domain.settings.OrderedList
import it.infraconsulting.activitytrack.domain.settings.ListOrders
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityPresetEntity
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.ClientSiteEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.CompanySiteEntity
import it.infraconsulting.activitytrack.data.db.DayOffEntity
import it.infraconsulting.activitytrack.data.db.LunchBreakEntity
import it.infraconsulting.activitytrack.data.db.PlaceEntity
import it.infraconsulting.activitytrack.data.db.PlaceKind
import it.infraconsulting.activitytrack.data.db.RoleEntity
import it.infraconsulting.activitytrack.data.db.TagEntity
import it.infraconsulting.activitytrack.domain.tag.Tags
import it.infraconsulting.activitytrack.data.db.WorkScheduleEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.start.KnownSite
import java.time.LocalDate

/*
 * Master data with input validation. Names are trimmed; unique-index and
 * foreign-key violations surface as [ValidationException].
 */
/*
 * [orders]: the list orders chosen in Master data (M16); every list flow
 * below comes sorted by them, so the screens and the drop-downs agree.
 */
class MasterDataRepository(
	private val db: ActivityTrackDatabase,
	orders: Flow<ListOrders> = flowOf(ListOrders()),
	private val clock: Clock = Clock.systemUTC(),
) {
	private val dao = db.masterDataDao()
	private val entryDao = db.entryDao()
	private val order = orders.distinctUntilChanged()

	val companies: Flow<List<CompanyEntity>> =
		combine(dao.observeCompanies(), order.map { it.companies }.distinctUntilChanged(), entryDao.observeCompanyLastUse()) { list, o, uses ->
			val last = uses.associate { it.id to it.lastMs }
			Ordering.sort(list, o, { it.name }, { it.archived }, { last[it.id] }, { it.position }, { it.id })
		}
	val clients: Flow<List<ClientEntity>> =
		combine(dao.observeClients(), order.map { it.clients }.distinctUntilChanged(), entryDao.observeClientLastUse()) { list, o, uses ->
			val last = uses.associate { it.clientId to it.lastMs }
			Ordering.sort(list, o, { it.name }, { it.archived }, { last[it.id] }, { it.position }, { it.id })
		}
	val places = dao.observePlaces()
	val roles: Flow<List<RoleEntity>> =
		combine(dao.observeRoles(), order.map { it.roles }.distinctUntilChanged(), entryDao.observeRoleLastUse()) { list, o, uses ->
			val last = uses.associate { it.id to it.lastMs }
			Ordering.sort(list, o, { it.name }, { it.archived }, { last[it.id] }, { it.position }, { it.id })
		}
	/* tags of the master data (issue #133), by name; archived ones last */
	val tags: Flow<List<TagEntity>> = dao.observeTagList()

	val presets: Flow<List<ActivityPresetEntity>> =
		combine(dao.observePresets(), order.map { it.presets }.distinctUntilChanged()) { list, o ->
			Ordering.sort(list, o, { it.text }, lastUse = { it.lastUsedUtcMs }, position = { it.position }, id = { it.id })
		}

	/* Manual order (M16): [ids] in their new order get positions 0..n-1. */
	suspend fun reorder(list: OrderedList, ids: List<Long>) = db.withTransaction {
		ids.forEachIndexed { i, id ->
			when (list) {
				OrderedList.COMPANIES -> dao.setCompanyPosition(id, i)
				OrderedList.CLIENTS -> dao.setClientPosition(id, i)
				OrderedList.ROLES -> dao.setRolePosition(id, i)
				OrderedList.PRESETS -> dao.setPresetPosition(id, i)
			}
		}
	}

	/* A preset picked for an entry: its "last use" (M16). */
	suspend fun touchPreset(id: Long) = dao.touchPreset(id, clock.millis())
	val schedule = dao.observeSchedule()
	val lunchBreaks = dao.observeLunchBreaks()
	val daysOff = dao.observeDaysOff()

	suspend fun daysOffBetween(from: LocalDate, to: LocalDate): Set<LocalDate> =
		dao.daysOffBetween(from.toEpochDay(), to.toEpochDay()).map(LocalDate::ofEpochDay).toSet()

	suspend fun saveDayOff(date: LocalDate, note: String?) =
		dao.upsertDayOff(DayOffEntity(date.toEpochDay(), note?.trim()?.ifEmpty { null }))

	suspend fun deleteDayOff(d: DayOffEntity) = dao.deleteDayOff(d)

	suspend fun company(id: Long): CompanyEntity? = dao.company(id)
	suspend fun client(id: Long): ClientEntity? = dao.client(id)

	fun companySites(companyId: Long) = dao.observeCompanySites(companyId)
	fun clientSites(clientId: Long) = dao.observeClientSites(clientId)

	suspend fun saveCompany(c: CompanyEntity): Long {
		val v = c.copy(name = name(c.name))
		return guard { if (v.id == 0L) dao.insertCompany(v) else v.id.also { dao.updateCompany(v) } }
	}

	suspend fun deleteCompany(c: CompanyEntity) = guard { dao.deleteCompany(c) }

	suspend fun saveCompanySite(s: CompanySiteEntity): Long {
		val v = s.copy(label = name(s.label), address = s.address?.trim()?.ifEmpty { null })
		checkSite(v.lat, v.lon, v.radiusM)
		return guard { if (v.id == 0L) dao.insertCompanySite(v) else v.id.also { dao.updateCompanySite(v) } }
	}

	suspend fun deleteCompanySite(s: CompanySiteEntity) = guard { dao.deleteCompanySite(s) }

	/* A client's company is fixed once entries reference it (entry/client company invariant). */
	suspend fun saveClient(c: ClientEntity): Long = db.withTransaction {
		val v = c.copy(name = name(c.name))
		if (v.id != 0L) {
			val old = dao.client(v.id) ?: throw ValidationException(Reason.NOT_FOUND)
			if (old.companyId != v.companyId && dao.entriesOnClient(v.id) > 0)
				throw ValidationException(Reason.CLIENT_COMPANY_LOCKED)
		}
		guard { if (v.id == 0L) dao.insertClient(v) else v.id.also { dao.updateClient(v) } }
	}

	suspend fun deleteClient(c: ClientEntity) = guard { dao.deleteClient(c) }

	suspend fun saveClientSite(s: ClientSiteEntity): Long {
		val v = s.copy(label = name(s.label), address = s.address?.trim()?.ifEmpty { null })
		checkSite(v.lat, v.lon, v.radiusM)
		return guard { if (v.id == 0L) dao.insertClientSite(v) else v.id.also { dao.updateClientSite(v) } }
	}

	suspend fun deleteClientSite(s: ClientSiteEntity) = guard { dao.deleteClientSite(s) }

	suspend fun savePlace(p: PlaceEntity): Long {
		val v = p.copy(label = name(p.label))
		checkSite(v.lat, v.lon, v.radiusM)
		return guard { if (v.id == 0L) dao.insertPlace(v) else v.id.also { dao.updatePlace(v) } }
	}

	suspend fun deletePlace(p: PlaceEntity) = guard { dao.deletePlace(p) }

	suspend fun saveRole(r: RoleEntity): Long {
		val v = r.copy(name = name(r.name))
		return guard { if (v.id == 0L) dao.insertRole(v) else v.id.also { dao.updateRole(v) } }
	}

	suspend fun deleteRole(r: RoleEntity) = guard { dao.deleteRole(r) }

	/*
	 * A tag of the master data (issue #133): its name normalised as the
	 * entries' tags; renamed, the entries using it follow (owner, 2026-10-30).
	 */
	suspend fun saveTag(t: TagEntity): Long = db.withTransaction {
		val v = t.copy(name = Tags.normalize(t.name) ?: throw ValidationException(Reason.BLANK_NAME))
		val old = if (v.id == 0L) null else dao.allTags().firstOrNull { it.id == v.id }
		guard { if (v.id == 0L) dao.insertTag(v) else v.id.also { dao.updateTag(v) } }.also {
			if (old != null && old.name != v.name) {
				dao.copyEntryTags(old.name, v.name)
				dao.dropEntryTags(old.name)
			}
		}
	}

	/* the entries keep the name: it becomes a free tag */
	suspend fun deleteTag(t: TagEntity) = guard { dao.deleteTag(t) }

	suspend fun savePreset(p: ActivityPresetEntity): Long {
		val v = p.copy(text = name(p.text))
		return guard { if (v.id == 0L) dao.insertPreset(v) else v.id.also { dao.updatePreset(v) } }
	}

	suspend fun deletePreset(p: ActivityPresetEntity) = guard { dao.deletePreset(p) }

	/* Working-hour intervals of one day must not overlap. */
	suspend fun saveSchedule(s: WorkScheduleEntity): Long = db.withTransaction {
		checkDay(s.dayOfWeek)
		checkRange(s.startMin, s.endMin)
		val others = dao.scheduleOf(s.dayOfWeek).filter { it.id != s.id }
		if (others.any { s.startMin < it.endMin && it.startMin < s.endMin })
			throw ValidationException(Reason.OVERLAPPING_INTERVALS)
		if (s.id == 0L) dao.insertSchedule(s) else s.id.also { dao.updateSchedule(s) }
	}

	suspend fun deleteSchedule(s: WorkScheduleEntity) = dao.deleteSchedule(s)

	suspend fun setLunchBreak(dayOfWeek: Int, startMin: Int, endMin: Int) {
		checkDay(dayOfWeek)
		checkRange(startMin, endMin)
		db.withTransaction {
			dao.deleteLunchBreak(dayOfWeek)
			dao.insertLunchBreak(LunchBreakEntity(dayOfWeek = dayOfWeek, startMin = startMin, endMin = endMin))
		}
	}

	suspend fun clearLunchBreak(dayOfWeek: Int) = dao.deleteLunchBreak(dayOfWeek)

	/* All sites and home places, for start pre-fill and proximity proposals. */
	suspend fun knownSites(): List<KnownSite> {
		val clientCompany = HashMap<Long, Long>()
		val out = ArrayList<KnownSite>()
		dao.allCompanySites().forEach { out += KnownSite.Company(it.companyId, GeoPoint(it.lat, it.lon), it.radiusM) }
		dao.allClientSites().forEach {
			val companyId = clientCompany.getOrPut(it.clientId) { dao.client(it.clientId)?.companyId ?: return@forEach }
			out += KnownSite.Client(companyId, it.clientId, GeoPoint(it.lat, it.lon), it.radiusM)
		}
		dao.allPlaces().filter { it.kind == PlaceKind.HOME }
			.forEach { out += KnownSite.Home(GeoPoint(it.lat, it.lon), it.radiusM) }
		return out
	}

	private fun name(s: String): String =
		s.trim().ifEmpty { throw ValidationException(Reason.BLANK_NAME) }

	private fun checkSite(lat: Double, lon: Double, radiusM: Int) {
		if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0)
			throw ValidationException(Reason.INVALID_COORDINATES)
		if (radiusM !in 1..10_000)
			throw ValidationException(Reason.INVALID_RADIUS)
	}

	private fun checkDay(dayOfWeek: Int) = require(dayOfWeek in 1..7) { "day of week $dayOfWeek" }

	private fun checkRange(startMin: Int, endMin: Int) {
		if (startMin !in 0 until 1440 || endMin !in 1..1440 || startMin >= endMin)
			throw ValidationException(Reason.INVALID_TIME_RANGE)
	}

	/* Unique index -> duplicate name; foreign key RESTRICT -> row in use. */
	private suspend fun <T> guard(block: suspend () -> T): T =
		try {
			block()
		} catch (e: SQLiteConstraintException) {
			val msg = e.message.orEmpty()
			throw ValidationException(
				if (msg.contains("UNIQUE", ignoreCase = true)) Reason.DUPLICATE_NAME else Reason.IN_USE,
				msg,
			)
		}
}
