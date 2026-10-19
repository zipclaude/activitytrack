/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/* Data of the management systems reached through their APIs (M24, Antares). */
@Dao
interface RemoteDao {
	@Upsert
	suspend fun upsertPortfolios(list: List<RemotePortfolioEntity>)

	@Query("UPDATE remote_portfolio SET active = 0 WHERE company_id = :companyId AND id NOT IN (:keep)")
	suspend fun deactivateOtherPortfolios(companyId: Long, keep: List<String>)

	@Query("SELECT * FROM remote_portfolio WHERE company_id = :companyId ORDER BY active DESC, name COLLATE NOCASE")
	fun observePortfolios(companyId: Long): Flow<List<RemotePortfolioEntity>>

	@Query("SELECT * FROM remote_portfolio WHERE id = :id")
	suspend fun portfolio(id: String): RemotePortfolioEntity?

	@Query("DELETE FROM remote_activity_type WHERE portfolio_id = :portfolioId")
	suspend fun clearTypes(portfolioId: String)

	@Upsert
	suspend fun upsertTypes(list: List<RemoteActivityTypeEntity>)

	@Query("SELECT * FROM remote_activity_type WHERE portfolio_id = :portfolioId ORDER BY sort_order, name COLLATE NOCASE")
	fun observeTypes(portfolioId: String): Flow<List<RemoteActivityTypeEntity>>

	@Query("SELECT * FROM remote_activity_type WHERE portfolio_id = :portfolioId ORDER BY sort_order, name COLLATE NOCASE")
	suspend fun types(portfolioId: String): List<RemoteActivityTypeEntity>

	@Query("SELECT * FROM remote_activity WHERE entry_id = :entryId")
	suspend fun sent(entryId: Long): List<RemoteActivityEntity>

	@Query("SELECT * FROM remote_activity WHERE entry_id = :entryId")
	fun observeSent(entryId: Long): Flow<List<RemoteActivityEntity>>

	@Upsert
	suspend fun upsertSent(row: RemoteActivityEntity)

	@Query("DELETE FROM remote_activity WHERE entry_id = :entryId AND day = :day AND idx = :idx")
	suspend fun deleteSent(entryId: Long, day: Long, idx: Int)

	/* closed entries of a company with Antares data (a portfolio, or a to-do from a ticket) */
	@Query(
		"SELECT * FROM time_entry WHERE company_id = :companyId AND status = 'CLOSED' " +
			"AND (remote_portfolio_id IS NOT NULL OR todo_id IN (SELECT id FROM todo WHERE remote_portfolio_id IS NOT NULL))",
	)
	suspend fun entriesToSync(companyId: Long): List<TimeEntryEntity>

	@Query("SELECT * FROM segment WHERE entry_id = :entryId ORDER BY start_utc_ms")
	suspend fun segmentsOf(entryId: Long): List<SegmentEntity>

	@Query("UPDATE time_entry SET remote_portfolio_id = :portfolioId, remote_activity_type_id = :typeId, remote_included = :included WHERE id = :entryId")
	suspend fun setEntryRemote(entryId: Long, portfolioId: String?, typeId: String?, included: Boolean?)

		/* the last portfolio and type used on a client: the proposal for the next entry */
	@Query(
		"SELECT * FROM time_entry WHERE client_id = :clientId AND remote_portfolio_id IS NOT NULL " +
			"ORDER BY created_utc_ms DESC LIMIT 1",
	)
	suspend fun lastOnClient(clientId: Long): TimeEntryEntity?
}
