/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * How a dialog button looks (issue #186): the dialogs use buttons like the
 * "Annulla" / "Salva" at the bottom of the detail, not bare texts.
 * PRIMARY filled (Salva, Conferma, Apri…), SECONDARY outlined (Annulla…),
 * DANGER filled in the error colour (the "Elimina" that confirms a
 * deletion), DANGER_OUTLINED outlined in the error colour (an "Elimina"
 * beside a Salva).
 */
enum class DialogButtonKind { PRIMARY, SECONDARY, DANGER, DANGER_OUTLINED }

/* One button of a dialog; [container] and [content] override the colours of a PRIMARY one (e.g. "Riapri" in the segment's colour). */
data class DialogAction(
	val label: String,
	val kind: DialogButtonKind = DialogButtonKind.SECONDARY,
	val enabled: Boolean = true,
	val container: Color? = null,
	val content: Color? = null,
	val onClick: () -> Unit,
)

/*
 * The rows the buttons go in (pure, unit-tested): one row up to [perRow],
 * else rows of [perRow] counted from the end, so the last row holds the
 * final pair (Annulla, Salva) and an odd one out (Elimina) sits alone above.
 */
fun <T> dialogButtonRows(actions: List<T>, perRow: Int = 2): List<List<T>> {
	if (actions.size <= perRow)
		return if (actions.isEmpty()) emptyList() else listOf(actions)
	val first = actions.size % perRow
	val head = if (first == 0) emptyList() else listOf(actions.take(first))
	return head + actions.drop(first).chunked(perRow)
}

/* The buttons of a dialog, equal width, in the rows of [dialogButtonRows]. */
@Composable
fun DialogButtons(actions: List<DialogAction>, modifier: Modifier = Modifier, perRow: Int = 2) {
	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		for (row in dialogButtonRows(actions, perRow))
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				for (a in row)
					DialogButton(a, Modifier.weight(1f))
			}
	}
}

@Composable
fun DialogButton(a: DialogAction, modifier: Modifier = Modifier) {
	val m = modifier.heightIn(min = 48.dp)
	val pad = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
	val error = MaterialTheme.colorScheme.error
	val label: @Composable () -> Unit = {
		Text(a.label, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
	}
	when (a.kind) {
		DialogButtonKind.PRIMARY -> Button(
			onClick = a.onClick, enabled = a.enabled, modifier = m, contentPadding = pad,
			colors = if (a.container != null)
				ButtonDefaults.buttonColors(containerColor = a.container, contentColor = a.content ?: Color.White)
			else ButtonDefaults.buttonColors(),
		) { label() }
		DialogButtonKind.SECONDARY -> OutlinedButton(onClick = a.onClick, enabled = a.enabled, modifier = m, contentPadding = pad) { label() }
		DialogButtonKind.DANGER -> Button(
			onClick = a.onClick, enabled = a.enabled, modifier = m, contentPadding = pad,
			colors = ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError),
		) { label() }
		DialogButtonKind.DANGER_OUTLINED -> OutlinedButton(
			onClick = a.onClick, enabled = a.enabled, modifier = m, contentPadding = pad,
			colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
			border = BorderStroke(1.dp, if (a.enabled) error else error.copy(alpha = 0.38f)),
		) { label() }
	}
}

/*
 * An AlertDialog whose actions are [DialogButtons]: the shared shape of the
 * questions and confirmations (issue #186).
 */
@Composable
fun ButtonsDialog(
	onDismiss: () -> Unit,
	actions: List<DialogAction>,
	title: String? = null,
	text: (@Composable () -> Unit)? = null,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = title?.let { t -> { Text(t) } },
		text = text,
		confirmButton = { DialogButtons(actions) },
	)
}
