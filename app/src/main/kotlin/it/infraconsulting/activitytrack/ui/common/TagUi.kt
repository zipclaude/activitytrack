/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Tags of the master data (issue #133): name -> colour (null = none),
 * provided at the root (MainActivity). A name missing is a free tag; both
 * show grey.
 */
val LocalTagColors = staticCompositionLocalOf<Map<String, Int?>> { emptyMap() }

@Composable
fun tagColor(name: String): Color = rgb(LocalTagColors.current[name] ?: NO_COLOR)

/* at most [max] dots, then "+N": the top right of a card or a row */
@Composable
fun TagDots(tags: Collection<String>, modifier: Modifier = Modifier, max: Int = 4, size: Dp = 8.dp) {
	if (tags.isEmpty()) return
	val sorted = tags.sorted()
	Row(
		modifier.semantics { contentDescription = sorted.joinToString(" ") { "#$it" } },
		horizontalArrangement = Arrangement.spacedBy(3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		for (t in sorted.take(max))
			Box(Modifier.size(size).clip(CircleShape).background(tagColor(t)))
		if (sorted.size > max)
			Text("+${sorted.size - max}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

/* the whole tag in its colour, as in the entry sheet */
@Composable
fun TagLabel(name: String, modifier: Modifier = Modifier) {
	val bg = tagColor(name)
	Text(
		"#$name",
		style = MaterialTheme.typography.labelLarge,
		color = if (bg.luminance() > 0.5f) Color.Black else Color.White,
		modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
	)
}

/* Name, colour and archived of a tag of the master data; from the list or from a free tag of an entry. */
@Composable
fun TagDialog(
	title: String,
	name: String,
	color: Int?,
	archived: Boolean,
	error: it.infraconsulting.activitytrack.data.repo.ValidationException.Reason?,
	onDismiss: () -> Unit,
	onDelete: (() -> Unit)?,
	onSave: (String, Int?, Boolean) -> Unit,
) {
	var n by remember { mutableStateOf(name) }
	var c by remember { mutableStateOf(color) }
	var a by remember { mutableStateOf(archived) }
	EditDialog(title, error, onDismiss, { onSave(n, c, a) }, onDelete) {
		TextInput(androidx.compose.ui.res.stringResource(it.infraconsulting.activitytrack.R.string.field_name), n, { n = it })
		ColorPalette(c, swatch = 32.dp, inset = 0.dp) { c = it }
		SwitchRow(androidx.compose.ui.res.stringResource(it.infraconsulting.activitytrack.R.string.field_archived), a) { a = it }
	}
}
