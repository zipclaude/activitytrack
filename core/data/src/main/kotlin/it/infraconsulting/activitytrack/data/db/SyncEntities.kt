/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Sync bookkeeping (issue #111, docs SYNC.md §2 of ActivityTrack Web). The
 * rows of sync_change are written only by the triggers of SyncSchema.
 */

/* A change of a synced record, in the order it happened. op: "upsert" or "delete". */
@Entity(tableName = "sync_change", indices = [Index(value = ["type", "uid"])])
data class SyncChangeEntity(
	@PrimaryKey(autoGenerate = true) val seq: Long = 0,
	val type: String,
	val uid: String,
	val op: String,
	@ColumnInfo(name = "changed_utc_ms") val changedUtcMs: Long,
)

/* A record as last agreed with the server (JSON of its wire fields): a push sends what differs. */
@Entity(tableName = "sync_shadow", primaryKeys = ["type", "uid"])
data class SyncShadowEntity(
	val type: String,
	val uid: String,
	val json: String,
)

/*
 * The one row of the sync state. [enabled]: the triggers record changes
 * (set when the app is paired); [applying]: the sync itself is writing, the
 * triggers stay quiet and uids may change (adoption).
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
	@PrimaryKey val id: Int = 1,
	val enabled: Boolean = false,
	val applying: Boolean = false,
	@ColumnInfo(name = "server_url") val serverUrl: String? = null,
	/* the server revision pulled up to */
	val cursor: Long = 0,
	/* server clock minus phone clock at the last sync */
	@ColumnInfo(name = "clock_offset_ms") val clockOffsetMs: Long = 0,
	@ColumnInfo(name = "last_sync_utc_ms") val lastSyncUtcMs: Long? = null,
	@ColumnInfo(name = "last_error") val lastError: String? = null,
)

/* A record the last sync could not send or take (issue #119), listed under Settings → Sync. */
@Entity(tableName = "sync_error", primaryKeys = ["type", "uid"])
data class SyncErrorEntity(
	val type: String,
	val uid: String,
	val message: String,
	@ColumnInfo(name = "at_utc_ms") val atUtcMs: Long,
)
