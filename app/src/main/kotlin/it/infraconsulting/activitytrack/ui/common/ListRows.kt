/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R

/*
 * A small row of a list inside a form (issue #183): the Home row's shape
 * (a card with a round coloured icon, a title, a detail line, a value on
 * the right) a little smaller, so the attachments, expenses, materials,
 * segments and references of the detail read as lists. [onDelete] adds the
 * swipe toward the start that reveals "Elimina" (SwipeCard, as on the
 * Home); the caller asks for confirmation. A tap runs [onClick].
 */
@Composable
fun SmallListRow(
	title: String,
	subtitle: String? = null,
	trailing: String? = null,
	leading: @Composable () -> Unit,
	onClick: (() -> Unit)?,
	onDelete: (() -> Unit)? = null,
	dimmed: Boolean = false,
) {
	val deleteLabel = stringResource(R.string.action_delete)
	SwipeCard(
		toStart = onDelete?.let { listOf(SwipeAction(deleteLabel, R.drawable.ic_state_deleted, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, it)) }.orEmpty(),
		toEnd = emptyList(),
		/* a long swipe asks the confirmation at once */
		fullSwipe = true,
		shape = CardDefaults.shape,
		modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
	) {
		Card(
			modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
			colors = if (dimmed) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) else CardDefaults.cardColors(),
		) {
			Row(
				Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				leading()
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
					Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
					subtitle?.takeIf { it.isNotBlank() }?.let {
						Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
					}
				}
				trailing?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
			}
		}
	}
}

/* The round coloured icon at the start of a small row (26 dp, the Home row has 28). */
@Composable
fun RowIcon(@DrawableRes icon: Int, container: Color, content: Color = Color.White) {
	Box(Modifier.size(26.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
		Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
	}
}

/* The "+" that adds what a field holds (issue #183): the icon used beside the pickers, not the word "Aggiungi". */
@Composable
fun AddIconButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
	IconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(R.drawable.ic_add), contentDescription = label) }
}
