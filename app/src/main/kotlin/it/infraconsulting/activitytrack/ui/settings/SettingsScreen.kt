/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import androidx.activity.compose.BackHandler

import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.ui.common.PolicySetting
import it.infraconsulting.activitytrack.domain.policy.Feature
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
			repo.update { cur -> s.copy(rootUri = cur.rootUri, calendarId = cur.calendarId, companyCalendars = cur.companyCalendars, gestures = cur.gestures, todoCompanyFilter = cur.todoCompanyFilter, todoWindowHours = cur.todoWindowHours, homeTodoWindowHours = cur.homeTodoWindowHours, agendaView = cur.agendaView, homeTab = cur.homeTab, homeClosedWindow = cur.homeClosedWindow, homeShow = cur.homeShow, homeWindow = cur.homeWindow, widgetTodoWindowHours = cur.widgetTodoWindowHours, homeFilterEntries = cur.homeFilterEntries, todoShowIgnored = cur.todoShowIgnored, gestureHintSeen = cur.gestureHintSeen, swipeLearned = cur.swipeLearned, suspendHintSeen = cur.suspendHintSeen, lastCompanyId = cur.lastCompanyId, lastClientId = cur.lastClientId, listOrders = cur.listOrders) }
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
/* [essential]: listed first; the others under "Altre impostazioni" (issue #99) */
enum class SettingsSection(val label: Int, val icon: Int, val form: Boolean = true, val feature: Feature? = null, val essential: Boolean = false) {
	DISPLAY(R.string.sec_display, R.drawable.ic_menu_phone_android, essential = true),
	TIMES(R.string.sec_times, R.drawable.ic_clock, essential = true),
	CALLS(R.string.sec_calls, R.drawable.ic_menu_phone, feature = Feature.CALLS),
	REMINDERS(R.string.sec_reminders, R.drawable.ic_menu_alarm, essential = true),
	NOTIFICATIONS(R.string.sec_notifications, R.drawable.ic_menu_bell, form = false),
	LOCATION(R.string.sec_location, R.drawable.ic_menu_place, feature = Feature.LOCATION, essential = true),
	CALENDAR(R.string.sec_calendar, R.drawable.ic_calendar, form = false, feature = Feature.CALENDAR),
	DICTATION(R.string.sec_dictation, R.drawable.ic_mic, feature = Feature.DICTATION),
	REPORTS(R.string.sec_reports, R.drawable.ic_nav_report),
	/* ActivityTrack Web (issue #113) */
	SYNC(R.string.sec_sync, R.drawable.ic_menu_sync, form = false, essential = true),
	ONLINE(R.string.sec_online, R.drawable.ic_menu_public),
	STORAGE(R.string.sec_storage, R.drawable.ic_menu_folder),
	/* newer releases on GitHub (SYNC.md §12.4) */
	UPDATES(R.string.sec_updates, R.drawable.ic_restore),
	ADVANCED(R.string.sec_advanced, R.drawable.ic_menu_tune),
}

/* [section] null: the list of sections (and Gestures); else that section's form. */
@Composable
fun SettingsScreen(onBack: () -> Unit, section: SettingsSection? = null, onOpen: (Screen) -> Unit = {}) {
	val vm = appViewModel { SettingsViewModel(it.settings) }
	val s = vm.settings.collectAsState().value
	val policy = LocalPolicy.current
	/* unsaved edits in the open section (issue #78): a dot in the title and a
	 * confirmation before leaving — the arrow, the system Back and the gesture */
	var dirty by remember { mutableStateOf(false) }
	var confirmLeave by remember { mutableStateOf(false) }
	fun guardedBack() {
		if (dirty && section?.form == true) confirmLeave = true else onBack()
	}
	BackHandler { guardedBack() }
	if (confirmLeave) {
		AlertDialog(
			onDismissRequest = { confirmLeave = false },
			text = { Text(stringResource(R.string.settings_unsaved)) },
			confirmButton = {
				TextButton(onClick = onBack) { Text(stringResource(R.string.settings_unsaved_leave)) }
			},
			dismissButton = {
				TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) }
			},
		)
	}
	val dots = if (dirty && section?.form == true) " •" else ""
	var showAll by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
	ScreenScaffold(title = stringResource(section?.label ?: R.string.settings_title) + dots, onBack = ::guardedBack) { padding ->
		if (section == null)
			LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
				/* sections of features the policy switched off are not offered (issue #48) */
				val offered = SettingsSection.entries.filter { it.feature?.let(policy::enabled) ?: true }
				items(offered.filter { it.essential }) { sec ->
					ClickableRow(stringResource(sec.label), icon = sec.icon) { onOpen(Screen.SettingsPage(sec.name)) }
				}
				if (policy.enabled(Feature.GESTURES))
					item { ClickableRow(stringResource(R.string.gestures_title), icon = R.drawable.ic_menu_touch) { onOpen(Screen.Gestures) } }
				/* the rest, folded (issue #99) */
				item {
					ClickableRow(stringResource(R.string.settings_more) + if (showAll) " ▴" else " ▾", icon = R.drawable.ic_menu_tune) { showAll = !showAll }
				}
				if (showAll)
					items(offered.filter { !it.essential }) { sec ->
						ClickableRow(stringResource(sec.label), icon = sec.icon) { onOpen(Screen.SettingsPage(sec.name)) }
					}
			}
		else if (s != null)
			/* the Save button stays above the keyboard (issue #141), as the entry form */
			SettingsForm(s, vm, section, Modifier.padding(padding).consumeWindowInsets(padding).imePadding(), onDirty = { dirty = it })
	}
}

@Composable
private fun SettingsForm(initial: AppSettings, vm: SettingsViewModel, section: SettingsSection, modifier: Modifier, onDirty: (Boolean) -> Unit = {}) {
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
	var tagsFreeText by remember { mutableStateOf(initial.tagsFreeText) }
	var updateCheck by remember { mutableStateOf(initial.updateCheck) }
	var updateBetas by remember { mutableStateOf(initial.updateBetas) }
	var callsIn by remember { mutableStateOf(initial.callsIncoming) }
	var callsOut by remember { mutableStateOf(initial.callsOutgoing) }
	var callPause by remember { mutableStateOf(initial.callAutoPause) }
	var callPropose by remember { mutableStateOf(initial.callProposeEntry) }
	var callsAlways by remember { mutableStateOf(initial.callsAlways) }
	var backupEvery by remember { mutableStateOf(initial.backupEveryHours) }
	var backupHour by remember { mutableStateOf(initial.backupHour) }
	var widgetTodoWindow by remember { mutableStateOf(initial.widgetTodoWindowHours) }
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
		tagsFreeText = tagsFreeText,
		updateCheck = updateCheck,
		updateBetas = updateBetas,
		callsIncoming = callsIn,
		callsOutgoing = callsOut,
		callAutoPause = callPause,
		callProposeEntry = callPropose,
		callsAlways = callsAlways,
		backupEveryHours = backupEvery,
		backupHour = backupHour,
		widgetTodoWindowHours = widgetTodoWindow,
		rounding = ReportRounding(
			work.copy(minimum = minutes(F.WORK_MIN, work.minimum)),
			travel.copy(minimum = minutes(F.TRAVEL_MIN, travel.minimum)),
			pause.copy(minimum = minutes(F.PAUSE_MIN, pause.minimum)),
		),
	)

	/* unsaved edits: the built settings differ from the loaded ones (issue #78);
	 * after Save the flow re-emits and initial catches up with build() */
	val dirtyNow = build() != initial
	LaunchedEffect(dirtyNow) { onDirty(dirtyNow) }

	@Composable
	fun field(f: F, label: Int, type: KeyboardType = KeyboardType.Number, key: String? = null) =
		if (key == null) TextInput(stringResource(label), text[f].orEmpty(), { text[f] = it }, type)
		else PolicySetting(key) { TextInput(stringResource(label), text[f].orEmpty(), { text[f] = it }, type) }

	Column(modifier = modifier.fillMaxSize()) {
		Column(
			/* top padding: the first outlined field's label sits above its border */
			modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			when (section) {
				SettingsSection.DISPLAY -> {
					PolicySetting("compactDisplay") {
						ChoiceField(
							stringResource(R.string.set_display),
							listOf(false to stringResource(R.string.display_normal), true to stringResource(R.string.display_compact)),
							compact,
						) { compact = it }
					}
					/* how far the planned-to-dos widget looks ahead (issue #62) */
					PolicySetting("widgetTodoWindowHours") {
						ChoiceField(
							stringResource(R.string.set_widget_todo_window),
							listOf(
								24 to stringResource(R.string.todo_window_24h),
								72 to stringResource(R.string.todo_window_3d),
								168 to stringResource(R.string.todo_window_7d),
								720 to stringResource(R.string.todo_window_30d),
							),
							widgetTodoWindow,
						) { widgetTodoWindow = it }
					}
					/* free tags or only the master data's (issue #133) */
					if (LocalPolicy.current.field(it.infraconsulting.activitytrack.domain.policy.Field.TAGS).visible)
						PolicySetting("tagsFreeText") {
							SwitchRow(stringResource(R.string.set_tags_free_text), tagsFreeText) { tagsFreeText = it }
							Text(stringResource(R.string.set_tags_free_text_hint), style = MaterialTheme.typography.bodySmall)
						}
				}
				SettingsSection.TIMES -> {
					PolicySetting("editableTimes") {
						SwitchRow(stringResource(R.string.set_editable_times), editableTimes) { editableTimes = it }
						Text(stringResource(R.string.set_editable_times_hint), style = MaterialTheme.typography.bodySmall)
					}
				}
				SettingsSection.CALLS -> {
					PolicySetting("callsIncoming") { SwitchRow(stringResource(R.string.set_calls_incoming), callsIn) { callsIn = it; if (it) askCalls() } }
					PolicySetting("callsOutgoing") { SwitchRow(stringResource(R.string.set_calls_outgoing), callsOut) { callsOut = it; if (it) askCalls() } }
					PolicySetting("callAutoPause") { SwitchRow(stringResource(R.string.set_call_pause), callPause) { callPause = it } }
					PolicySetting("callProposeEntry") { SwitchRow(stringResource(R.string.set_call_propose), callPropose) { callPropose = it } }
					PolicySetting("callsAlways") { SwitchRow(stringResource(R.string.set_calls_always), callsAlways) { callsAlways = it } }
					Text(stringResource(R.string.set_calls_hint), style = MaterialTheme.typography.bodySmall)
				}
				SettingsSection.REMINDERS -> {
					field(F.CONFIRM, R.string.set_confirm_interval, key = "confirmIntervalMin")
					field(F.MAX_CONFIRM, R.string.set_max_confirmations, key = "maxConfirmations")
					field(F.PAUSE, R.string.set_pause_interval, key = "pauseIntervalMin")
					field(F.DAY_START, R.string.set_day_start_delay, key = "dayStartDelayMin")
				}
				SettingsSection.NOTIFICATIONS -> NotificationRows()
				SettingsSection.LOCATION -> {
					LocationPermissionRows()
					AddTileRow()
					field(F.MOVEMENT, R.string.set_movement, key = "movementThresholdM")
					field(F.ST_RADIUS, R.string.set_stationary_radius, key = "stationaryRadiusM")
					field(F.ST_MIN, R.string.set_stationary_min, key = "stationaryMin")
					field(F.KM_FACTOR, R.string.set_km_factor, KeyboardType.Decimal, key = "kmFactor")
				}
				SettingsSection.CALENDAR -> CalendarRows()
				SettingsSection.SYNC -> SyncRows()
				SettingsSection.UPDATES -> {
					UpdateRows()
					PolicySetting("updateCheck") { SwitchRow(stringResource(R.string.set_update_check), updateCheck) { updateCheck = it } }
					PolicySetting("updateBetas") { SwitchRow(stringResource(R.string.set_update_betas), updateBetas) { updateBetas = it } }
				}
				/* language names in their own language, as in the system pickers */
				SettingsSection.DICTATION -> PolicySetting("dictationLanguage") {
					ChoiceField(
						stringResource(R.string.set_dictation_language),
						AppSettings.DICTATION_LANGUAGES.map { tag ->
							tag to java.util.Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it).replaceFirstChar(Char::titlecase) }
						},
						dictation,
					) { dictation = it }
				}
				SettingsSection.REPORTS -> {
					PolicySetting("carryOverMin") { TimeField(stringResource(R.string.set_carry_over), text[F.CARRY_OVER].orEmpty(), { text[F.CARRY_OVER] = it }) }
					/* mode, quantum and minimum each under their own key (policy, 2026-10-28) */
					RoundingEditor(R.string.set_round_work, work, { work = it }, F.WORK_MIN, text, "Work")
					RoundingEditor(R.string.set_round_travel, travel, { travel = it }, F.TRAVEL_MIN, text, "Travel")
					RoundingEditor(R.string.set_round_pause, pause, { pause = it }, F.PAUSE_MIN, text, "Pause")
				}
				SettingsSection.ONLINE -> {
					PolicySetting("nominatimEnabled") { SwitchRow(stringResource(R.string.set_nominatim), nominatimOn) { nominatimOn = it } }
					field(F.NOMINATIM, R.string.set_endpoint, KeyboardType.Uri, key = "nominatimEndpoint")
					PolicySetting("osrmEnabled") { SwitchRow(stringResource(R.string.set_osrm), osrmOn) { osrmOn = it } }
					field(F.OSRM, R.string.set_endpoint, KeyboardType.Uri, key = "osrmEndpoint")
					PolicySetting("tilesEnabled") { SwitchRow(stringResource(R.string.set_tiles), tilesOn) { tilesOn = it } }
					field(F.TILES, R.string.set_endpoint, KeyboardType.Uri, key = "tilesEndpoint")
				}
				SettingsSection.STORAGE -> {
					DataDirRow(initial.rootUri)
					field(F.RETENTION, R.string.set_backup_retention, key = "backupRetention")
					/* frequency and preferred hour of the automatic backup (issue #44) */
					val every = mapOf(
						6 to stringResource(R.string.backup_every_6h), 12 to stringResource(R.string.backup_every_12h),
						24 to stringResource(R.string.backup_every_day), 168 to stringResource(R.string.backup_every_week),
					)
					PolicySetting("backupEveryHours") { ChoiceField(stringResource(R.string.set_backup_every), every.toList(), backupEvery) { backupEvery = it } }
					PolicySetting("backupHour") {
						ChoiceField(
							stringResource(R.string.set_backup_hour), (0..23).map { h -> h to "%02d:00".format(h) }, backupHour,
						) { backupHour = it }
					}
					Text(stringResource(R.string.backup_hour_hint), style = MaterialTheme.typography.bodySmall)
					/* "Back up now" moved to Import / export, beside the restore (issue #44) */
					Text(stringResource(R.string.backup_moved_hint), style = MaterialTheme.typography.bodySmall)
				}
				SettingsSection.ADVANCED -> {
					field(F.PLAN_LEAD, R.string.set_plan_lead, key = "planLeadMin")
					field(F.PLAN_SNOOZE, R.string.set_plan_snooze, key = "planSnoozeMin")
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
	/* "Work", "Travel", "Pause": the policy keys rounding<kind>Mode / Min / MinimumMin */
	kind: String,
) {
	val p = LocalPolicy.current.settings
	if (listOf("Mode", "Min", "MinimumMin").all { p["rounding$kind$it"]?.hidden == true })
		return
	Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
	PolicySetting("rounding${kind}Mode") {
		ChoiceField(
			stringResource(R.string.round_mode),
			RoundingMode.entries.map { it to stringResource(modeLabel(it)) },
			rule.mode,
		) { onChange(rule.copy(mode = it)) }
	}
	PolicySetting("rounding${kind}Min") {
		ChoiceField(
			stringResource(R.string.round_quantum),
			AppSettings.QUANTA_MIN.map { it to it.toString() },
			rule.quantum.toMinutes(),
		) { onChange(rule.copy(quantum = Duration.ofMinutes(it))) }
	}
	PolicySetting("rounding${kind}MinimumMin") {
		TextInput(stringResource(R.string.round_minimum), text[minField].orEmpty(), { text[minField] = it }, KeyboardType.Number)
	}
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
