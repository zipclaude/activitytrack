/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.AbsenceKind
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import it.infraconsulting.activitytrack.ui.common.reasonText
import it.infraconsulting.activitytrack.ui.common.TimeField
import java.time.LocalDate
import java.time.LocalTime

class LateEntryViewModel(private val entries: EntryRepository) : ActionViewModel() {
	fun save(day: LocalDate, time: String, done: () -> Unit) = run(done) {
		val min = parseHhMm(time)?.takeIf { it < 24 * 60 }
			?: throw ValidationException(ValidationException.Reason.INVALID_TIME)
		entries.recordAbsence(day, AbsenceKind.LATE_ENTRY, min)
	}
}

/* "Late entry at…" from the day-start prompt: records only the LATE_ENTRY absence (owner decision 2026-10-17). */
@Composable
fun LateEntryScreen(epochDay: Long, onBack: () -> Unit) {
	val vm = appViewModel { LateEntryViewModel(it.entries) }
	val error by vm.error.collectAsState()
	val day = LocalDate.ofEpochDay(epochDay)
	var time by remember { mutableStateOf(LocalTime.now().let { formatMin(it.hour * 60 + it.minute) }) }

	ScreenScaffold(title = stringResource(R.string.late_entry_title), onBack = onBack) { padding ->
		Column(
			modifier = Modifier.padding(padding).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(stringResource(R.string.late_entry_hint, day.toString()))
			TimeField(stringResource(R.string.field_time), time, { time = it })
			error?.let { Text(reasonText(it), color = MaterialTheme.colorScheme.error) }
			Button(onClick = { vm.save(day, time, onBack) }, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.action_save))
			}
		}
	}
}
