/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Date and time fields with the Material pickers (M10, owner request
 * 2026-10-18). The value stays the text the screens already parse
 * ("yyyy-mm-dd", "hh:mm"); the field shows it formatted and opens the
 * picker on tap. [clearable] adds a clear button for optional values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit, clearable: Boolean = false, error: String? = null, importance: Importance? = null) {
	var open by remember { mutableStateOf(false) }
	val locale = LocalConfiguration.current.locales[0]
	val date = parseDate(value)
	PickerTextField(
		label = label,
		shown = date?.let { dateText(it, locale) } ?: value,
		icon = R.drawable.ic_calendar,
		onOpen = { open = true },
		onClear = if (clearable && value.isNotBlank()) ({ onChange("") }) else null,
		error = error,
		importance = importance,
	)
	if (open) {
		/* the picker works in UTC midnights: convert with UTC, never the phone zone */
		val state = rememberDatePickerState(
			initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
		)
		DatePickerDialog(
			onDismissRequest = { open = false },
			confirmButton = {
				TextButton(onClick = {
					state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
					open = false
				}) { Text(stringResource(R.string.action_ok)) }
			},
			dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
		) { DatePicker(state = state) }
	}
}

/*
 * [endOfDay]: a picked 00:00 means 24:00 (the end of a working interval).
 * A tap opens the 5-minute grid (backlog 3); its "Exact time" opens the
 * clock. [onOpen] replaces the grid, e.g. with one choosing a whole range.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
	label: String,
	value: String,
	onChange: (String) -> Unit,
	clearable: Boolean = false,
	endOfDay: Boolean = false,
	error: String? = null,
	importance: Importance? = null,
	onOpen: (() -> Unit)? = null,
) {
	var grid by remember { mutableStateOf(false) }
	var open by remember { mutableStateOf(false) }
	val min = parseHhMm(value)
	PickerTextField(
		label = label,
		shown = min?.let(::formatMin) ?: value,
		icon = R.drawable.ic_clock,
		onOpen = onOpen ?: { grid = true },
		onClear = if (clearable && value.isNotBlank()) ({ onChange("") }) else null,
		error = error,
		importance = importance,
	)
	if (grid)
		TimeGridSheet(
			title = label,
			/* the exact minute: OK without a tap keeps it */
			initial = min?.takeIf { it < 1440 }?.let { it..it },
			range = false,
			onExact = { grid = false; open = true },
			onDismiss = { grid = false },
		) { start, _ ->
			grid = false
			onChange(formatMin(if (endOfDay && start == 0) 1440 else start))
		}
	if (open) {
		val start = (min ?: java.time.LocalTime.now().let { it.hour * 60 + it.minute }) % 1440
		val state = rememberTimePickerState(initialHour = start / 60, initialMinute = start % 60, is24Hour = true)
		AlertDialog(
			onDismissRequest = { open = false },
			confirmButton = {
				TextButton(onClick = {
					val picked = state.hour * 60 + state.minute
					onChange(formatMin(if (endOfDay && picked == 0) 1440 else picked))
					open = false
				}) { Text(stringResource(R.string.action_ok)) }
			},
			dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
			text = { TimePicker(state = state) },
		)
	}
}

/* "lun 19/10/2026" in the app language. */
fun dateText(d: LocalDate, locale: Locale): String =
	d.format(DateTimeFormatter.ofPattern("EEE dd/MM/yyyy", locale))

/* A read-only text field that opens a picker when tapped anywhere; [error] shows under it. */
@Composable
private fun PickerTextField(label: String, shown: String, icon: Int, onOpen: () -> Unit, onClear: (() -> Unit)?, error: String?, importance: Importance?) {
	val source = remember { MutableInteractionSource() }
	LaunchedEffect(source) {
		source.interactions.collect { if (it is PressInteraction.Release) onOpen() }
	}
	OutlinedTextField(
		value = shown,
		onValueChange = {},
		readOnly = true,
		label = { Text(label) },
		singleLine = true,
		interactionSource = source,
		isError = error != null,
		supportingText = error?.let { { Text(it) } },
		colors = importanceColors(importance, shown.isNotBlank()),
		trailingIcon = {
			Row {
				onClear?.let {
					IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.action_clear)) }
				}
				IconButton(onClick = onOpen) { Icon(painterResource(icon), contentDescription = label) }
			}
		},
		modifier = Modifier.fillMaxWidth(),
	)
}
