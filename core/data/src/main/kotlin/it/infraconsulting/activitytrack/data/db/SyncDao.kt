/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/* The sync bookkeeping (issue #111); the engine of issue #114 extends it. */
@Dao
interface SyncDao {
	@Query("SELECT * FROM sync_state WHERE id = 1")
	suspend fun state(): SyncStateEntity?

	@Query("SELECT * FROM sync_state WHERE id = 1")
	fun observeState(): Flow<SyncStateEntity?>

	@Query("UPDATE sync_state SET enabled = :enabled WHERE id = 1")
	suspend fun setEnabled(enabled: Boolean)

	@Query("SELECT COUNT(*) FROM sync_change")
	fun observePending(): Flow<Int>

	@Query("SELECT * FROM sync_change ORDER BY seq LIMIT :limit")
	suspend fun changes(limit: Int): List<SyncChangeEntity>

	@Query("DELETE FROM sync_change WHERE seq <= :seq")
	suspend fun dropChangesUpTo(seq: Long)
}
