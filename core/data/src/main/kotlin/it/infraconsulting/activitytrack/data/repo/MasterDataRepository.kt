/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityPresetEntity
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.ClientSiteEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.CompanySiteEntity
import it.infraconsulting.activitytrack.data.db.LunchBreakEntity
import it.infraconsulting.activitytrack.data.db.PlaceEntity
import it.infraconsulting.activitytrack.data.db.RoleEntity
import it.infraconsulting.activitytrack.data.db.WorkScheduleEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.start.KnownSite
import it.infraconsulting.activitytrack.data.db.PlaceKind

/*
 * Master data with input validation. Names are trimmed; unique-index and
 * foreign-key violations surface as [ValidationException].
 */
class MasterDataRepository(private val db: ActivityTrackDatabase) {
	private val dao = db.masterDataDao()

	val companies = dao.observeCompanies()
	val clients = dao.observeClients()
	val places = dao.observePlaces()
	val roles = dao.observeRoles()
	val presets = dao.observePresets()
	val schedule = dao.observeSchedule()
	val lunchBreaks = dao.observeLunchBreaks()

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
