/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import kotlinx.coroutines.flow.Flow

/* What the plugins' imports read and write (issue #48 B3). */
@Dao
interface PluginDao {
	@Query("DELETE FROM remote_item WHERE company_id = :companyId AND plugin_id = :pluginId")
	suspend fun clearItems(companyId: Long, pluginId: String)

	@Insert
	suspend fun insertItems(items: List<RemoteItemEntity>)

	@Query("SELECT uid FROM company WHERE id = :companyId")
	suspend fun companyUid(companyId: Long): String?

	/*
	 * A sync replaces the company's lists of that plugin at once; each item
	 * keeps its uid from one sync to the next (issue #111).
	 */
	@Transaction
	suspend fun replaceItems(companyId: Long, pluginId: String, items: List<RemoteItemEntity>) {
		val company = companyUid(companyId) ?: return
		clearItems(companyId, pluginId)
		insertItems(
			items.distinctBy { listOf(it.list, it.parentId, it.id) }
				.map { it.copy(uid = SyncIds.remoteItem(company, pluginId, it.list, it.parentId, it.id)) },
		)
	}

	@Query("SELECT * FROM remote_item WHERE company_id = :companyId AND plugin_id = :pluginId AND list = :list ORDER BY parent_id, sort_order, name COLLATE NOCASE")
	fun observeItems(companyId: Long, pluginId: String, list: String): Flow<List<RemoteItemEntity>>

	@Query("SELECT * FROM remote_item WHERE company_id = :companyId AND plugin_id = :pluginId")
	suspend fun items(companyId: Long, pluginId: String): List<RemoteItemEntity>

	@Query("SELECT * FROM remote_item WHERE company_id = :companyId AND plugin_id = :pluginId ORDER BY sort_order, name COLLATE NOCASE")
	fun observeAllItems(companyId: Long, pluginId: String): Flow<List<RemoteItemEntity>>

	@Query("SELECT * FROM client WHERE company_id = :companyId")
	suspend fun clientsOf(companyId: Long): List<ClientEntity>

	@Insert
	suspend fun insertClient(c: ClientEntity): Long

	/* an activity imported from the plugin (activity model 1.0: the to-dos are TODO activities) */
	@Query("SELECT * FROM time_entry WHERE company_id = :companyId AND external_key = :key LIMIT 1")
	suspend fun activityByKey(companyId: Long, key: String): TimeEntryEntity?

	@Insert
	suspend fun insertActivity(t: TimeEntryEntity): Long

	@Update
	suspend fun updateActivity(t: TimeEntryEntity)

	@Query("DELETE FROM segment WHERE entry_id = :entryId AND planned = 1")
	suspend fun deletePlannedSegments(entryId: Long)

	/* an imported activity updated; [deletePlanned]: the remote ticked it off, its plan has served (ACTIVITY-MODEL.md 3) */
	@Transaction
	suspend fun updateActivity(t: TimeEntryEntity, deletePlanned: Boolean) {
		if (deletePlanned)
			deletePlannedSegments(t.id)
		updateActivity(t)
	}

	@Query("SELECT contact.* FROM contact JOIN client ON client.id = contact.client_id WHERE client.company_id = :companyId AND contact.external_key = :key LIMIT 1")
	suspend fun contactByKey(companyId: Long, key: String): ContactEntity?

	@Insert
	suspend fun insertContact(c: ContactEntity): Long

	@Insert
	suspend fun insertPhone(p: ContactPhoneEntity)

	/*
	 * Closed activities of the company the plugin may send (B4): those with
	 * its entry fields, those imported from it (an external key), and those
	 * already sent (an edit or a deletion of blocks must reach the remote
	 * side). Absences and deleted activities never go.
	 */
	@Query(
		"SELECT * FROM time_entry WHERE company_id = :companyId AND status = 'CLOSED' AND kind = 'ACTIVITY' AND deleted_utc_ms IS NULL " +
			"AND (plugin_fields IS NOT NULL OR external_key IS NOT NULL " +
			"OR id IN (SELECT entry_id FROM remote_record WHERE plugin_id = :pluginId)) ORDER BY id",
	)
	suspend fun entriesToSend(companyId: Long, pluginId: String): List<TimeEntryEntity>

	@Query("SELECT * FROM segment WHERE entry_id = :entryId AND planned = 0 ORDER BY start_utc_ms")
	suspend fun segmentsOf(entryId: Long): List<SegmentEntity>

	@Query("SELECT * FROM client WHERE id = :id")
	suspend fun client(id: Long): ClientEntity?

	@Query("SELECT * FROM entry_material WHERE entry_id = :entryId ORDER BY created_utc_ms, id")
	suspend fun materialsOf(entryId: Long): List<EntryMaterialEntity>

	@Query("SELECT * FROM remote_record WHERE entry_id = :entryId AND plugin_id = :pluginId")
	suspend fun sent(entryId: Long, pluginId: String): List<RemoteRecordEntity>

	@Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
	suspend fun recordSent(r: RemoteRecordEntity)

	@Query("DELETE FROM remote_record WHERE entry_id = :entryId AND plugin_id = :pluginId AND block_key = :key")
	suspend fun forgetSent(entryId: Long, pluginId: String, key: String)

	/* the plugin's activities closed in the app (export.activityClosed closes them on the remote side once) */
	@Query("SELECT * FROM time_entry WHERE company_id = :companyId AND external_key IS NOT NULL AND status = 'CLOSED' AND kind = 'ACTIVITY' AND deleted_utc_ms IS NULL")
	suspend fun closedImported(companyId: Long): List<TimeEntryEntity>

	/*
	 * What was done for an imported activity: its own description and those
	 * of the activities pointing at it with a UUID reference (the entries of
	 * a to-do before 1.0), oldest first.
	 */
	@Query(
		"SELECT activity FROM time_entry WHERE (id = :entryId OR id IN (SELECT entry_id FROM external_ref WHERE kind = 'UUID' AND value = :uid)) " +
			"AND activity IS NOT NULL AND activity != '' ORDER BY created_utc_ms",
	)
	suspend fun activitiesOf(entryId: Long, uid: String): List<String>

	@Query("SELECT * FROM remote_record WHERE entry_id = :entryId")
	suspend fun sentAll(entryId: Long): List<RemoteRecordEntity>

	@Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
	suspend fun recordDeleted(d: RemoteDeletedEntity)

	@Query("SELECT * FROM remote_deleted WHERE company_id = :companyId AND plugin_id = :pluginId ORDER BY deleted_utc_ms")
	suspend fun deleted(companyId: Long, pluginId: String): List<RemoteDeletedEntity>

	@Query("DELETE FROM remote_deleted WHERE plugin_id = :pluginId AND remote_id = :remoteId")
	suspend fun forgetDeleted(pluginId: String, remoteId: String)

	@Query("UPDATE time_entry SET plugin_fields = :json WHERE id = :entryId")
	suspend fun setEntryFields(entryId: Long, json: String?)
}
