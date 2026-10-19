/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R

/*
 * Company and client colours (M12, owner 2026-10-18): twelve hues told
 * apart on the dark theme, stored as 0xRRGGBB. The names describe them
 * for accessibility.
 */
/*
 * Automatic colour of a company without a chosen one (the agenda's, since
 * M6): every view shows the same colour for it (owner, 2026-10-19: Home
 * cards had no band when no colour was chosen).
 */
private val AUTO = intArrayOf(0x1565C0, 0x2E7D32, 0xEF6C00, 0x6A1B9A, 0x00838F, 0xAD1457, 0x4E342E, 0x546E7A)

fun companyColor(companyId: Long?, chosen: Int?): Int? =
	chosen ?: companyId?.let { AUTO[(it % AUTO.size).toInt()] }

val PALETTE: List<Pair<Int, Int>> = listOf(
	0x3D7BEA to R.string.color_blue, 0x00A3A3 to R.string.color_teal, 0x2E9D4E to R.string.color_green,
	0x8BB82D to R.string.color_lime, 0xF2B705 to R.string.color_yellow, 0xE8862A to R.string.color_orange,
	0xD64545 to R.string.color_red, 0xC2185B to R.string.color_magenta, 0x8E5CD9 to R.string.color_purple,
	0x5B6BD6 to R.string.color_indigo, 0x8D6E63 to R.string.color_brown, 0x78909C to R.string.color_grey,
)

/* 0xRRGGBB -> opaque Compose colour */
fun rgb(c: Int): Color = Color(0xFF000000.toInt() or c)

/* The colour row of a company or client screen; null = no colour. */
@Composable
fun ColorPalette(selected: Int?, onPick: (Int?) -> Unit) {
	Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			for ((c, name) in PALETTE) {
				val on = c == selected
				val label = stringResource(name)
				Box(
					Modifier.size(40.dp).clip(CircleShape)
						.border(if (on) 3.dp else 0.dp, if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
						.padding(if (on) 5.dp else 0.dp).clip(CircleShape).background(rgb(c))
						.selectable(selected = on, role = Role.RadioButton) { onPick(c) }
						.semantics { contentDescription = label },
				)
			}
		}
		OutlinedButton(onClick = { onPick(null) }, enabled = selected != null) { Text(stringResource(R.string.color_none)) }
	}
}

/*
 * A card with an optional colour band at its start edge (M12): the band
 * marks the company, or the client when one company is shown.
 */
@Composable
fun Banded(color: Int?, content: @Composable () -> Unit) {
	if (color == null) {
		content()
		return
	}
	Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
		Box(Modifier.width(6.dp).fillMaxHeight().background(rgb(color)))
		Box(Modifier.weight(1f)) { content() }
	}
}
