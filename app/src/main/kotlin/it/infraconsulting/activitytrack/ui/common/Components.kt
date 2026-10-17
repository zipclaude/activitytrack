/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import java.text.Normalizer

/* Top bar with optional back button; no icon library, plain glyphs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
	title: String,
	onBack: (() -> Unit)?,
	actions: @Composable RowScope.() -> Unit = {},
	content: @Composable (PaddingValues) -> Unit,
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
				navigationIcon = {
					if (onBack != null) {
						val desc = stringResource(R.string.action_back)
						IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = desc }) {
							Text("←", style = MaterialTheme.typography.titleLarge)
						}
					}
				},
				actions = actions,
			)
		},
		content = content,
	)
}

@Composable
fun EditDialog(
	title: String,
	error: Reason?,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
	onDelete: (() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		/* the dialog handles insets itself so the buttons stay above the keyboard */
		properties = DialogProperties(decorFitsSystemWindows = false),
		modifier = Modifier.safeDrawingPadding(),
		title = { Text(title) },
		text = {
			Column(
				modifier = Modifier.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				content()
				if (error != null)
					Text(reasonText(error), color = MaterialTheme.colorScheme.error)
			}
		},
		confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_save)) } },
		dismissButton = {
			Row {
				if (onDelete != null)
					TextButton(onClick = onDelete) {
						Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
					}
				TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
			}
		},
	)
}

@Composable
fun TextInput(
	label: String,
	value: String,
	onChange: (String) -> Unit,
	keyboardType: KeyboardType = KeyboardType.Text,
	isError: Boolean = false,
	singleLine: Boolean = true,
) {
	OutlinedTextField(
		value = value,
		onValueChange = onChange,
		label = { Text(label) },
		singleLine = singleLine,
		isError = isError,
		keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
		modifier = Modifier.fillMaxWidth(),
	)
}

/*
 * Editable combo box (owner request 2026-10-17): typing filters the options
 * (case and accents ignored), the dropdown lists the matches in the given
 * order. Leaving the field restores the selected label if nothing was picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SearchableChoiceField(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
	val selectedText = options.firstOrNull { it.first == selected }?.second.orEmpty()
	var text by remember(selectedText) { mutableStateOf(selectedText) }
	var expanded by remember { mutableStateOf(false) }
	val query = fold(text)
	/* the full list while the field shows the current choice, matches once the user types */
	val shown = if (text == selectedText) options else options.filter { fold(it.second).contains(query) }
	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = text,
			onValueChange = { text = it; expanded = true },
			singleLine = true,
			label = { Text(label) },
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
			modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
				.onFocusChanged { if (!it.isFocused) text = selectedText },
		)
		ExposedDropdownMenu(expanded = expanded && shown.isNotEmpty(), onDismissRequest = { expanded = false }) {
			shown.forEach { (value, optionText) ->
				DropdownMenuItem(
					text = { Text(optionText) },
					onClick = {
						onSelect(value)
						text = optionText
						expanded = false
					},
				)
			}
		}
	}
}

/* Lower case without diacritics, for searching names ("Négoce" ~ "nego"). */
fun fold(s: String): String =
	Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/* Read-only dropdown; [options] pairs a value with its label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceField(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
	var expanded by remember { mutableStateOf(false) }
	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
			onValueChange = {},
			readOnly = true,
			label = { Text(label) },
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
			modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
		)
		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			options.forEach { (value, text) ->
				DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
			}
		}
	}
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(label, modifier = Modifier.weight(1f))
		Switch(checked = checked, onCheckedChange = onChange)
	}
}

@Composable
fun ClickableRow(headline: String, supporting: String? = null, onClick: () -> Unit) {
	ListItem(
		headlineContent = { Text(headline) },
		supportingContent = supporting?.let { { Text(it) } },
		modifier = Modifier.clickable(onClick = onClick),
	)
}

@Composable
fun SectionHeader(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.titleSmall,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
	)
}

@Composable
fun EmptyText(text: String = stringResource(R.string.no_items)) {
	Text(
		text,
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(16.dp),
	)
}

@Composable
fun AddButton(onClick: () -> Unit) {
	TextButton(onClick = onClick) { Text(stringResource(R.string.action_add)) }
}

@Composable
fun reasonText(reason: Reason): String = stringResource(
	when (reason) {
		Reason.BLANK_NAME -> R.string.err_blank_name
		Reason.DUPLICATE_NAME -> R.string.err_duplicate_name
		Reason.INVALID_COORDINATES -> R.string.err_invalid_coordinates
		Reason.INVALID_RADIUS -> R.string.err_invalid_radius
		Reason.INVALID_TIME_RANGE -> R.string.err_invalid_time_range
		Reason.OVERLAPPING_INTERVALS -> R.string.err_overlapping_intervals
		Reason.CLIENT_COMPANY_LOCKED -> R.string.err_client_company_locked
		Reason.IN_USE -> R.string.err_in_use
		Reason.COMPLETION_INCOMPLETE -> R.string.err_completion_incomplete
		Reason.CLIENT_REQUIRED -> R.string.err_client_required
		Reason.BLANK_VALUE -> R.string.err_blank_value
		Reason.INVALID_SEGMENT -> R.string.err_invalid_segment
		Reason.OVERLAPPING_SEGMENTS -> R.string.err_overlapping_segments
		Reason.INVALID_DATE -> R.string.err_invalid_date
		Reason.INVALID_TIME -> R.string.err_invalid_time
		else -> R.string.err_generic
	},
)

/* "HH:MM" from minutes since midnight. */
fun formatMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

/* Minutes since midnight from "H", "HH:MM" or "HH.MM"; 24:00 allowed as end of day. */
fun parseHhMm(s: String): Int? {
	val parts = s.trim().split(':', '.')
	val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
	val m = if (parts.size > 1) parts[1].toIntOrNull() ?: return null else 0
	if (parts.size > 2 || m !in 0..59 || h !in 0..24 || (h == 24 && m != 0))
		return null
	return h * 60 + m
}

/* Decimal number accepting both '.' and ',' as separator. */
fun parseDecimal(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
