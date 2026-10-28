/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import kotlinx.coroutines.flow.Flow

/* The sync bookkeeping as the app sees it (issues #111, #113). */
class SyncRepository(db: ActivityTrackDatabase) {
	private val dao = db.syncDao()

	fun observePending(): Flow<Int> = dao.observePending()

	suspend fun setServer(url: String) = dao.setServer(url)

	suspend fun forgetServer() = dao.forgetServer()
}
