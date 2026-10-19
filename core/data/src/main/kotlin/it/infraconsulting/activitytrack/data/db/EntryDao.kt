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

/* Newest entry on a company or role (M16 "last use" order). */
data class LastUse(
	val id: Long,
	@ColumnInfo(name = "last_ms") val lastMs: Long,
)

data class ExpenseTotal(
	@ColumnInfo(name = "entry_id") val entryId: Long,
	@ColumnInfo(name = "cents") val cents: Long,
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

	@Query(
		"SELECT company_id AS id, MAX(created_utc_ms) AS last_ms FROM time_entry " +
			"WHERE company_id IS NOT NULL GROUP BY company_id",
	)
	fun observeCompanyLastUse(): Flow<List<LastUse>>

	@Query(
		"SELECT role_id AS id, MAX(created_utc_ms) AS last_ms FROM time_entry " +
			"WHERE role_id IS NOT NULL GROUP BY role_id",
	)
	fun observeRoleLastUse(): Flow<List<LastUse>>

	/* Segments of every entry that is not CLOSED (home screen timers). */
	@Query(
		"SELECT segment.* FROM segment JOIN time_entry ON time_entry.id = segment.entry_id " +
			"WHERE time_entry.status != 'CLOSED' ORDER BY segment.entry_id, segment.start_utc_ms",
	)
	fun observeOpenEntrySegments(): Flow<List<SegmentEntity>>

	/* The last [n] completed entries (Home keeps a few in view, owner 2026-10-19). */
	@Query("SELECT * FROM time_entry WHERE status = 'CLOSED' ORDER BY updated_utc_ms DESC LIMIT :n")
	fun observeRecentClosed(n: Int): Flow<List<TimeEntryEntity>>

	@Query(
		"SELECT * FROM segment WHERE entry_id IN " +
			"(SELECT id FROM time_entry WHERE status = 'CLOSED' ORDER BY updated_utc_ms DESC LIMIT :n) " +
			"ORDER BY entry_id, start_utc_ms",
	)
	fun observeRecentClosedSegments(n: Int): Flow<List<SegmentEntity>>

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

	/* Any entry change bumps updated_utc_ms (invariant 4): a cheap "something changed" signal. */
	@Query("SELECT MAX(updated_utc_ms) FROM time_entry")
	fun observeLastChange(): Flow<Long?>

	@Query("SELECT entry_id, SUM(amount_cents) AS cents FROM expense WHERE entry_id IN (:entryIds) GROUP BY entry_id")
	suspend fun expenseTotals(entryIds: List<Long>): List<ExpenseTotal>

	@Query("SELECT COUNT(*) FROM attachment")
	suspend fun attachmentTotal(): Int

	@Insert
	suspend fun insertExpense(e: ExpenseEntity): Long

	@Query("SELECT * FROM expense WHERE entry_id = :entryId ORDER BY created_utc_ms")
	fun observeExpenses(entryId: Long): Flow<List<ExpenseEntity>>

	@Query("DELETE FROM expense WHERE id = :id")
	suspend fun deleteExpense(id: Long)

	@Query("SELECT tag FROM entry_tag WHERE entry_id = :entryId ORDER BY tag")
	fun observeTags(entryId: Long): Flow<List<String>>

	/* every tag in use, most used first (suggestions) */
	@Query("SELECT tag FROM entry_tag GROUP BY tag ORDER BY COUNT(*) DESC, tag")
	fun observeAllTags(): Flow<List<String>>

	@Query("SELECT * FROM entry_tag WHERE entry_id IN (:entryIds)")
	suspend fun tagsOf(entryIds: List<Long>): List<EntryTagEntity>

	@Query("DELETE FROM entry_tag WHERE entry_id = :entryId")
	suspend fun clearTags(entryId: Long)

	@androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
	suspend fun insertTags(tags: List<EntryTagEntity>)

	@androidx.room.Update
	suspend fun updateSegment(s: SegmentEntity)

	@Query("SELECT COUNT(*) FROM expense WHERE entry_id = :entryId")
	suspend fun expenseCount(entryId: Long): Int

	/* segments, samples, references cascade */
	@Query("DELETE FROM time_entry WHERE id = :id")
	suspend fun deleteEntry(id: Long)

	@Query("SELECT * FROM attachment WHERE id = :id")
	suspend fun attachment(id: Long): AttachmentEntity?

	@Insert
	suspend fun insertAttachment(a: AttachmentEntity): Long

	@Query("SELECT * FROM attachment WHERE entry_id = :entryId ORDER BY created_utc_ms")
	fun observeAttachments(entryId: Long): Flow<List<AttachmentEntity>>

	@Query("DELETE FROM attachment WHERE id = :id")
	suspend fun deleteAttachment(id: Long)

	/* Samples taken after a backdated transition belong to the segment opened by it. */
	@Query("UPDATE location_sample SET segment_id = :segmentId WHERE entry_id = :entryId AND ts_utc_ms >= :fromUtcMs")
	suspend fun retagSamples(entryId: Long, segmentId: Long, fromUtcMs: Long)

	@Insert
	suspend fun insertAbsence(a: AbsenceEntity): Long

	@Query("DELETE FROM absence WHERE date_epoch_day = :day AND kind = :kind")
	suspend fun deleteAbsence(day: Long, kind: AbsenceKind)

	@Query("SELECT * FROM absence WHERE date_epoch_day BETWEEN :fromDay AND :toDay ORDER BY date_epoch_day")
	suspend fun absencesBetween(fromDay: Long, toDay: Long): List<AbsenceEntity>

	@Query("SELECT * FROM absence ORDER BY date_epoch_day DESC")
	fun observeAbsences(): Flow<List<AbsenceEntity>>

	@Query("SELECT created_utc_ms FROM time_entry WHERE created_utc_ms >= :fromMs AND created_utc_ms < :toMs")
	suspend fun entryStartsBetween(fromMs: Long, toMs: Long): List<Long>

	@Transaction
	suspend fun startEntry(entry: TimeEntryEntity, first: SegmentEntity): Long {
		val id = insertEntry(entry)
		insertSegment(first.copy(entryId = id))
		return id
	}
}
