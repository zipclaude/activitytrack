/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
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

	/* paired (issue #113): the server; the log stays off until the first sync (issue #114) */
	@Query("UPDATE sync_state SET server_url = :url, enabled = 0, applying = 0, cursor = 0, clock_offset_ms = 0, last_sync_utc_ms = NULL, last_error = NULL WHERE id = 1")
	suspend fun setServer(url: String)

	/* unpaired: the phone keeps its data, the bookkeeping of the old server goes */
	@Transaction
	suspend fun forgetServer() {
		clearState()
		clearChanges()
		clearShadow()
	}

	@Query("UPDATE sync_state SET server_url = NULL, enabled = 0, applying = 0, cursor = 0, clock_offset_ms = 0, last_sync_utc_ms = NULL, last_error = NULL WHERE id = 1")
	suspend fun clearState()

	@Query("DELETE FROM sync_change")
	suspend fun clearChanges()

	@Query("DELETE FROM sync_shadow")
	suspend fun clearShadow()

	@Query("SELECT COUNT(*) FROM sync_change")
	fun observePending(): Flow<Int>

	@Query("SELECT * FROM sync_change ORDER BY seq LIMIT :limit")
	suspend fun changes(limit: Int): List<SyncChangeEntity>

	@Query("DELETE FROM sync_change WHERE seq <= :seq")
	suspend fun dropChangesUpTo(seq: Long)
}
