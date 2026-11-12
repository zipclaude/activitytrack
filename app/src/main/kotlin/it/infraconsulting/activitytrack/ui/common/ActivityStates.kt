/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivitySegment
import it.infraconsulting.activitytrack.domain.activity.ActivitySnapshot
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import java.time.Instant
import java.time.LocalDate

/*
 * The one table of icon, colours and name of every displayed state
 * (ACTIVITY-MODEL.md 2.2, owner 2026-11-11): every screen, widget and
 * notification takes them from here.
 */
private val WHITE = Color(0xFFF5F5F5)
private val GREY = Color(0xFF757575)
private val BROWN = Color(0xFF6D4C41)
private val GREENISH = Color(0xFF66BB6A)
private val REDDISH = Color(0xFFE57373)
private val FUCHSIA = Color(0xFFD81B60)
private val DARK = Color(0xFF212121)

fun displayStyle(state: DisplayState): CommandStyle = when (state) {
	DisplayState.DELETED -> CommandStyle(R.drawable.ic_state_deleted, GREY, Color.White)
	DisplayState.ABSENCE -> CommandStyle(R.drawable.ic_state_absence, FUCHSIA, Color.White)
	DisplayState.LATE -> CommandStyle(R.drawable.ic_state_late, BROWN, Color.White)
	DisplayState.PLANNED -> CommandStyle(R.drawable.ic_clock, GREY, Color.White)
	/* dark icon on white: white on white would vanish */
	DisplayState.TODO -> CommandStyle(R.drawable.ic_state_todo, WHITE, DARK)
	DisplayState.WORK -> CommandStyle(R.drawable.ic_cmd_play, GREEN, Color.White)
	DisplayState.TRAVEL -> CommandStyle(R.drawable.ic_cmd_travel, BLUE, Color.White)
	DisplayState.PAUSE -> CommandStyle(R.drawable.ic_cmd_pause, YELLOW, DARK)
	DisplayState.SUSPENDED -> CommandStyle(R.drawable.ic_cmd_suspend, ORANGE, Color.White)
	DisplayState.DONE -> CommandStyle(R.drawable.ic_done, GREENISH, DARK)
	DisplayState.INCOMPLETE -> CommandStyle(R.drawable.ic_cmd_stop, REDDISH, DARK)
}

@StringRes
fun displayName(state: DisplayState): Int = when (state) {
	DisplayState.DELETED -> R.string.state_deleted
	DisplayState.ABSENCE -> R.string.state_absence
	DisplayState.LATE -> R.string.state_late
	DisplayState.PLANNED -> R.string.state_planned
	DisplayState.TODO -> R.string.state_todo
	DisplayState.WORK -> R.string.state_work
	DisplayState.TRAVEL -> R.string.state_travel
	DisplayState.PAUSE -> R.string.state_pause
	DisplayState.SUSPENDED -> R.string.state_suspended
	DisplayState.DONE -> R.string.state_done
	DisplayState.INCOMPLETE -> R.string.state_incomplete
}

/* What ActivityDisplay.display needs of a stored activity and its segments. */
fun TimeEntryEntity.snapshot(segments: List<SegmentEntity>, missingRequired: Boolean = false) = ActivitySnapshot(
	kind = kind,
	status = status,
	segments = segments.map { s ->
		ActivitySegment(s.kind, Instant.ofEpochMilli(s.startUtcMs), s.endUtcMs?.let(Instant::ofEpochMilli), s.planned)
	},
	deletedAt = deletedUtcMs?.let(Instant::ofEpochMilli),
	closedAt = closedUtcMs?.let(Instant::ofEpochMilli),
	hasClient = clientId != null,
	plan = TodoPlan.of(planEpochDay, planStartMin, durationMin),
	dueDay = dueEpochDay?.let(LocalDate::ofEpochDay),
	dueMin = dueTimeMin,
	missingRequired = missingRequired,
)
