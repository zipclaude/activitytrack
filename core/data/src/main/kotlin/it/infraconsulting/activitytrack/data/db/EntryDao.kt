/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/* Newest entry creation time per client (client picker order). */
data class ClientLastUse(
	@ColumnInfo(name = "client_id") val clientId: Long,
	@ColumnInfo(name = "last_ms") val lastMs: Long,
)

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

	@Query(
		"SELECT client_id, MAX(created_utc_ms) AS last_ms FROM time_entry " +
			"WHERE client_id IS NOT NULL GROUP BY client_id",
	)
	fun observeClientLastUse(): Flow<List<ClientLastUse>>

	/* Segments of every entry that is not CLOSED (home screen timers). */
	@Query(
		"SELECT segment.* FROM segment JOIN time_entry ON time_entry.id = segment.entry_id " +
			"WHERE time_entry.status != 'CLOSED' ORDER BY segment.entry_id, segment.start_utc_ms",
	)
	fun observeOpenEntrySegments(): Flow<List<SegmentEntity>>

	@Query(
		"SELECT * FROM time_entry WHERE status = 'SUSPENDED' AND id != :excludeId " +
			"ORDER BY updated_utc_ms DESC LIMIT 1",
	)
	suspend fun mostRecentSuspended(excludeId: Long): TimeEntryEntity?

	/* Every segment of the entries having at least one segment overlapping [fromUtcMs, toUtcMs). */
	@Query(
		"SELECT * FROM segment WHERE entry_id IN (" +
			"SELECT DISTINCT entry_id FROM segment WHERE start_utc_ms < :toUtcMs " +
			"AND (end_utc_ms IS NULL OR end_utc_ms > :fromUtcMs)) ORDER BY entry_id, start_utc_ms",
	)
	suspend fun segmentsOfEntriesTouching(fromUtcMs: Long, toUtcMs: Long): List<SegmentEntity>

	@Query("SELECT * FROM time_entry WHERE id IN (:ids)")
	suspend fun entries(ids: List<Long>): List<TimeEntryEntity>

	@Query("SELECT * FROM segment WHERE id = :id")
	suspend fun segment(id: Long): SegmentEntity?

	@Query("DELETE FROM segment WHERE id = :id")
	suspend fun deleteSegment(id: Long)

	@Query("SELECT * FROM external_ref WHERE entry_id = :entryId ORDER BY id")
	fun observeExternalRefs(entryId: Long): Flow<List<ExternalRefEntity>>

	@Query("SELECT * FROM external_ref WHERE entry_id IN (:entryIds) ORDER BY entry_id, id")
	suspend fun externalRefs(entryIds: List<Long>): List<ExternalRefEntity>

	@Insert
	suspend fun insertExternalRef(ref: ExternalRefEntity): Long

	@Query("DELETE FROM external_ref WHERE id = :id")
	suspend fun deleteExternalRef(id: Long)

	@Transaction
	suspend fun startEntry(entry: TimeEntryEntity, first: SegmentEntity): Long {
		val id = insertEntry(entry)
		insertSegment(first.copy(entryId = id))
		return id
	}
}
