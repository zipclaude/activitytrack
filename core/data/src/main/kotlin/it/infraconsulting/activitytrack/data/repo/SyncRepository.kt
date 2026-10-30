/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.SyncStateEntity
import it.infraconsulting.activitytrack.data.sync.SyncDb
import it.infraconsulting.activitytrack.data.sync.SyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/* The sync bookkeeping as the app sees it (issues #111, #113, #114). */
class SyncRepository(private val db: ActivityTrackDatabase) : SyncDb {
	private val dao = db.syncDao()

	fun observePending(): Flow<Int> = dao.observePending()

	fun observeState(): Flow<SyncStateEntity?> = dao.observeState()

	suspend fun state(): SyncStateEntity? = dao.state()

	suspend fun setServer(url: String) = dao.setServer(url)

	suspend fun forgetServer() = dao.forgetServer()

	/* one transaction of the engine, on the connection Room uses (its flows see the writes) */
	override suspend fun <T> run(block: (SyncStore) -> T): T = withContext(Dispatchers.IO) {
		db.runInTransaction<T> { block(SyncStore(ActivityTrackDatabase.SupportSql(db.openHelper.writableDatabase))) }
	}
}
