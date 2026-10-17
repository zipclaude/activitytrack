/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.quick

import android.app.PendingIntent
import android.content.Context
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.ui.Screen
import kotlinx.coroutines.flow.first

/*
 * One-tap toggle shared by the Quick Settings tile and the 1×1 widget
 * (DESIGN §10): PAUSE <-> WORK on the most recent ACTIVE entry (TRAVEL goes
 * to PAUSE). Without an ACTIVE entry the caller opens the start screen
 * (provisional choice 2026-10-17).
 */
object QuickToggle {
	/* Most recently changed ACTIVE entry (open entries are ordered by last update). */
	suspend fun target(container: AppContainer): TimeEntryEntity? =
		container.entries.openEntries.first().firstOrNull { it.status == EntryStatus.ACTIVE }

	/* True when an entry was toggled, false when there is none to toggle. */
	suspend fun toggle(container: AppContainer): Boolean {
		val e = target(container) ?: return false
		container.entries.apply(e.id, if (e.mode == SegmentKind.PAUSE) EntryCommand.WORK else EntryCommand.PAUSE)
		return true
	}

	fun startScreen(context: Context): PendingIntent = EntryLinks.openScreen(context, Screen.Start, START_REQUEST)

	private const val START_REQUEST = 5
}
