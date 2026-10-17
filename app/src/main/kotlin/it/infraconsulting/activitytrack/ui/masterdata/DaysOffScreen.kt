/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.DayOffEntity
import it.infraconsulting.activitytrack.ui.common.AddButton
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.TextInput
import java.time.LocalDate

/*
 * Manual days off (patron saint, leave): a date and an optional note. They
 * suppress the day-start prompt like national holidays.
 */
@Composable
fun DaysOffScreen(onBack: () -> Unit) {
	val vm = masterDataViewModel()
	val days by vm.daysOff.collectAsState()
	val error by vm.error.collectAsState()
	var editing by remember { mutableStateOf<DayOffEntity?>(null) }

	ScreenScaffold(
		title = stringResource(R.string.md_days_off),
		onBack = onBack,
		actions = { AddButton { vm.clearError(); editing = DayOffEntity(dateEpochDay = Long.MIN_VALUE) } },
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			if (days.isEmpty())
				item { EmptyText() }
			items(days, key = { it.dateEpochDay }) { d ->
				ClickableRow(LocalDate.ofEpochDay(d.dateEpochDay).toString(), d.note) { vm.clearError(); editing = d }
			}
		}
	}
	editing?.let { d ->
		val isNew = d.dateEpochDay == Long.MIN_VALUE
		var date by remember { mutableStateOf(if (isNew) LocalDate.now().toString() else LocalDate.ofEpochDay(d.dateEpochDay).toString()) }
		var note by remember { mutableStateOf(d.note.orEmpty()) }
		val delete = if (!isNew) confirmDelete(date) { vm.delete(d) { editing = null } } else null
		EditDialog(
			title = stringResource(R.string.day_off_edit),
			error = error,
			onDismiss = { editing = null },
			onConfirm = {
				/* a changed date is a new row: drop the old one once the new one is saved */
				vm.saveDayOff(date, note) {
					if (!isNew && LocalDate.ofEpochDay(d.dateEpochDay).toString() != date.trim())
						vm.delete(d) {}
					editing = null
				}
			},
			onDelete = delete,
		) {
			TextInput(stringResource(R.string.field_date), date, { date = it })
			TextInput(stringResource(R.string.field_note), note, { note = it })
		}
	}
}
