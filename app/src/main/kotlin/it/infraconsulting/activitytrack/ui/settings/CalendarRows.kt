/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.calendar.CalendarSync
import it.infraconsulting.activitytrack.calendar.DeviceCalendar
import it.infraconsulting.activitytrack.domain.settings.AppSettings
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import kotlinx.coroutines.launch

private const val NEW_LOCAL = -1L

/* The calendars this phone offers, reloaded after the permission is granted. */
@Composable
private fun deviceCalendars(reload: Int): List<DeviceCalendar> {
	val sync = (LocalContext.current.applicationContext as ActivityTrackApp).calendarSync
	return produceState(emptyList<DeviceCalendar>(), reload) { value = sync.calendars() }.value
}

private fun label(c: DeviceCalendar) = if (c.account.isBlank() || c.account == c.name) c.name else "${c.name} (${c.account})"

/*
 * Settings, calendar section (M5): permission, the generic calendar (or a
 * new local "ActivityTrack" one) and a full re-sync of the last 90 days.
 */
@Composable
fun CalendarRows() {
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val sync = app.calendarSync
	val scope = rememberCoroutineScope()
	var reload by remember { mutableIntStateOf(0) }
	var granted by remember { mutableStateOf(sync.hasPermission()) }
	var result by remember { mutableStateOf<Int?>(null) }
	val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
		granted = sync.hasPermission()
		reload++
	}
	val settings = produceState<AppSettings?>(null) {
		app.container.settings.settings.collect { value = it }
	}.value ?: return
	val calendars = deviceCalendars(reload)

	if (!granted) {
		Row {
			Text(stringResource(R.string.calendar_permission), modifier = Modifier.weight(1f))
			TextButton(onClick = { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) {
				Text(stringResource(R.string.perm_grant))
			}
		}
		return
	}
	ChoiceField(
		stringResource(R.string.calendar_generic),
		listOf<Pair<Long?, String>>(null to stringResource(R.string.calendar_none)) + calendars.map { it.id to label(it) } +
			(NEW_LOCAL to stringResource(R.string.calendar_new_local)),
		settings.calendarId,
	) { id ->
		scope.launch {
			val chosen = if (id == NEW_LOCAL) sync.createLocalCalendar().also { reload++ } else id
			app.container.settings.update { it.copy(calendarId = chosen) }
		}
	}
	Row {
		Text(
			result?.let { stringResource(R.string.calendar_synced, it) } ?: stringResource(R.string.calendar_hint),
			style = MaterialTheme.typography.bodySmall,
			modifier = Modifier.weight(1f),
		)
		TextButton(onClick = { scope.launch { result = sync.sync(CalendarSync.FULL_DAYS) } }) {
			Text(stringResource(R.string.calendar_sync_now))
		}
	}
}

/* Company screen: its own calendar instead of the generic one. */
@Composable
fun CompanyCalendarRow(companyId: Long) {
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val scope = rememberCoroutineScope()
	if (!app.calendarSync.hasPermission())
		return
	val settings = produceState<AppSettings?>(null) {
		app.container.settings.settings.collect { value = it }
	}.value ?: return
	val calendars = deviceCalendars(0)
	ChoiceField(
		stringResource(R.string.calendar_company),
		listOf<Pair<Long?, String>>(null to stringResource(R.string.calendar_use_generic)) + calendars.map { it.id to label(it) },
		settings.companyCalendars[companyId],
	) { id ->
		scope.launch {
			app.container.settings.update {
				it.copy(companyCalendars = if (id == null) it.companyCalendars - companyId else it.companyCalendars + (companyId to id))
			}
		}
	}
}
