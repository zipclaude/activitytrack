/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Query

/* Bulk reads for import and export (M4). */
@Dao
interface InterchangeDao {
	@Query("SELECT * FROM activity_preset")
	suspend fun allPresets(): List<ActivityPresetEntity>

	@Query("SELECT * FROM time_entry WHERE uid = :uid")
	suspend fun entryByUid(uid: String): TimeEntryEntity?

	@Query("SELECT * FROM time_entry")
	suspend fun allEntries(): List<TimeEntryEntity>

	@Query("SELECT * FROM segment WHERE entry_id IN (:entryIds) ORDER BY entry_id, start_utc_ms")
	suspend fun segmentsOf(entryIds: List<Long>): List<SegmentEntity>

	@Query("SELECT * FROM expense WHERE entry_id IN (:entryIds) ORDER BY entry_id, created_utc_ms")
	suspend fun expensesOf(entryIds: List<Long>): List<ExpenseEntity>

	@Query("SELECT * FROM absence ORDER BY date_epoch_day")
	suspend fun allAbsences(): List<AbsenceEntity>

	@Query("SELECT * FROM day_off ORDER BY date_epoch_day")
	suspend fun allDaysOff(): List<DayOffEntity>
}
