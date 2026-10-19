/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/* Companies, clients, sites, places, roles, presets, schedules. */
@Dao
interface MasterDataDao {
	/* name lookups for reports */
	@Query("SELECT * FROM company")
	suspend fun allCompanies(): List<CompanyEntity>

	@Query("SELECT * FROM client")
	suspend fun allClients(): List<ClientEntity>

	@Query("SELECT * FROM role")
	suspend fun allRoles(): List<RoleEntity>

	/* manual order and last use (M16) */
	@Query("UPDATE company SET sort_position = :position WHERE id = :id")
	suspend fun setCompanyPosition(id: Long, position: Int)

	@Query("UPDATE client SET sort_position = :position WHERE id = :id")
	suspend fun setClientPosition(id: Long, position: Int)

	@Query("UPDATE role SET sort_position = :position WHERE id = :id")
	suspend fun setRolePosition(id: Long, position: Int)

	@Query("UPDATE activity_preset SET sort_position = :position WHERE id = :id")
	suspend fun setPresetPosition(id: Long, position: Int)

	@Query("UPDATE activity_preset SET last_used_utc_ms = :utcMs WHERE id = :id")
	suspend fun touchPreset(id: Long, utcMs: Long)

	/* companies */
	@Query("SELECT * FROM company ORDER BY archived, name COLLATE NOCASE")
	fun observeCompanies(): Flow<List<CompanyEntity>>

	@Query("SELECT * FROM company WHERE id = :id")
	suspend fun company(id: Long): CompanyEntity?

	@Insert
	suspend fun insertCompany(c: CompanyEntity): Long

	@Update
	suspend fun updateCompany(c: CompanyEntity)

	@Delete
	suspend fun deleteCompany(c: CompanyEntity)

	/* company sites */
	@Query("SELECT * FROM company_site WHERE company_id = :companyId ORDER BY label COLLATE NOCASE")
	fun observeCompanySites(companyId: Long): Flow<List<CompanySiteEntity>>

	@Query("SELECT * FROM company_site")
	suspend fun allCompanySites(): List<CompanySiteEntity>

	@Insert
	suspend fun insertCompanySite(s: CompanySiteEntity): Long

	@Update
	suspend fun updateCompanySite(s: CompanySiteEntity)

	@Delete
	suspend fun deleteCompanySite(s: CompanySiteEntity)

	/* clients */
	@Query("SELECT * FROM client ORDER BY archived, name COLLATE NOCASE")
	fun observeClients(): Flow<List<ClientEntity>>

	@Query("SELECT * FROM client WHERE id = :id")
	suspend fun client(id: Long): ClientEntity?

	@Insert
	suspend fun insertClient(c: ClientEntity): Long

	@Update
	suspend fun updateClient(c: ClientEntity)

	@Delete
	suspend fun deleteClient(c: ClientEntity)

	@Query("SELECT COUNT(*) FROM time_entry WHERE client_id = :clientId")
	suspend fun entriesOnClient(clientId: Long): Int

	/* client sites */
	@Query("SELECT * FROM client_site WHERE client_id = :clientId ORDER BY label COLLATE NOCASE")
	fun observeClientSites(clientId: Long): Flow<List<ClientSiteEntity>>

	@Query("SELECT * FROM client_site")
	suspend fun allClientSites(): List<ClientSiteEntity>

	@Insert
	suspend fun insertClientSite(s: ClientSiteEntity): Long

	@Update
	suspend fun updateClientSite(s: ClientSiteEntity)

	@Delete
	suspend fun deleteClientSite(s: ClientSiteEntity)

	/* places */
	@Query("SELECT * FROM place ORDER BY kind, label COLLATE NOCASE")
	fun observePlaces(): Flow<List<PlaceEntity>>

	@Query("SELECT * FROM place")
	suspend fun allPlaces(): List<PlaceEntity>

	@Insert
	suspend fun insertPlace(p: PlaceEntity): Long

	@Update
	suspend fun updatePlace(p: PlaceEntity)

	@Delete
	suspend fun deletePlace(p: PlaceEntity)

	/* roles */
	@Query("SELECT * FROM role ORDER BY archived, name COLLATE NOCASE")
	fun observeRoles(): Flow<List<RoleEntity>>

	@Insert
	suspend fun insertRole(r: RoleEntity): Long

	@Update
	suspend fun updateRole(r: RoleEntity)

	@Delete
	suspend fun deleteRole(r: RoleEntity)

	/* activity presets */
	@Query("SELECT * FROM activity_preset ORDER BY text COLLATE NOCASE")
	fun observePresets(): Flow<List<ActivityPresetEntity>>

	@Insert
	suspend fun insertPreset(p: ActivityPresetEntity): Long

	@Update
	suspend fun updatePreset(p: ActivityPresetEntity)

	@Delete
	suspend fun deletePreset(p: ActivityPresetEntity)

	/* working hours */
	@Query("SELECT * FROM work_schedule ORDER BY day_of_week, start_min")
	fun observeSchedule(): Flow<List<WorkScheduleEntity>>

	@Query("SELECT * FROM work_schedule WHERE day_of_week = :dayOfWeek")
	suspend fun scheduleOf(dayOfWeek: Int): List<WorkScheduleEntity>

	@Insert
	suspend fun insertSchedule(s: WorkScheduleEntity): Long

	@Update
	suspend fun updateSchedule(s: WorkScheduleEntity)

	@Delete
	suspend fun deleteSchedule(s: WorkScheduleEntity)

	/* lunch breaks, one per day of week */
	@Query("SELECT * FROM lunch_break ORDER BY day_of_week")
	fun observeLunchBreaks(): Flow<List<LunchBreakEntity>>

	@Query("DELETE FROM lunch_break WHERE day_of_week = :dayOfWeek")
	suspend fun deleteLunchBreak(dayOfWeek: Int)

	@Insert
	suspend fun insertLunchBreak(l: LunchBreakEntity): Long

	@Query("SELECT * FROM day_off ORDER BY date_epoch_day DESC")
	fun observeDaysOff(): Flow<List<DayOffEntity>>

	@Query("SELECT date_epoch_day FROM day_off WHERE date_epoch_day BETWEEN :from AND :to")
	suspend fun daysOffBetween(from: Long, to: Long): List<Long>

	@Upsert
	suspend fun upsertDayOff(d: DayOffEntity)

	@Delete
	suspend fun deleteDayOff(d: DayOffEntity)
}
