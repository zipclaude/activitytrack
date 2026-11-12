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
			"WHERE entry_id = :entryId AND end_utc_ms IS NULL AND planned = 0",
	)
	suspend fun closeOpenSegment(entryId: Long, endUtcMs: Long): Int

	@Query("SELECT * FROM time_entry WHERE id = :id")
	suspend fun entry(id: Long): TimeEntryEntity?

	@Query("SELECT * FROM time_entry WHERE id = :id")
	fun observeEntry(id: Long): Flow<TimeEntryEntity?>

	@Query("SELECT COUNT(*) FROM attachment WHERE entry_id = :entryId")
	suspend fun attachmentCount(entryId: Long): Int

	/* The activities running with a segment open: without concurrent activities only one may (issue #132). */
	@Query("SELECT * FROM time_entry WHERE status = 'RUNNING' AND mode IS NOT NULL AND deleted_utc_ms IS NULL")
	suspend fun activeEntries(): List<TimeEntryEntity>

	/* The running activities (a segment open or suspended), not deleted. */
	@Query("SELECT * FROM time_entry WHERE status = 'RUNNING' AND deleted_utc_ms IS NULL ORDER BY updated_utc_ms DESC")
	fun observeOpenEntries(): Flow<List<TimeEntryEntity>>

	/* TODO activities not deleted: plan first (by day and time, unplanned last), then due date, priority (highest first). */
	@Query(
		"SELECT * FROM time_entry WHERE status = 'TODO' AND deleted_utc_ms IS NULL ORDER BY plan_epoch_day IS NULL, plan_epoch_day, " +
			"plan_start_min IS NULL, plan_start_min, due_epoch_day IS NULL, due_epoch_day, due_time_min IS NULL, due_time_min, priority DESC, activity COLLATE NOCASE",
	)
	fun observeTodos(): Flow<List<TimeEntryEntity>>

	@Query("SELECT * FROM time_entry WHERE status = 'TODO' AND deleted_utc_ms IS NULL")
	suspend fun todos(): List<TimeEntryEntity>

	/* The bin: deleted activities, most recently deleted first. */
	@Query("SELECT * FROM time_entry WHERE deleted_utc_ms IS NOT NULL ORDER BY deleted_utc_ms DESC")
	fun observeDeleted(): Flow<List<TimeEntryEntity>>

	/* Absences (kind ABSENCE) not deleted, newest first. */
	@Query("SELECT * FROM time_entry WHERE kind = 'ABSENCE' AND deleted_utc_ms IS NULL ORDER BY created_utc_ms DESC")
	fun observeAbsences(): Flow<List<TimeEntryEntity>>

	/* The absence segments touching [fromUtcMs, toUtcMs) of absences not deleted. */
	@Query(
		"SELECT segment.* FROM segment JOIN time_entry ON time_entry.id = segment.entry_id WHERE time_entry.kind = 'ABSENCE' " +
			"AND time_entry.deleted_utc_ms IS NULL AND segment.start_utc_ms < :toUtcMs AND segment.end_utc_ms > :fromUtcMs",
	)
	suspend fun absenceSegmentsBetween(fromUtcMs: Long, toUtcMs: Long): List<SegmentEntity>

	/* IS: null matches the activities of no company (issue #48) */
	@Query("SELECT * FROM time_entry WHERE company_id IS :companyId AND external_key = :key LIMIT 1")
	suspend fun byExternalKey(companyId: Long?, key: String): TimeEntryEntity?

	@Query("SELECT * FROM time_entry WHERE uid = :uid")
	suspend fun byUid(uid: String): TimeEntryEntity?

	@Query("UPDATE time_entry SET plan_reminded_utc_ms = :startMs WHERE id = :id")
	suspend fun markPlanReminded(id: Long, startMs: Long)

	@Query("DELETE FROM segment WHERE entry_id = :entryId AND planned = 1")
	suspend fun deletePlannedSegments(entryId: Long)

	@Query("SELECT * FROM segment WHERE entry_id = :entryId ORDER BY start_utc_ms")
	suspend fun segments(entryId: Long): List<SegmentEntity>

	@Query("SELECT * FROM segment WHERE entry_id = :entryId ORDER BY start_utc_ms")
	fun observeSegments(entryId: Long): Flow<List<SegmentEntity>>

	/* Real segments overlapping [fromUtcMs, toUtcMs); open segments always overlap. */
	@Query(
		"SELECT * FROM segment WHERE planned = 0 AND start_utc_ms < :toUtcMs " +
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

	/* Segments of every running activity (home screen timers). */
	@Query(
		"SELECT segment.* FROM segment JOIN time_entry ON time_entry.id = segment.entry_id " +
			"WHERE time_entry.status = 'RUNNING' AND time_entry.deleted_utc_ms IS NULL ORDER BY segment.entry_id, segment.start_utc_ms",
	)
	fun observeOpenEntrySegments(): Flow<List<SegmentEntity>>

	/* the tags of the open entries: dots on the Home cards (issue #133) */
	@Query("SELECT entry_tag.* FROM entry_tag JOIN time_entry ON time_entry.id = entry_tag.entry_id WHERE time_entry.status = 'RUNNING' AND time_entry.deleted_utc_ms IS NULL")
	fun observeOpenEntryTags(): Flow<List<EntryTagEntity>>

	/*
	 * The "Chiuse" tab of the Home (issue #59): closed activities (not
	 * absences, not deleted) with their last segment ending after
	 * [fromUtcMs], newest end first.
	 */
	@Query(
		"SELECT time_entry.* FROM time_entry JOIN segment ON segment.entry_id = time_entry.id " +
			"WHERE time_entry.status = 'CLOSED' AND time_entry.kind = 'ACTIVITY' AND time_entry.deleted_utc_ms IS NULL " +
			"GROUP BY time_entry.id HAVING MAX(segment.end_utc_ms) > :fromUtcMs " +
			"ORDER BY MAX(segment.end_utc_ms) DESC LIMIT :limit",
	)
	fun observeClosedSince(fromUtcMs: Long, limit: Int): Flow<List<TimeEntryEntity>>

	@Query(
		"SELECT segment.* FROM segment WHERE segment.entry_id IN (" +
			"SELECT time_entry.id FROM time_entry JOIN segment s2 ON s2.entry_id = time_entry.id " +
			"WHERE time_entry.status = 'CLOSED' AND time_entry.kind = 'ACTIVITY' AND time_entry.deleted_utc_ms IS NULL " +
			"GROUP BY time_entry.id HAVING MAX(s2.end_utc_ms) > :fromUtcMs " +
			"ORDER BY MAX(s2.end_utc_ms) DESC LIMIT :limit) " +
			"ORDER BY segment.entry_id, segment.start_utc_ms",
	)
	fun observeClosedSegmentsSince(fromUtcMs: Long, limit: Int): Flow<List<SegmentEntity>>

	@Query(
		"SELECT entry_tag.* FROM entry_tag WHERE entry_tag.entry_id IN (" +
			"SELECT time_entry.id FROM time_entry JOIN segment ON segment.entry_id = time_entry.id " +
			"WHERE time_entry.status = 'CLOSED' AND time_entry.kind = 'ACTIVITY' AND time_entry.deleted_utc_ms IS NULL " +
			"GROUP BY time_entry.id HAVING MAX(segment.end_utc_ms) > :fromUtcMs " +
			"ORDER BY MAX(segment.end_utc_ms) DESC LIMIT :limit)",
	)
	fun observeClosedTagsSince(fromUtcMs: Long, limit: Int): Flow<List<EntryTagEntity>>


	/*
	 * The ticked tasks (CLOSED without a real segment, ACTIVITY-MODEL.md 2.4)
	 * closed after [fromUtcMs], newest first: no segment end to order them by,
	 * so the "Chiuse" tab takes them by closed_utc_ms.
	 */
	@Query(
		"SELECT * FROM time_entry WHERE status = 'CLOSED' AND kind = 'ACTIVITY' AND deleted_utc_ms IS NULL " +
			"AND closed_utc_ms > :fromUtcMs " +
			"AND NOT EXISTS (SELECT 1 FROM segment WHERE segment.entry_id = time_entry.id AND segment.planned = 0) " +
			"ORDER BY closed_utc_ms DESC LIMIT :limit",
	)
	fun observeTickedSince(fromUtcMs: Long, limit: Int): Flow<List<TimeEntryEntity>>

	@Query(
		"SELECT entry_tag.* FROM entry_tag WHERE entry_tag.entry_id IN (" +
			"SELECT id FROM time_entry WHERE status = 'CLOSED' AND kind = 'ACTIVITY' AND deleted_utc_ms IS NULL " +
			"AND closed_utc_ms > :fromUtcMs " +
			"AND NOT EXISTS (SELECT 1 FROM segment WHERE segment.entry_id = time_entry.id AND segment.planned = 0) " +
			"ORDER BY closed_utc_ms DESC LIMIT :limit)",
	)
	fun observeTickedTagsSince(fromUtcMs: Long, limit: Int): Flow<List<EntryTagEntity>>

	@Query(
		"SELECT * FROM time_entry WHERE status = 'RUNNING' AND mode IS NULL AND deleted_utc_ms IS NULL AND id != :excludeId " +
			"ORDER BY updated_utc_ms DESC LIMIT 1",
	)
	suspend fun mostRecentSuspended(excludeId: Long): TimeEntryEntity?

	/*
	 * Every real segment of the activities (not deleted) having at least one
	 * overlapping [fromUtcMs, toUtcMs): reports and the time grid; planned
	 * segments never count (ACTIVITY-MODEL.md 1.2).
	 */
	@Query(
		"SELECT * FROM segment WHERE planned = 0 AND entry_id IN (" +
			"SELECT DISTINCT s.entry_id FROM segment s JOIN time_entry t ON t.id = s.entry_id WHERE t.deleted_utc_ms IS NULL AND s.planned = 0 " +
			"AND s.start_utc_ms < :toUtcMs AND (s.end_utc_ms IS NULL OR s.end_utc_ms > :fromUtcMs)) ORDER BY entry_id, start_utc_ms",
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

	@Query("SELECT created_utc_ms FROM time_entry WHERE kind = 'ACTIVITY' AND status != 'TODO' AND deleted_utc_ms IS NULL AND created_utc_ms >= :fromMs AND created_utc_ms < :toMs")
	suspend fun entryStartsBetween(fromMs: Long, toMs: Long): List<Long>

	@Transaction
	suspend fun startEntry(entry: TimeEntryEntity, first: SegmentEntity): Long {
		val id = insertEntry(entry)
		insertSegment(first.copy(entryId = id))
		return id
	}

	/* worked between the two instants on [entryId] (a real segment touching them) */
	@Query("SELECT COUNT(*) FROM segment WHERE entry_id = :entryId AND planned = 0 AND start_utc_ms < :toMs AND COALESCE(end_utc_ms, :toMs) > :fromMs")
	suspend fun workedBetween(entryId: Long, fromMs: Long, toMs: Long): Int
}
