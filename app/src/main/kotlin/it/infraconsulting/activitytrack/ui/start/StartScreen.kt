/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.location.CurrentPosition
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.DateField
import it.infraconsulting.activitytrack.ui.common.DictationInput
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.ui.common.PolicyField
import it.infraconsulting.activitytrack.ui.common.QuickClientDialog
import it.infraconsulting.activitytrack.ui.common.QuickCompanyDialog
import it.infraconsulting.activitytrack.ui.common.QuickPresetDialog
import it.infraconsulting.activitytrack.ui.common.QuickRoleDialog
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.WithAddButton
import it.infraconsulting.activitytrack.ui.common.commandStyle
import it.infraconsulting.activitytrack.ui.common.enabled
import it.infraconsulting.activitytrack.ui.common.fieldImportance
import it.infraconsulting.activitytrack.ui.common.fieldLabel
import it.infraconsulting.activitytrack.ui.common.fieldShown
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.parseDate
import it.infraconsulting.activitytrack.ui.common.reasonText
import java.time.LocalDate
import kotlinx.coroutines.flow.map

/*
 * "Lavoro": the creation dialog of a work activity (ACTIVITY-MODEL.md 3),
 * a screen of its own because of its length. The top selector says when it
 * begins: Adesso, Già iniziata (started at a time), Passata (recorded
 * whole, one "Salva"). Adesso and Già iniziata end with two buttons, Lavoro
 * and Viaggio, each starting the activity with an open segment of that
 * kind. [clientId] pre-fills the client (widget, shortcut), [todoId] starts
 * that TODO activity with the details typed here, [call] a recognised call.
 */
@Composable
fun StartScreen(
	onBack: () -> Unit, clientId: Long? = null, todoId: Long? = null,
	call: CallPrefill? = null,
) {
	val context = LocalContext.current.applicationContext
	val vm = appViewModel {
		StartViewModel(
			it.entries, it.masterData, it.pluginData, clientId, todoId, { CurrentPosition.get(context) },
			it.settings.settings.map { s -> s.editableTimes }, { it.policies.current.value },
		).also { vm ->
			vm.callText = { n -> context.getString(R.string.call_entry_activity, n) }
			call?.let(vm::prefillCall)
		}
	}
	val f by vm.form.collectAsState()
	val companies by vm.companies.collectAsState()
	val clients by vm.clients.collectAsState()
	val roles by vm.roles.collectAsState()
	val presets by vm.presets.collectAsState()
	val clientColors by vm.clientColors.collectAsState()
	val pending by vm.pending.collectAsState()
	val error by vm.error.collectAsState()
	val editableTimes by vm.editableTimes.collectAsState()
	val none = stringResource(R.string.field_none)
	val policy = LocalPolicy.current
	var addCompany by remember { mutableStateOf(false) }
	var addClient by remember { mutableStateOf(false) }
	var addRole by remember { mutableStateOf(false) }
	var addPreset by remember { mutableStateOf(false) }
	var more by rememberSaveable { mutableStateOf(false) }

	/* the time modes only when the times are editable (setting and policy); an old to-do is never "past" */
	val start = if (editableTimes) f.start.takeIf { todoId == null || it != StartWhen.PAST } ?: StartWhen.NOW else StartWhen.NOW
	val past = start == StartWhen.PAST
	val companyId = clients.firstOrNull { it.id == f.clientId }?.companyId ?: f.companyId
	/* outside the scrolling column's own lambdas: the plugin's form reads flows */
	val plugin = rememberPluginForm(companyId, f.clientId, f.pluginInitial)
	LaunchedEffect(plugin?.json) { vm.setPluginFields(plugin?.json) }
	val day = parseDate(f.date) ?: LocalDate.now()

	ScreenScaffold(title = stringResource(R.string.start_title), onBack = onBack) { padding ->
		/* the keyboard shrinks the form, so the field being typed in scrolls into view (issue #47) */
		Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
			Column(
				modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				if (editableTimes && todoId == null)
					Segmented(
						listOf(
							StartWhen.NOW to stringResource(R.string.start_when_now),
							StartWhen.STARTED to stringResource(R.string.start_when_started),
							StartWhen.PAST to stringResource(R.string.start_when_past),
						),
						start, { vm.setStart(it); vm.clearError() },
					)
				else if (editableTimes)
					Segmented(
						listOf(StartWhen.NOW to stringResource(R.string.start_when_now), StartWhen.STARTED to stringResource(R.string.start_when_started)),
						start, { vm.setStart(it); vm.clearError() },
					)
				if (policy.field(Field.COMPANY).visible)
					WithAddButton(stringResource(R.string.quick_company), { addCompany = true }, masterData = true) { PolicyField(Field.COMPANY) {
						ChoiceField(
							fieldLabel(Field.COMPANY, R.string.field_company),
							listOf<Pair<Long?, String>>(null to stringResource(R.string.all_companies)) +
								companies.filter { !it.archived || it.id == f.companyId }.map { it.id to it.name },
							f.companyId,
							/* a filter here (the client is what is required): neutral on "All companies", green once set */
							importance = if (f.companyId == null) null else Importance.REQUIRED,
							colorOf = { id -> companies.firstOrNull { it.id == id }?.color },
						) { vm.setCompany(it) }
					} }
				if (policy.field(Field.CLIENT).visible)
					/* combo box: type to search, or pick from the dropdown; most recent first; "+" adds one (M11) */
					WithAddButton(stringResource(R.string.quick_client), { addClient = true }, masterData = true) { PolicyField(Field.CLIENT) {
						if (clients.isEmpty())
							EmptyText(stringResource(R.string.start_no_clients))
						else
							SearchableChoiceField(
								fieldLabel(Field.CLIENT, R.string.field_client),
								/* a travel may start without a destination */
								listOf<Pair<Long?, String>>(null to stringResource(R.string.client_unknown)) + clients.map { it.id to it.name },
								f.clientId,
								importance = Importance.REQUIRED,
								colorOf = { id -> id?.let { clientColors[it] } },
							) { vm.setClient(it) }
					} }
				/* order: Azienda, Cliente, Scelta rapida, Descrizione, Ruolo */
				val usable = presets.filter { it.companyId == null || it.companyId == companyId }
				if (fieldShown(Field.QUICK_PICK))
					WithAddButton(stringResource(R.string.quick_preset), { addPreset = true }) { PolicyField(Field.QUICK_PICK) {
						ChoiceField(
							fieldLabel(Field.QUICK_PICK, R.string.field_preset),
							listOf<Pair<Long?, String>>(null to none) + usable.map { it.id to it.text },
							null,
							importance = fieldImportance(Field.QUICK_PICK, Importance.OPTIONAL),
						) { id -> id?.let(vm::applyPreset) }
					} }
				/* required: typed or from a preset (owner, 2026-10-19) */
				PolicyField(Field.DESCRIPTION) {
					DictationInput(fieldLabel(Field.DESCRIPTION, R.string.field_activity), f.activity, vm::setActivity, Importance.REQUIRED)
				}
				if (fieldShown(Field.ROLE))
					WithAddButton(stringResource(R.string.quick_role), { addRole = true }) { PolicyField(Field.ROLE) {
						ChoiceField(
							fieldLabel(Field.ROLE, R.string.field_role),
							listOf<Pair<Long?, String>>(null to none) + roles.filter { !it.archived || it.id == f.roleId }.map { it.id to it.name },
							f.roleId,
							importance = fieldImportance(Field.ROLE, Importance.IMPORTANT),
						) { vm.setRole(it) }
					} }
				when (start) {
					StartWhen.NOW -> if (editableTimes) Text(stringResource(R.string.start_when_now_hint), style = MaterialTheme.typography.bodySmall)
					StartWhen.STARTED -> {
						TimeField(
							stringResource(R.string.start_started_at), f.startTime, { vm.setStartTime(it); vm.clearError() },
							clearable = true, importance = Importance.IMPORTANT,
						)
						FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							for (back in listOf(15, 30, 60))
								AssistChip(
									onClick = {
										val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
										vm.setStartTime(formatMin(((now - back) % 1440 + 1440) % 1440))
										vm.clearError()
									},
									label = { Text(stringResource(R.string.start_minutes_ago, back)) },
								)
						}
						Text(stringResource(R.string.start_when_started_hint), style = MaterialTheme.typography.bodySmall)
					}
					StartWhen.PAST -> {
						/* the day (issue #44): empty = today; the segments below are on it */
						if (fieldShown(Field.DATE))
							PolicyField(Field.DATE) {
								DateField(
									fieldLabel(Field.DATE, R.string.field_date), f.date, { vm.setDate(it); vm.clearError() },
									clearable = true, importance = fieldImportance(Field.DATE, Importance.OPTIONAL),
								)
							}
						SegmentsEditor(f.segments, vm::setSegments, day, required = true)
					}
				}
				plugin?.let { if (it.hasRequired) PluginRows(it, required = true) }
				MoreInfoToggle(more) { more = !more }
				if (more) {
					/* segments are times typed by hand: only when the times are editable */
					if (!past && editableTimes)
						SegmentsEditor(f.segments, vm::setSegments, LocalDate.now())
					NotesInput(f.notes, vm::setNotes)
					RefsInput(f.refs, vm::setRefs)
					plugin?.let { if (it.hasOptional) PluginRows(it, required = false) }
				}
				if (error != null)
					Text(reasonText(error!!), color = MaterialTheme.colorScheme.error)
			}
			if (past)
				/* the one button of a past activity */
				Button(
					onClick = { vm.savePast(onBack) },
					enabled = f.ready && f.hasClient && f.segments.isNotEmpty() && pending.isEmpty(),
					modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
				) { Text(stringResource(R.string.action_save), style = MaterialTheme.typography.titleMedium) }
			else
				Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					StartButton(R.string.cmd_work, EntryCommand.WORK, f.ready && f.hasClient && pending.isEmpty(), Modifier.weight(1f)) {
						vm.requestStart(SegmentKind.WORK, onBack)
					}
					if (enabled(Feature.TRAVEL))
						StartButton(R.string.cmd_travel, EntryCommand.TRAVEL, f.ready && pending.isEmpty(), Modifier.weight(1f)) {
							vm.requestStart(SegmentKind.TRAVEL, onBack)
						}
				}
		}
	}

	pending.firstOrNull()?.let { r -> RunningDialog(r, vm) }
	val askFuture by vm.askFuture.collectAsState()
	askFuture?.let { min ->
		val at = formatMin(min)
		AlertDialog(
			onDismissRequest = vm::cancelFuture,
			title = { Text(stringResource(R.string.future_title, at)) },
			text = {
				Column {
					Text(stringResource(R.string.future_text))
					TextButton(onClick = { vm.answerFuture(StartViewModel.FutureChoice.PLAN) }) { Text(stringResource(R.string.future_plan, at)) }
					TextButton(onClick = { vm.answerFuture(StartViewModel.FutureChoice.NOW) }) { Text(stringResource(R.string.future_now)) }
					TextButton(onClick = { vm.answerFuture(StartViewModel.FutureChoice.YESTERDAY) }) { Text(stringResource(R.string.future_yesterday, at)) }
				}
			},
			confirmButton = {},
			dismissButton = { TextButton(onClick = vm::cancelFuture) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	if (addCompany)
		QuickCompanyDialog(onDone = { addCompany = false; vm.setCompany(it) }, onDismiss = { addCompany = false })
	if (addRole)
		QuickRoleDialog(onDone = { addRole = false; vm.setRole(it) }, onDismiss = { addRole = false })
	if (addPreset)
		QuickPresetDialog(
			f.activity, clients.firstOrNull { it.id == f.clientId }?.companyId ?: f.companyId,
			onDone = { addPreset = false; vm.setActivity(it) }, onDismiss = { addPreset = false },
		)
	if (addClient)
		QuickClientDialog(f.companyId, onDone = { client, company -> addClient = false; vm.pickNewClient(client, company) }, onDismiss = { addClient = false })
}

/* "Lavoro" green with the work icon, "Viaggio" blue with the travel icon (owner, 2026-11-11). */
@Composable
private fun StartButton(label: Int, cmd: EntryCommand, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
	val style = commandStyle(cmd)!!
	Button(
		onClick = onClick,
		enabled = enabled,
		colors = ButtonDefaults.buttonColors(containerColor = style.container, contentColor = style.content),
		modifier = modifier.height(56.dp),
	) {
		Icon(painterResource(style.icon), contentDescription = null, modifier = Modifier.size(24.dp))
		Spacer(Modifier.size(8.dp))
		Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
	}
}

/* One choice among a few, as connected buttons (issue #92: work/travel and when look alike). */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
	SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
		options.forEachIndexed { i, (value, label) ->
			SegmentedButton(
				selected = value == selected,
				onClick = { onPick(value) },
				shape = SegmentedButtonDefaults.itemShape(i, options.size),
			) { Text(label, maxLines = 1) }
		}
	}
}

/*
 * Another entry is ACTIVE (SPEC §3.1). In TRAVEL the question is whether
 * the travel is over: yes = stop it, no = keep it running, or suspend it.
 * In PAUSE suspension is not a legal command, so only stop / leave remain.
 */
@Composable
private fun RunningDialog(r: RunningEntry, vm: StartViewModel) {
	val travel = r.entry.mode == SegmentKind.TRAVEL
	val pause = r.entry.mode == SegmentKind.PAUSE
	AlertDialog(
		onDismissRequest = vm::cancelStart,
		title = {
			Text(
				if (travel) stringResource(R.string.travel_done_title)
				else stringResource(R.string.running_title, r.title),
			)
		},
		text = { if (travel) Text(r.title) },
		confirmButton = {
			Column(horizontalAlignment = Alignment.End) {
				TextButton(onClick = { vm.decide(EntryCommand.STOP) }) {
					Text(stringResource(if (travel) R.string.action_yes else R.string.running_close))
				}
				/* no concurrent entries (policy, issue #48): the running one is closed or suspended */
				if (LocalPolicy.current.enabled(Feature.CONCURRENT_ENTRIES))
					TextButton(onClick = { vm.decide(null) }) {
						Text(stringResource(if (travel) R.string.action_no else R.string.running_leave))
					}
				if (!pause)
					TextButton(onClick = { vm.decide(EntryCommand.SUSPEND) }) {
						Text(stringResource(if (travel) R.string.travel_suspend else R.string.running_suspend))
					}
			}
		},
	)
}
