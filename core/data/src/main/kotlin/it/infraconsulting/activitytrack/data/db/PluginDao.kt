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
}
