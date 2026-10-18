/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind

/*
 * Command colours and icons (owner request 2026-10-17): start/resume green,
 * stop red, pause yellow, suspend orange, travel blue; media icons for
 * play/pause/stop, a moon for suspend, a car for travel. Shared with the
 * notification actions.
 */
data class CommandStyle(@DrawableRes val icon: Int, val container: Color, val content: Color)

private val GREEN = Color(0xFF2E7D32)
private val RED = Color(0xFFC62828)
private val YELLOW = Color(0xFFF9A825)
private val ORANGE = Color(0xFFEF6C00)
private val BLUE = Color(0xFF1565C0)
/* dark icon on yellow: white would fail contrast */
private val ON_YELLOW = Color(0xFF212121)

fun commandStyle(cmd: EntryCommand): CommandStyle? = when (cmd) {
	EntryCommand.WORK -> CommandStyle(R.drawable.ic_cmd_play, GREEN, Color.White)
	EntryCommand.STOP -> CommandStyle(R.drawable.ic_cmd_stop, RED, Color.White)
	EntryCommand.PAUSE -> CommandStyle(R.drawable.ic_cmd_pause, YELLOW, ON_YELLOW)
	EntryCommand.SUSPEND -> CommandStyle(R.drawable.ic_cmd_suspend, ORANGE, Color.White)
	EntryCommand.TRAVEL -> CommandStyle(R.drawable.ic_cmd_travel, BLUE, Color.White)
	EntryCommand.COMPLETE, EntryCommand.REOPEN -> null
}

/* Round coloured icon button; [label] is the accessibility description. */
@Composable
fun CommandIconButton(style: CommandStyle, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
	FilledIconButton(
		onClick = onClick,
		modifier = modifier.size(56.dp),
		colors = IconButtonDefaults.filledIconButtonColors(containerColor = style.container, contentColor = style.content),
	) {
		Icon(painterResource(style.icon), contentDescription = label, modifier = Modifier.size(32.dp))
	}
}

/* Colour and icon of an entry state, matching the command that leads to it. */
fun stateStyle(status: EntryStatus, mode: SegmentKind?): CommandStyle? = commandStyle(
	when (status) {
		EntryStatus.ACTIVE -> when (mode) {
			SegmentKind.TRAVEL -> EntryCommand.TRAVEL
			SegmentKind.PAUSE -> EntryCommand.PAUSE
			else -> EntryCommand.WORK
		}
		EntryStatus.SUSPENDED -> EntryCommand.SUSPEND
		EntryStatus.TO_COMPLETE, EntryStatus.CLOSED -> EntryCommand.STOP
	},
)

/* Coloured pill with the state icon and text ("Working since 13:36"). */
@Composable
fun StatePill(style: CommandStyle, text: String) {
	Row(
		modifier = Modifier.clip(RoundedCornerShape(50)).background(style.container).padding(horizontal = 10.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(painterResource(style.icon), contentDescription = null, tint = style.content, modifier = Modifier.size(16.dp))
		Spacer(Modifier.width(6.dp))
		Text(text, color = style.content, style = MaterialTheme.typography.labelLarge)
	}
}

