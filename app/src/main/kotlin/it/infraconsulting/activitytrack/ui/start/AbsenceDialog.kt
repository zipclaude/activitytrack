/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.AbsenceRequest
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.DateField
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.PolicyField
import it.infraconsulting.activitytrack.ui.common.RangeField
import it.infraconsulting.activitytrack.ui.common.TimeGridSheet
import it.infraconsulting.activitytrack.ui.common.fieldLabel
import it.infraconsulting.activitytrack.ui.common.fieldShown
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.parseDate
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import java.time.LocalDate
import kotlinx.coroutines.launch

@StringRes
fun absenceKindName(k: AbsenceKind): Int = when (k) {
	AbsenceKind.ABSENCE -> R.string.absence_kind_absence
	AbsenceKind.HOLIDAY -> R.string.absence_kind_holiday
	AbsenceKind.LEAVE -> R.string.absence_kind_leave
	AbsenceKind.SICK -> R.string.absence_kind_sick
}

/*
 * "Assenza" (ACTIVITY-MODEL.md 3.1): Azienda (none = every company), the
 * kind (the description: Assenza, Ferie, Permesso, Malattia), Note, from
 * and to day, an optional start and end time (a "permesso"). Without times
 * the segments follow the working hours of each working day.
 */
@Composable
fun AbsenceDialog(onClose: () -> Unit) {
	val c = (LocalContext.current.applicationContext as ActivityTrackApp).container
	val scope = rememberCoroutineScope()
	val companies by c.masterData.companies.collectAsState(emptyList())
	var error by remember { mutableStateOf<Reason?>(null) }
	var companyId by remember { mutableStateOf<Long?>(null) }
	var kind by remember { mutableStateOf(AbsenceKind.ABSENCE) }
	var notes by remember { mutableStateOf("") }
	var from by remember { mutableStateOf(LocalDate.now().toString()) }
	var to by remember { mutableStateOf("") }
	var start by remember { mutableStateOf("") }
	var end by remember { mutableStateOf("") }
	var grid by remember { mutableStateOf(false) }

	EditDialog(
		title = stringResource(R.string.create_absence_title),
		error = error,
		onDismiss = onClose,
		onConfirm = confirm@{
			val f = parseDate(from)
			val t = if (to.isBlank()) f else parseDate(to)
			if (f == null || t == null) {
				error = Reason.INVALID_DATE
				return@confirm
			}
			/* both times or none */
			val a = parseHhMm(start)?.takeIf { it < 1440 }
			val b = parseHhMm(end)
			if ((start.isBlank() != end.isBlank()) || (start.isNotBlank() && (a == null || b == null || b <= a))) {
				error = Reason.INVALID_TIME_RANGE
				return@confirm
			}
			scope.launch {
				try {
					c.entries.createAbsence(
						AbsenceRequest(
							companyId = companyId, absenceKind = kind, notes = notes, fromEpochDay = f.toEpochDay(), toEpochDay = t.toEpochDay(),
							startMin = a, endMin = b,
						),
					)
					onClose()
				} catch (x: ValidationException) {
					error = x.reason
				}
			}
		},
	) {
		if (fieldShown(Field.COMPANY))
			PolicyField(Field.COMPANY) {
				ChoiceField(
					fieldLabel(Field.COMPANY, R.string.field_company),
					listOf<Pair<Long?, String>>(null to stringResource(R.string.all_companies)) + companies.filter { !it.archived }.map { it.id to it.name },
					companyId,
					importance = Importance.OPTIONAL,
					colorOf = { id -> companies.firstOrNull { it.id == id }?.color },
				) { companyId = it }
			}
		/* the description is one of four, never free text */
		ChoiceField(
			stringResource(R.string.field_kind),
			AbsenceKind.entries.map { it to stringResource(absenceKindName(it)) },
			kind,
			importance = Importance.REQUIRED,
		) { kind = it }
		PolicyField(Field.NOTES) {
			it.infraconsulting.activitytrack.ui.common.TextInput(
				fieldLabel(Field.NOTES, R.string.field_notes), notes, { notes = it }, singleLine = false, importance = Importance.OPTIONAL,
			)
		}
		DateField(stringResource(R.string.create_absence_from), from, { from = it }, importance = Importance.REQUIRED)
		DateField(stringResource(R.string.create_absence_to), to, { to = it }, clearable = true, importance = Importance.OPTIONAL)
		RangeField(
			stringResource(R.string.todo_plan_time_optional), start, end, { grid = true }, { start = ""; end = "" },
			importance = Importance.OPTIONAL,
		)
		Text(
			stringResource(if (start.isBlank()) R.string.create_absence_hint_days else R.string.create_absence_hint_times),
			style = MaterialTheme.typography.bodySmall,
		)
	}
	if (grid) {
		val a = parseHhMm(start)?.takeIf { it < 1440 }
		val b = parseHhMm(end)
		TimeGridSheet(
			title = stringResource(R.string.todo_plan_time_optional),
			initial = if (a != null && b != null && b > a) a until b else null,
			range = true,
			day = parseDate(from) ?: LocalDate.now(),
			onDismiss = { grid = false },
		) { f, t ->
			grid = false
			start = formatMin(f)
			end = formatMin(t)
		}
	}
}
