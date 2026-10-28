/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.ui.common.enabled
import it.infraconsulting.activitytrack.ui.common.fieldShown
import it.infraconsulting.activitytrack.ui.common.fieldImportance
import it.infraconsulting.activitytrack.ui.common.fieldLabel
import it.infraconsulting.activitytrack.ui.common.PolicyField
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.ui.common.DateField
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import java.time.ZoneId
import it.infraconsulting.activitytrack.ui.common.clockOf
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.TimeGridSheet
import kotlinx.coroutines.flow.map
import it.infraconsulting.activitytrack.ui.common.TimeField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.QuickPresetDialog
import it.infraconsulting.activitytrack.ui.common.QuickRoleDialog
import it.infraconsulting.activitytrack.ui.common.WithAddButton
import it.infraconsulting.activitytrack.ui.common.QuickCompanyDialog
import it.infraconsulting.activitytrack.ui.common.QuickClientDialog
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.location.CurrentPosition
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.DictationInput
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.commandStyle
import it.infraconsulting.activitytrack.ui.common.reasonText

@Composable
fun StartScreen(onBack: () -> Unit, clientId: Long? = null, todoId: Long? = null, travel: Boolean = false, call: CallPrefill? = null) {
	val context = LocalContext.current.applicationContext
	val vm = appViewModel {
		StartViewModel(it.entries, it.masterData, clientId, todoId, it.todos, { CurrentPosition.get(context) }, it.settings.settings.map { s -> s.editableTimes }, { it.policies.current.value })
			.also { vm -> if (travel) vm.setMode(SegmentKind.TRAVEL) }
			.also { vm ->
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
	val askEnded by vm.askEnded.collectAsState()
	/* kept across a rotation (issue #104): the grid stays open */
	var rangeGrid by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
	val none = stringResource(R.string.field_none)
	/* read here: the lazy list's builder is not composable */
	val policy = it.infraconsulting.activitytrack.ui.common.LocalPolicy.current
	var addCompany by remember { mutableStateOf(false) }
	var addClient by remember { mutableStateOf(false) }
	var addRole by remember { mutableStateOf(false) }
	var addPreset by remember { mutableStateOf(false) }

	ScreenScaffold(title = stringResource(R.string.start_title), onBack = onBack) { padding ->
		/* the keyboard shrinks the form, so the field being typed in scrolls into view (issue #47) */
		Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
			LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
				/* work or travel, and when (issue #92): two selectors of the same kind */
				item {
					Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
						if (enabled(Feature.TRAVEL))
							Segmented(
								listOf(SegmentKind.WORK to stringResource(R.string.cmd_work), SegmentKind.TRAVEL to stringResource(R.string.cmd_travel)),
								f.mode, vm::setMode,
							)
						if (editableTimes)
							Segmented(
								listOf(
									StartWhen.NOW to stringResource(R.string.start_when_now),
									StartWhen.STARTED to stringResource(R.string.start_when_started),
									StartWhen.PAST to stringResource(R.string.start_when_past),
								),
								f.start, { vm.setStart(it); vm.clearError() },
							)
					}
				}
				if (policy.field(Field.COMPANY).visible) item {
					Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
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
					}
				}
				if (policy.field(Field.CLIENT).visible) item {
					/* combo box (owner request): type to search, or pick from the dropdown; most recent first; "+" adds one (M11) */
					val unknown = stringResource(R.string.client_unknown)
					Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
						WithAddButton(stringResource(R.string.quick_client), { addClient = true }, masterData = true) { PolicyField(Field.CLIENT) {
							if (clients.isEmpty())
								EmptyText(stringResource(R.string.start_no_clients))
							else
								SearchableChoiceField(
									fieldLabel(Field.CLIENT, R.string.field_client),
									(if (f.mode == SegmentKind.TRAVEL) listOf<Pair<Long?, String>>(null to unknown) else emptyList()) +
										clients.map { it.id to it.name },
									f.clientId,
									/* a travel may start without a destination */
									importance = if (f.mode == SegmentKind.TRAVEL) Importance.IMPORTANT else Importance.REQUIRED,
									colorOf = { id -> id?.let { clientColors[it] } },
								) { vm.setClient(it) }
						} }
					}
				}
				item {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						/* order (issue #92): what is done first, then the role, then the times the mode asks */
						val companyId = clients.firstOrNull { it.id == f.clientId }?.companyId ?: f.companyId
						val usable = presets.filter { it.companyId == null || it.companyId == companyId }
						/* "+" also for presets and roles (backlog 4, 2026-10-18) */
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
									listOf<Pair<Long?, String>>(null to none) +
										roles.filter { !it.archived || it.id == f.roleId }.map { it.id to it.name },
									f.roleId,
									importance = fieldImportance(Field.ROLE, Importance.IMPORTANT),
								) { vm.setRole(it) }
							} }
						when (f.start.takeIf { editableTimes } ?: StartWhen.NOW) {
							StartWhen.NOW -> Text(stringResource(R.string.start_when_now_hint), style = MaterialTheme.typography.bodySmall)
							StartWhen.STARTED -> {
								/* only the start: today, or yesterday for a time still to come (asked) */
								TimeField(
									stringResource(R.string.start_started_at), f.startTime, { vm.setStartTime(it); vm.clearError() },
									clearable = true, importance = Importance.IMPORTANT,
								)
								androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
									for (back in listOf(15, 30, 60))
										androidx.compose.material3.AssistChip(
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
								/* the day (issue #44): empty = today */
								if (fieldShown(Field.DATE))
									PolicyField(Field.DATE) {
										DateField(fieldLabel(Field.DATE, R.string.field_date), f.date, { vm.setDate(it); vm.clearError() }, clearable = true, importance = fieldImportance(Field.DATE, Importance.OPTIONAL))
									}
								/* start and end on one row (issue #37), both from the range grid */
								Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
									Column(Modifier.weight(1f)) {
										TimeField(
											fieldLabel(Field.TIMES, R.string.field_start), f.startTime, { vm.setStartTime(it); vm.clearError() },
											clearable = true, importance = Importance.IMPORTANT,
											onOpen = if (f.startTime.isBlank()) ({ rangeGrid = true }) else null,
										)
									}
									Column(Modifier.weight(1f)) {
										TimeField(
											stringResource(R.string.field_end), f.endTime, { vm.setEndTime(it); vm.clearError() },
											clearable = true, importance = Importance.IMPORTANT, onOpen = { rangeGrid = true },
										)
									}
								}
							}
						}
						if (error != null)
							Text(reasonText(error!!), color = MaterialTheme.colorScheme.error)
					}
				}
			}
			/* the button says what will happen (issue #92) */
			val start = commandStyle(if (f.mode == SegmentKind.TRAVEL) EntryCommand.TRAVEL else EntryCommand.WORK)!!
			val from = parseHhMm(f.startTime)?.takeIf { it < 1440 }
			val to = parseHhMm(f.endTime)
			val label = when {
				!editableTimes || f.start == StartWhen.NOW || from == null -> stringResource(R.string.start_now_action)
				f.start == StartWhen.PAST && to != null -> stringResource(R.string.start_record_action, formatMin(from), formatMin(to % 1440))
				else -> stringResource(R.string.start_from_action, formatMin(from))
			}
			Button(
				onClick = { vm.requestStart(onBack) },
				enabled = f.canStart && pending.isEmpty(),
				colors = ButtonDefaults.buttonColors(containerColor = start.container, contentColor = start.content),
				modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
			) {
				Icon(painterResource(start.icon), contentDescription = null, modifier = Modifier.size(24.dp))
				androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
				Text(label, style = MaterialTheme.typography.titleMedium)
			}
		}
	}

	pending.firstOrNull()?.let { r -> RunningDialog(r, vm) }
	val askPlan by vm.askPlan.collectAsState()
	askPlan?.let { (day, min) ->
		val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
		AlertDialog(
			onDismissRequest = { vm.confirmPlan(false) },
			title = { Text(stringResource(R.string.plan_question, it.infraconsulting.activitytrack.ui.common.dateText(day, locale), formatMin(min))) },
			text = { Text(stringResource(R.string.plan_question_text)) },
			confirmButton = { TextButton(onClick = { vm.confirmPlan(true) }) { Text(stringResource(R.string.action_yes)) } },
			dismissButton = { TextButton(onClick = { vm.confirmPlan(false) }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
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
	askEnded?.let { end ->
		AlertDialog(
			onDismissRequest = vm::cancelEnded,
			title = { Text(stringResource(R.string.ended_title)) },
			text = { Text(stringResource(R.string.ended_text, clockOf(end, ZoneId.systemDefault()))) },
			confirmButton = { TextButton(onClick = { vm.answerEnded(true) }) { Text(stringResource(R.string.action_yes)) } },
			dismissButton = { TextButton(onClick = { vm.answerEnded(false) }) { Text(stringResource(R.string.action_no)) } },
		)
	}
	if (rangeGrid) {
		val a = parseHhMm(f.startTime)?.takeIf { it < 1440 }
		val b = parseHhMm(f.endTime)
		TimeGridSheet(
			title = stringResource(R.string.start_title),
			initial = if (a != null && b != null && b > a) a until b else null,
			range = true,
			onDismiss = { rangeGrid = false },
		) { from, to ->
			rangeGrid = false
			vm.setStartTime(formatMin(from))
			vm.setEndTime(formatMin(to))
		}
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

/* One choice among a few, as connected buttons (issue #92: work/travel and when look alike). */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
	androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
		options.forEachIndexed { i, (value, label) ->
			SegmentedButton(
				selected = value == selected,
				onClick = { onPick(value) },
				shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, options.size),
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
