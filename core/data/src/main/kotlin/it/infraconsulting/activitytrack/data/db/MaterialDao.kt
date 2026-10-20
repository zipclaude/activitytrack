/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/* Consumables (issue #41): the master data and the lines left with an entry. */
@Dao
interface MaterialDao {
	@Query("SELECT * FROM material ORDER BY archived, name COLLATE NOCASE")
	fun observeMaterials(): Flow<List<MaterialEntity>>

	@Insert
	suspend fun insertMaterial(m: MaterialEntity): Long

	@Update
	suspend fun updateMaterial(m: MaterialEntity)

	@Query("DELETE FROM material WHERE id = :id")
	suspend fun deleteMaterial(id: Long)

	@Query("SELECT * FROM entry_material WHERE entry_id = :entryId ORDER BY created_utc_ms, id")
	fun observeLines(entryId: Long): Flow<List<EntryMaterialEntity>>

	@Query("SELECT * FROM entry_material WHERE entry_id IN (:entryIds) ORDER BY entry_id, created_utc_ms, id")
	suspend fun linesOf(entryIds: List<Long>): List<EntryMaterialEntity>

	@Insert
	suspend fun insertLine(l: EntryMaterialEntity): Long

	@Update
	suspend fun updateLine(l: EntryMaterialEntity)

	@Query("DELETE FROM entry_material WHERE id = :id")
	suspend fun deleteLine(id: Long)
}
