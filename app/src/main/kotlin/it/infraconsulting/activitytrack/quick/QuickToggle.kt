/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.quick

import android.app.PendingIntent
import android.content.Context
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.model.QuickAction
import it.infraconsulting.activitytrack.notify.isTicking
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.ui.home.resumeKindOf
import kotlinx.coroutines.flow.first

/*
 * One-tap toggle shared by the Quick Settings tile and the 1×1 widget
 * (DESIGN §10): PAUSE <-> WORK on the most recent running activity (one with a segment open) (TRAVEL goes
 * to PAUSE, a pause resumes the kind it interrupted). Without one the caller opens the start screen
 * (provisional choice 2026-10-17).
 */
object QuickToggle {
	/* Most recently changed running activity with a segment open (open entries are ordered by last update). */
	suspend fun target(container: AppContainer): TimeEntryEntity? =
		container.entries.openEntries.first().firstOrNull { it.isTicking }

	/* True when an entry was toggled, false when there is none to toggle. */
	suspend fun toggle(container: AppContainer): Boolean {
		val e = target(container) ?: return false
		container.entries.quick(e.id, action(e, container.entries.segments(e.id).first()))
		return true
	}

	/* the tap's command: from a pause "Riprendi", the kind open before it (ACTIVITY-MODEL.md 3), else a pause */
	internal fun action(e: TimeEntryEntity, segments: List<SegmentEntity>): QuickAction =
		if (e.mode == SegmentKind.PAUSE) QuickAction.valueOf(resumeKindOf(e, segments).name) else QuickAction.PAUSE

	fun startScreen(context: Context): PendingIntent = EntryLinks.openScreen(context, Screen.Start, START_REQUEST)

	private const val START_REQUEST = 5
}
