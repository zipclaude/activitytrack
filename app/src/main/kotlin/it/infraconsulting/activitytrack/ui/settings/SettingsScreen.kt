/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.Screen
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import it.infraconsulting.activitytrack.backup.BackupWorker
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import it.infraconsulting.activitytrack.domain.rounding.RoundingMode
import it.infraconsulting.activitytrack.domain.rounding.RoundingRule
import it.infraconsulting.activitytrack.domain.settings.AppSettings
import it.infraconsulting.activitytrack.domain.settings.OnlineService
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.parseDecimal
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import it.infraconsulting.activitytrack.ui.setup.DataDirViewModel
import it.infraconsulting.activitytrack.ui.setup.rememberDataDirPicker
import java.time.Duration
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
	val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	fun save(s: AppSettings, onDone: () -> Unit) {
		viewModelScope.launch {
			/* data folder, calendars and gestures change through their own rows, not this form */
			repo.update { cur -> s.copy(rootUri = cur.rootUri, calendarId = cur.calendarId, companyCalendars = cur.companyCalendars, gestures = cur.gestures, todoCompanyFilter = cur.todoCompanyFilter, todoWindowHours = cur.todoWindowHours, homeTodoWindowHours = cur.homeTodoWindowHours, todoShowIgnored = cur.todoShowIgnored, listOrders = cur.listOrders) }
			onDone()
		}
	}
}

/* Text-field keys of the form; values are parsed on save. */
private enum class F {
	CONFIRM, MAX_CONFIRM, PAUSE, DAY_START, MOVEMENT, ST_RADIUS, ST_MIN, KM_FACTOR, CARRY_OVER, RETENTION,
	NOMINATIM, OSRM, TILES, WORK_MIN, TRAVEL_MIN, PAUSE_MIN, PLAN_LEAD, PLAN_SNOOZE,
}

/* Settings grouped in sub-screens (backlog 2, 2026-10-18). */
enum class SettingsSection(val label: Int, val icon: Int, val form: Boolean = true) {
	DISPLAY(R.string.sec_display, R.drawable.ic_menu_phone_android),
	TIMES(R.string.sec_times, R.drawable.ic_clock),
	CALLS(R.string.sec_calls, R.drawable.ic_menu_phone),
	REMINDERS(R.string.sec_reminders, R.drawable.ic_menu_alarm),
	NOTIFICATIONS(R.string.sec_notifications, R.drawable.ic_menu_bell, form = false),
	LOCATION(R.string.sec_location, R.drawable.ic_menu_place),
	CALENDAR(R.string.sec_calendar, R.drawable.ic_calendar, form = false),
	DICTATION(R.string.sec_dictation, R.drawable.ic_mic),
	REPORTS(R.string.sec_reports, R.drawable.ic_nav_report),
	ONLINE(R.string.sec_online, R.drawable.ic_menu_public),
	STORAGE(R.string.sec_storage, R.drawable.ic_menu_folder),
	ADVANCED(R.string.sec_advanced, R.drawable.ic_menu_tune),
}

/* [section] null: the list of sections (and Gestures); else that section's form. */
@Composable
fun SettingsScreen(onBack: () -> Unit, section: SettingsSection? = null, onOpen: (Screen) -> Unit = {}) {
	val vm = appViewModel { SettingsViewModel(it.settings) }
	val s = vm.settings.collectAsState().value
	ScreenScaffold(title = stringResource(section?.label ?: R.string.settings_title), onBack = onBack) { padding ->
		if (section == null)
			LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
				items(SettingsSection.entries) { sec -> ClickableRow(stringResource(sec.label), icon = sec.icon) { onOpen(Screen.SettingsPage(sec.name)) } }
				item { ClickableRow(stringResource(R.string.gestures_title), icon = R.drawable.ic_menu_touch) { onOpen(Screen.Gestures) } }
			}
		else if (s != null)
			SettingsForm(s, vm, section, Modifier.padding(padding))
	}
}

@Composable
private fun SettingsForm(initial: AppSettings, vm: SettingsViewModel, section: SettingsSection, modifier: Modifier) {
	val text = remember {
		mutableStateMapOf(
			F.CONFIRM to initial.confirmIntervalMin.toString(),
			F.MAX_CONFIRM to initial.maxConfirmations.toString(),
			F.PAUSE to initial.pauseIntervalMin.toString(),
			F.DAY_START to initial.dayStartDelayMin.toString(),
			F.MOVEMENT to initial.movementThresholdM.toString(),
			F.ST_RADIUS to initial.stationaryRadiusM.toString(),
			F.ST_MIN to initial.stationaryMin.toString(),
			F.KM_FACTOR to initial.kmFactor.toString(),
			F.CARRY_OVER to formatMin(initial.carryOverMin),
			F.RETENTION to initial.backupRetention.toString(),
			F.NOMINATIM to initial.nominatim.endpoint,
			F.OSRM to initial.osrm.endpoint,
			F.TILES to initial.tiles.endpoint,
			F.WORK_MIN to initial.rounding.work.minimum.toMinutes().toString(),
			F.TRAVEL_MIN to initial.rounding.travel.minimum.toMinutes().toString(),
			F.PAUSE_MIN to initial.rounding.pause.minimum.toMinutes().toString(),
			F.PLAN_LEAD to initial.planLeadMin.toString(),
			F.PLAN_SNOOZE to initial.planSnoozeMin.toString(),
		)
	}
	var nominatimOn by remember { mutableStateOf(initial.nominatim.enabled) }
	var osrmOn by remember { mutableStateOf(initial.osrm.enabled) }
	var tilesOn by remember { mutableStateOf(initial.tiles.enabled) }
	var dictation by remember { mutableStateOf(initial.dictationLanguage) }
	var compact by remember { mutableStateOf(initial.compactDisplay) }
	var editableTimes by remember { mutableStateOf(initial.editableTimes) }
	var callsIn by remember { mutableStateOf(initial.callsIncoming) }
	var callsOut by remember { mutableStateOf(initial.callsOutgoing) }
	var callPause by remember { mutableStateOf(initial.callAutoPause) }
	var callPropose by remember { mutableStateOf(initial.callProposeEntry) }
	var callsAlways by remember { mutableStateOf(initial.callsAlways) }
	/* recognition needs the phone state and the call log: asked when switched on */
	val callPermissions = androidx.activity.compose.rememberLauncherForActivityResult(
		androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
	) { granted -> if (granted.values.any { !it }) { callsIn = false; callsOut = false } }
	val askCalls = {
		callPermissions.launch(arrayOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_CALL_LOG))
	}
	var work by remember { mutableStateOf(initial.rounding.work) }
	var travel by remember { mutableStateOf(initial.rounding.travel) }
	var pause by remember { mutableStateOf(initial.rounding.pause) }

	val snackbar = remember { SnackbarHostState() }
	val scope = rememberCoroutineScope()
	val savedMsg = stringResource(R.string.settings_saved)

	fun int(f: F, fallback: Int) = text[f]?.trim()?.toIntOrNull() ?: fallback
	fun minutes(f: F, fallback: Duration) = text[f]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
		?.let(Duration::ofMinutes) ?: fallback

	fun build() = initial.copy(
		confirmIntervalMin = int(F.CONFIRM, initial.confirmIntervalMin),
		maxConfirmations = int(F.MAX_CONFIRM, initial.maxConfirmations),
		pauseIntervalMin = int(F.PAUSE, initial.pauseIntervalMin),
		dayStartDelayMin = int(F.DAY_START, initial.dayStartDelayMin),
		planLeadMin = int(F.PLAN_LEAD, initial.planLeadMin),
		planSnoozeMin = int(F.PLAN_SNOOZE, initial.planSnoozeMin),
		movementThresholdM = int(F.MOVEMENT, initial.movementThresholdM),
		stationaryRadiusM = int(F.ST_RADIUS, initial.stationaryRadiusM),
		stationaryMin = int(F.ST_MIN, initial.stationaryMin),
		kmFactor = parseDecimal(text[F.KM_FACTOR].orEmpty()) ?: initial.kmFactor,
		carryOverMin = parseHhMm(text[F.CARRY_OVER].orEmpty()) ?: initial.carryOverMin,
		backupRetention = int(F.RETENTION, initial.backupRetention),
		nominatim = OnlineService(nominatimOn, text[F.NOMINATIM].orEmpty()),
		osrm = OnlineService(osrmOn, text[F.OSRM].orEmpty()),
		tiles = OnlineService(tilesOn, text[F.TILES].orEmpty()),
		dictationLanguage = dictation,
		compactDisplay = compact,
		editableTimes = editableTimes,
		callsIncoming = callsIn,
		callsOutgoing = callsOut,
		callAutoPause = callPause,
		callProposeEntry = callPropose,
		callsAlways = callsAlways,
		rounding = ReportRounding(
			work.copy(minimum = minutes(F.WORK_MIN, work.minimum)),
			travel.copy(minimum = minutes(F.TRAVEL_MIN, travel.minimum)),
			pause.copy(minimum = minutes(F.PAUSE_MIN, pause.minimum)),
		),
	)

	@Composable
	fun field(f: F, label: Int, type: KeyboardType = KeyboardType.Number) =
		TextInput(stringResource(label), text[f].orEmpty(), { text[f] = it }, type)

	Column(modifier = modifier.fillMaxSize()) {
		Column(
			modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			when (section) {
				SettingsSection.DISPLAY -> ChoiceField(
					stringResource(R.string.set_display),
					listOf(false to stringResource(R.string.display_normal), true to stringResource(R.string.display_compact)),
					compact,
				) { compact = it }
				SettingsSection.TIMES -> {
					SwitchRow(stringResource(R.string.set_editable_times), editableTimes) { editableTimes = it }
					Text(stringResource(R.string.set_editable_times_hint), style = MaterialTheme.typography.bodySmall)
				}
				SettingsSection.CALLS -> {
					SwitchRow(stringResource(R.string.set_calls_incoming), callsIn) { callsIn = it; if (it) askCalls() }
					SwitchRow(stringResource(R.string.set_calls_outgoing), callsOut) { callsOut = it; if (it) askCalls() }
					SwitchRow(stringResource(R.string.set_call_pause), callPause) { callPause = it }
					SwitchRow(stringResource(R.string.set_call_propose), callPropose) { callPropose = it }
					SwitchRow(stringResource(R.string.set_calls_always), callsAlways) { callsAlways = it }
					Text(stringResource(R.string.set_calls_hint), style = MaterialTheme.typography.bodySmall)
				}
				SettingsSection.REMINDERS -> {
					field(F.CONFIRM, R.string.set_confirm_interval)
					field(F.MAX_CONFIRM, R.string.set_max_confirmations)
					field(F.PAUSE, R.string.set_pause_interval)
					field(F.DAY_START, R.string.set_day_start_delay)
				}
				SettingsSection.NOTIFICATIONS -> NotificationRows()
				SettingsSection.LOCATION -> {
					LocationPermissionRows()
					AddTileRow()
					field(F.MOVEMENT, R.string.set_movement)
					field(F.ST_RADIUS, R.string.set_stationary_radius)
					field(F.ST_MIN, R.string.set_stationary_min)
					field(F.KM_FACTOR, R.string.set_km_factor, KeyboardType.Decimal)
				}
				SettingsSection.CALENDAR -> CalendarRows()
				/* language names in their own language, as in the system pickers */
				SettingsSection.DICTATION -> ChoiceField(
					stringResource(R.string.set_dictation_language),
					AppSettings.DICTATION_LANGUAGES.map { tag ->
						tag to java.util.Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it).replaceFirstChar(Char::titlecase) }
					},
					dictation,
				) { dictation = it }
				SettingsSection.REPORTS -> {
					TimeField(stringResource(R.string.set_carry_over), text[F.CARRY_OVER].orEmpty(), { text[F.CARRY_OVER] = it })
					RoundingEditor(R.string.set_round_work, work, { work = it }, F.WORK_MIN, text)
					RoundingEditor(R.string.set_round_travel, travel, { travel = it }, F.TRAVEL_MIN, text)
					RoundingEditor(R.string.set_round_pause, pause, { pause = it }, F.PAUSE_MIN, text)
				}
				SettingsSection.ONLINE -> {
					SwitchRow(stringResource(R.string.set_nominatim), nominatimOn) { nominatimOn = it }
					field(F.NOMINATIM, R.string.set_endpoint, KeyboardType.Uri)
					SwitchRow(stringResource(R.string.set_osrm), osrmOn) { osrmOn = it }
					field(F.OSRM, R.string.set_endpoint, KeyboardType.Uri)
					SwitchRow(stringResource(R.string.set_tiles), tilesOn) { tilesOn = it }
					field(F.TILES, R.string.set_endpoint, KeyboardType.Uri)
				}
				SettingsSection.STORAGE -> {
					DataDirRow(initial.rootUri)
					field(F.RETENTION, R.string.set_backup_retention)
					val context = androidx.compose.ui.platform.LocalContext.current
					val started = stringResource(R.string.backup_started)
					TextButton(onClick = {
						BackupWorker.runNow(context)
						scope.launch { snackbar.showSnackbar(started) }
					}) { Text(stringResource(R.string.backup_now)) }
				}
				SettingsSection.ADVANCED -> {
					field(F.PLAN_LEAD, R.string.set_plan_lead)
					field(F.PLAN_SNOOZE, R.string.set_plan_snooze)
				}
			}

			/* the note is about values typed in a form; Notifications and Calendar have none */
			if (section.form) Text(
				stringResource(R.string.settings_note),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(vertical = 8.dp),
			)
		}
		SnackbarHost(snackbar)
		if (section.form)
			Button(
				onClick = { vm.save(build()) { scope.launch { snackbar.showSnackbar(savedMsg) } } },
				modifier = Modifier.fillMaxWidth().padding(16.dp),
			) { Text(stringResource(R.string.action_save)) }
	}
}

@Composable
private fun RoundingEditor(
	label: Int,
	rule: RoundingRule,
	onChange: (RoundingRule) -> Unit,
	minField: F,
	text: MutableMap<F, String>,
) {
	Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
	ChoiceField(
		stringResource(R.string.round_mode),
		RoundingMode.entries.map { it to stringResource(modeLabel(it)) },
		rule.mode,
	) { onChange(rule.copy(mode = it)) }
	ChoiceField(
		stringResource(R.string.round_quantum),
		AppSettings.QUANTA_MIN.map { it to it.toString() },
		rule.quantum.toMinutes(),
	) { onChange(rule.copy(quantum = Duration.ofMinutes(it))) }
	TextInput(stringResource(R.string.round_minimum), text[minField].orEmpty(), { text[minField] = it }, KeyboardType.Number)
}

private fun modeLabel(m: RoundingMode) = when (m) {
	RoundingMode.CEIL_TOLERANT -> R.string.round_ceil_tolerant
	RoundingMode.CEIL -> R.string.round_ceil
	RoundingMode.FLOOR -> R.string.round_floor
}

@Composable
private fun DataDirRow(rootUri: String?) {
	val vm = appViewModel { DataDirViewModel(it.userDataDir, it.settings) }
	val failed by vm.failed.collectAsState()
	val pick = rememberDataDirPicker(vm)
	Row(verticalAlignment = Alignment.CenterVertically) {
		Column(modifier = Modifier.weight(1f)) {
			Text(stringResource(R.string.set_root))
			Text(
				rootUri?.let { Uri.decode(it.toUri().lastPathSegment.orEmpty()) }.orEmpty(),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (failed)
				Text(stringResource(R.string.setup_error), color = MaterialTheme.colorScheme.error)
		}
		TextButton(onClick = pick) { Text(stringResource(R.string.set_change_root)) }
	}
}
