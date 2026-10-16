/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
	@Insert
	suspend fun insertEntry(entry: TimeEntryEntity): Long

	@Update
	suspend fun updateEntry(entry: TimeEntryEntity)

	@Insert
	suspend fun insertSegment(segment: SegmentEntity): Long

	/* Never closes before the segment start (wall clock moved backwards). */
	@Query(
		"UPDATE segment SET end_utc_ms = MAX(start_utc_ms, :endUtcMs) " +
			"WHERE entry_id = :entryId AND end_utc_ms IS NULL",
	)
	suspend fun closeOpenSegment(entryId: Long, endUtcMs: Long): Int

	@Query("SELECT * FROM time_entry WHERE id = :id")
	suspend fun entry(id: Long): TimeEntryEntity?

	@Query("SELECT * FROM time_entry WHERE id = :id")
	fun observeEntry(id: Long): Flow<TimeEntryEntity?>

	@Query("SELECT COUNT(*) FROM attachment WHERE entry_id = :entryId")
	suspend fun attachmentCount(entryId: Long): Int

	/* Entries that still need user attention: active, suspended or to complete. */
	@Query("SELECT * FROM time_entry WHERE status != 'CLOSED' ORDER BY updated_utc_ms DESC")
	fun observeOpenEntries(): Flow<List<TimeEntryEntity>>

	@Query("SELECT * FROM segment WHERE entry_id = :entryId ORDER BY start_utc_ms")
	suspend fun segments(entryId: Long): List<SegmentEntity>

	@Query("SELECT * FROM segment WHERE entry_id = :entryId ORDER BY start_utc_ms")
	fun observeSegments(entryId: Long): Flow<List<SegmentEntity>>

	/* Segments overlapping [fromUtcMs, toUtcMs); open segments always overlap. */
	@Query(
		"SELECT * FROM segment WHERE start_utc_ms < :toUtcMs " +
			"AND (end_utc_ms IS NULL OR end_utc_ms > :fromUtcMs) ORDER BY entry_id, start_utc_ms",
	)
	suspend fun segmentsBetween(fromUtcMs: Long, toUtcMs: Long): List<SegmentEntity>

	/* Default activity and role: last entry on the same client since [sinceUtcMs]. */
	@Query(
		"SELECT * FROM time_entry WHERE client_id = :clientId AND created_utc_ms >= :sinceUtcMs " +
			"AND activity IS NOT NULL ORDER BY created_utc_ms DESC LIMIT 1",
	)
	suspend fun lastOnClient(clientId: Long, sinceUtcMs: Long): TimeEntryEntity?

	/* Most recent entry with a company created since [sinceUtcMs] (start of today). */
	@Query(
		"SELECT * FROM time_entry WHERE company_id IS NOT NULL AND created_utc_ms >= :sinceUtcMs " +
			"ORDER BY created_utc_ms DESC LIMIT 1",
	)
	suspend fun lastWithCompanySince(sinceUtcMs: Long): TimeEntryEntity?

	@Transaction
	suspend fun startEntry(entry: TimeEntryEntity, first: SegmentEntity): Long {
		val id = insertEntry(entry)
		insertSegment(first.copy(entryId = id))
		return id
	}
}
