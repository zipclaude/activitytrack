/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/* policy.json history (issue #48). */
@Dao
interface PolicyDao {
	@Insert
	suspend fun insert(r: PolicyRecordEntity): Long

	@Query("SELECT * FROM policy_record ORDER BY id DESC LIMIT 1")
	suspend fun latest(): PolicyRecordEntity?

	@Query("SELECT * FROM policy_record WHERE applied ORDER BY id DESC LIMIT 1")
	fun observeInForce(): Flow<PolicyRecordEntity?>

	@Query("SELECT * FROM policy_record WHERE applied ORDER BY id DESC LIMIT 1")
	suspend fun inForce(): PolicyRecordEntity?

	@Query("SELECT signer_cn FROM policy_record WHERE applied AND signer_cn IS NOT NULL ORDER BY id LIMIT 1")
	suspend fun pinnedCn(): String?

	@Query("SELECT * FROM policy_record ORDER BY id DESC LIMIT :limit")
	fun observeHistory(limit: Int): Flow<List<PolicyRecordEntity>>
}
