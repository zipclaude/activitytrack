/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.RemoteActivityEntity
import it.infraconsulting.activitytrack.data.db.RemoteActivityTypeEntity
import it.infraconsulting.activitytrack.data.db.RemotePortfolioEntity
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.db.TodoEntity

/*
 * Local side of the API connectors (M24, Antares): cached services,
 * projects and activity types, the entries' Antares data, the to-dos from
 * tickets and what was sent.
 */
class RemoteRepository(private val db: ActivityTrackDatabase) {
	private val dao = db.remoteDao()
	private val todos = db.todoDao()

	fun portfolios(companyId: Long) = dao.observePortfolios(companyId)
	fun types(portfolioId: String) = dao.observeTypes(portfolioId)
	fun sentOf(entryId: Long) = dao.observeSent(entryId)

	suspend fun portfolio(id: String): RemotePortfolioEntity? = dao.portfolio(id)
	suspend fun typeList(portfolioId: String): List<RemoteActivityTypeEntity> = dao.types(portfolioId)

	/* every portfolio of the company as now in the remote system; the missing ones become inactive */
	suspend fun replacePortfolios(companyId: Long, list: List<RemotePortfolioEntity>) = db.withTransaction {
		dao.upsertPortfolios(list)
		dao.deactivateOtherPortfolios(companyId, list.map { it.id }.ifEmpty { listOf("") })
	}

	suspend fun replaceTypes(portfolioId: String, list: List<RemoteActivityTypeEntity>) = db.withTransaction {
		dao.clearTypes(portfolioId)
		dao.upsertTypes(list)
	}

	suspend fun clientsOf(companyId: Long): List<ClientEntity> = db.masterDataDao().allClients().filter { it.companyId == companyId }

	suspend fun todoByKey(companyId: Long, key: String): TodoEntity? = todos.byExternalKey(companyId, key)
	suspend fun todo(id: Long): TodoEntity? = todos.byId(id)
	suspend fun saveTodo(t: TodoEntity): Long =
		if (t.id == 0L) todos.insert(t) else t.id.also { todos.update(t) }

	suspend fun entriesToSync(companyId: Long): List<TimeEntryEntity> = dao.entriesToSync(companyId)
	suspend fun segments(entryId: Long): List<SegmentEntity> = dao.segmentsOf(entryId)
	suspend fun sent(entryId: Long): List<RemoteActivityEntity> = dao.sent(entryId)
	suspend fun recordSent(row: RemoteActivityEntity) = dao.upsertSent(row)
	suspend fun forgetSent(entryId: Long, day: Long, idx: Int) = dao.deleteSent(entryId, day, idx)

	/* the entry's service or project, activity type and "included in the contract" */
	suspend fun setEntryRemote(entryId: Long, portfolioId: String?, typeId: String?, included: Boolean?) =
		dao.setEntryRemote(entryId, portfolioId, typeId, included)

	/* proposal for a new entry: what the last entry on the client used */
	suspend fun lastOnClient(clientId: Long): TimeEntryEntity? = dao.lastOnClient(clientId)
}
