/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand
import it.infraconsulting.activitytrack.domain.activity.ActivityDisplay
import it.infraconsulting.activitytrack.domain.activity.ActivityState
import it.infraconsulting.activitytrack.domain.activity.ActivityStateMachine
import it.infraconsulting.activitytrack.domain.activity.Displayed
import it.infraconsulting.activitytrack.domain.model.QuickAction
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.ui.common.snapshot
import java.time.Instant
import java.time.ZoneId

/*
 * What notifications, widgets and the tile know about a running activity
 * (ACTIVITY-MODEL.md 2 and 3): the displayed state from ActivityDisplay and
 * the buttons from ActivityStateMachine.allowed. There is no "suspend"
 * button: it is internal (another activity started).
 */

/* An activity with an open segment, the one that counts as "running" for the toggle, the tile and the ongoing notification. */
val TimeEntryEntity.isTicking: Boolean
	get() = status == it.infraconsulting.activitytrack.domain.activity.ActivityStatus.RUNNING && mode != null && deletedUtcMs == null

fun TimeEntryEntity.activityState() = ActivityState(
	kind, status, mode, closedUtcMs?.let(Instant::ofEpochMilli), deletedUtcMs?.let(Instant::ofEpochMilli),
)

/*
 * "In <state> dalle <since>" of a stored activity and its segments. Its
 * callers (notification, widgets, tile) show running activities only, whose
 * state no plugin field changes: missingRequired (issue #178) matters for a
 * closed one alone, and goes through RequiredFields where one can be shown.
 */
fun displayedState(e: TimeEntryEntity, segments: List<SegmentEntity>, now: Instant = Instant.now()): Displayed =
	ActivityDisplay.display(e.snapshot(segments), now, ZoneId.systemDefault())

/*
 * The buttons a notification or widget offers for [e], in order: the
 * commands the state machine allows, without suspend. TRAVEL while WORK is
 * open is a switch within the same activity: a widget cannot ask "Hai
 * completato l'attivita lavorativa?" (the Home does).
 */
fun ongoingActions(e: TimeEntryEntity): List<QuickAction> {
	val allowed = ActivityStateMachine.allowed(e.activityState())
	val found = allowed.mapNotNull { c ->
		when (c) {
			is ActivityCommand.Switch -> when (c.kind) {
				SegmentKind.PAUSE -> QuickAction.PAUSE
				SegmentKind.WORK -> QuickAction.WORK
				SegmentKind.TRAVEL -> QuickAction.TRAVEL
				SegmentKind.ABSENCE -> null
			}
			is ActivityCommand.Resume -> if (c.kind == SegmentKind.WORK) QuickAction.WORK else QuickAction.TRAVEL
			is ActivityCommand.Stop -> QuickAction.STOP
			else -> null
		}
	}.toSet()
	return listOf(QuickAction.PAUSE, QuickAction.WORK, QuickAction.TRAVEL, QuickAction.STOP).filter { it in found }
}
