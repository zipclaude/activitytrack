/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
	@Insert
	suspend fun insert(t: TodoEntity): Long

	@Update
	suspend fun update(t: TodoEntity)

	@Query("SELECT * FROM todo WHERE id = :id")
	suspend fun byId(id: Long): TodoEntity?

	@Query("SELECT * FROM todo WHERE id = :id")
	fun observe(id: Long): Flow<TodoEntity?>

	@Query("SELECT * FROM todo WHERE uid = :uid")
	suspend fun byUid(uid: String): TodoEntity?

	@Query("SELECT * FROM todo WHERE company_id = :companyId AND external_key = :key")
	suspend fun byExternalKey(companyId: Long, key: String): TodoEntity?

	/* open (not done, not ignored) first by due date (undated last), then priority (1 = highest) */
	@Query(
		"SELECT * FROM todo WHERE done = 0 AND ignored_utc_ms IS NULL ORDER BY due_epoch_day IS NULL, due_epoch_day, " +
			"due_time_min IS NULL, due_time_min, priority IS NULL, priority, title COLLATE NOCASE",
	)
	fun observeOpen(): Flow<List<TodoEntity>>

	/* ignored and not done, most recently ignored first */
	@Query("SELECT * FROM todo WHERE done = 0 AND ignored_utc_ms IS NOT NULL ORDER BY ignored_utc_ms DESC")
	fun observeIgnored(): Flow<List<TodoEntity>>

	/* ignored ones after the others (M10: at the bottom of the lists) */
	@Query("SELECT * FROM todo ORDER BY done, ignored_utc_ms IS NOT NULL, due_epoch_day IS NULL, due_epoch_day, title COLLATE NOCASE")
	fun observeAll(): Flow<List<TodoEntity>>

	@Query("SELECT * FROM todo")
	suspend fun all(): List<TodoEntity>

	@Query("UPDATE todo SET plan_reminded_utc_ms = :startMs WHERE id = :id")
	suspend fun markPlanReminded(id: Long, startMs: Long)

	@Query("SELECT COUNT(*) FROM time_entry WHERE todo_id = :todoId")
	suspend fun entriesOf(todoId: Long): Int
}
