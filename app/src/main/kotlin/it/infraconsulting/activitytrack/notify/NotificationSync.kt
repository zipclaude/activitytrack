/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import android.app.NotificationManager
import android.content.Context
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.location.LocationService
import it.infraconsulting.activitytrack.quick.QuickWidgets
import it.infraconsulting.activitytrack.quick.ToggleTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/*
 * Keeps the per-entry notifications in line with the database for the whole
 * process lifetime: every change of the open entries (from the UI, a
 * notification action or a reminder) re-posts or cancels them.
 */
class NotificationSync(
	private val context: Context,
	private val container: AppContainer,
	private val scope: CoroutineScope,
) {
	/* Bumped to re-post without a database change (e.g. permission just granted). */
	private val tick = MutableStateFlow(0)

	fun refresh() {
		tick.value++
	}

	fun start() {
		scope.launch {
			/* entries notified before a process restart are cancelled if no longer open */
			var previous = context.getSystemService(NotificationManager::class.java).activeNotifications
				.map { it.id / 16L }
				.filter { it > 0 }
				.toSet()
			val fallback = context.getString(R.string.app_name)
			combine(
				container.entries.openEntries,
				container.entries.openEntrySegments,
				container.masterData.clients,
				container.masterData.companies,
			) { entries, segments, clients, companies ->
				val cl = clients.associateBy { it.id }
				val co = companies.associateBy { it.id }
				val bySeg = segments.groupBy { it.entryId }
				entries.map { e ->
					val title = e.clientId?.let { cl[it]?.name } ?: e.companyId?.let { co[it]?.name } ?: fallback
					EntryInfo(e, title, bySeg[e.id].orEmpty())
				}
			}.combine(tick) { infos, _ -> infos }.conflate().collect { infos ->
				EntryNotifications.sync(context, infos, previous)
				LocationService.sync(context, infos)
				ToggleTileService.refresh(context)
				QuickWidgets.refresh(context)
				previous = infos.map { it.id }.toSet()
			}
		}
	}
}
