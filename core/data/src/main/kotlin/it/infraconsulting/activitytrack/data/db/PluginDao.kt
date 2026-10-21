/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/* What the plugins' imports read and write (issue #48 B3). */
@Dao
interface PluginDao {
	@Query("DELETE FROM remote_item WHERE company_id = :companyId AND plugin_id = :pluginId")
	suspend fun clearItems(companyId: Long, pluginId: String)

	@Insert
	suspend fun insertItems(items: List<RemoteItemEntity>)

	/* a sync replaces the company's lists of that plugin at once */
	@Transaction
	suspend fun replaceItems(companyId: Long, pluginId: String, items: List<RemoteItemEntity>) {
		clearItems(companyId, pluginId)
		insertItems(items.distinctBy { listOf(it.list, it.parentId, it.id) })
	}

	@Query("SELECT * FROM remote_item WHERE company_id = :companyId AND plugin_id = :pluginId AND list = :list ORDER BY parent_id, sort_order, name COLLATE NOCASE")
	fun observeItems(companyId: Long, pluginId: String, list: String): Flow<List<RemoteItemEntity>>

	@Query("SELECT * FROM remote_item WHERE company_id = :companyId AND plugin_id = :pluginId")
	suspend fun items(companyId: Long, pluginId: String): List<RemoteItemEntity>

	@Query("SELECT * FROM client WHERE company_id = :companyId")
	suspend fun clientsOf(companyId: Long): List<ClientEntity>

	@Insert
	suspend fun insertClient(c: ClientEntity): Long

	@Query("SELECT * FROM todo WHERE company_id = :companyId AND external_key = :key LIMIT 1")
	suspend fun todoByKey(companyId: Long, key: String): TodoEntity?

	@Insert
	suspend fun insertTodo(t: TodoEntity): Long

	@Update
	suspend fun updateTodo(t: TodoEntity)

	@Query("SELECT contact.* FROM contact JOIN client ON client.id = contact.client_id WHERE client.company_id = :companyId AND contact.external_key = :key LIMIT 1")
	suspend fun contactByKey(companyId: Long, key: String): ContactEntity?

	@Insert
	suspend fun insertContact(c: ContactEntity): Long

	@Insert
	suspend fun insertPhone(p: ContactPhoneEntity)

	/*
	 * Closed entries of the company the plugin may send (B4): those with its
	 * entry fields, those started from an imported to-do, and those already
	 * sent (an edit or a deletion of blocks must reach the remote side).
	 */
	@Query(
		"SELECT * FROM time_entry WHERE company_id = :companyId AND status = 'CLOSED' AND (plugin_fields IS NOT NULL " +
			"OR todo_id IN (SELECT id FROM todo WHERE external_key IS NOT NULL) " +
			"OR id IN (SELECT entry_id FROM remote_record WHERE plugin_id = :pluginId)) ORDER BY id",
	)
	suspend fun entriesToSend(companyId: Long, pluginId: String): List<TimeEntryEntity>

	@Query("SELECT * FROM segment WHERE entry_id = :entryId ORDER BY start_utc_ms")
	suspend fun segmentsOf(entryId: Long): List<SegmentEntity>

	@Query("SELECT * FROM todo WHERE id = :id")
	suspend fun todo(id: Long): TodoEntity?

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

	@Query("UPDATE time_entry SET plugin_fields = :json WHERE id = :entryId")
	suspend fun setEntryFields(entryId: Long, json: String?)
}
