/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

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
fun StartScreen(onBack: () -> Unit, clientId: Long? = null, todoId: Long? = null, travel: Boolean = false) {
	val context = LocalContext.current.applicationContext
	val vm = appViewModel {
		StartViewModel(it.entries, it.masterData, clientId, todoId, it.todos, { CurrentPosition.get(context) }, it.settings.settings.map { s -> s.editableTimes })
			.also { vm -> if (travel) vm.setMode(SegmentKind.TRAVEL) }
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
	var addCompany by remember { mutableStateOf(false) }
	var addClient by remember { mutableStateOf(false) }
	var addRole by remember { mutableStateOf(false) }
	var addPreset by remember { mutableStateOf(false) }

	ScreenScaffold(title = stringResource(R.string.start_title), onBack = onBack) { padding ->
		Column(modifier = Modifier.fillMaxSize().padding(padding)) {
			LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
				item {
					Row(
						modifier = Modifier.padding(horizontal = 16.dp),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						FilterChip(
							selected = f.mode == SegmentKind.WORK,
							onClick = { vm.setMode(SegmentKind.WORK) },
							label = { Text(stringResource(R.string.cmd_work)) },
						)
						FilterChip(
							selected = f.mode == SegmentKind.TRAVEL,
							onClick = { vm.setMode(SegmentKind.TRAVEL) },
							label = { Text(stringResource(R.string.cmd_travel)) },
						)
					}
				}
				item {
					Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
						WithAddButton(stringResource(R.string.quick_company), { addCompany = true }) {
							ChoiceField(
								stringResource(R.string.field_company),
								listOf<Pair<Long?, String>>(null to stringResource(R.string.all_companies)) +
									companies.filter { !it.archived || it.id == f.companyId }.map { it.id to it.name },
								f.companyId,
								importance = Importance.REQUIRED,
								colorOf = { id -> companies.firstOrNull { it.id == id }?.color },
							) { vm.setCompany(it) }
						}
					}
				}
				item {
					/* combo box (owner request): type to search, or pick from the dropdown; most recent first; "+" adds one (M11) */
					val unknown = stringResource(R.string.client_unknown)
					Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
						WithAddButton(stringResource(R.string.quick_client), { addClient = true }) {
							if (clients.isEmpty())
								EmptyText(stringResource(R.string.start_no_clients))
							else
								SearchableChoiceField(
									stringResource(R.string.field_client),
									(if (f.mode == SegmentKind.TRAVEL) listOf<Pair<Long?, String>>(null to unknown) else emptyList()) +
										clients.map { it.id to it.name },
									f.clientId,
									/* a travel may start without a destination */
									importance = if (f.mode == SegmentKind.TRAVEL) Importance.IMPORTANT else Importance.REQUIRED,
									colorOf = { id -> id?.let { clientColors[it] } },
								) { vm.setClient(it) }
						}
					}
				}
				item {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						val companyId = clients.firstOrNull { it.id == f.clientId }?.companyId ?: f.companyId
						val usable = presets.filter { it.companyId == null || it.companyId == companyId }
						/* "+" also for presets and roles (backlog 4, 2026-10-18) */
						WithAddButton(stringResource(R.string.quick_preset), { addPreset = true }) {
							ChoiceField(
								stringResource(R.string.field_preset),
								listOf<Pair<Long?, String>>(null to none) + usable.map { it.id to it.text },
								null,
								importance = Importance.OPTIONAL,
							) { id -> id?.let(vm::applyPreset) }
						}
						/* required: typed or from a preset (owner, 2026-10-19) */
						DictationInput(stringResource(R.string.field_activity), f.activity, vm::setActivity, Importance.REQUIRED)
						WithAddButton(stringResource(R.string.quick_role), { addRole = true }) {
							ChoiceField(
								stringResource(R.string.field_role),
								listOf<Pair<Long?, String>>(null to none) +
									roles.filter { !it.archived || it.id == f.roleId }.map { it.id to it.name },
								f.roleId,
								importance = Importance.IMPORTANT,
							) { vm.setRole(it) }
						}
						/* M17: start in the past; empty = now */
						if (editableTimes) {
							TimeField(stringResource(R.string.field_start), f.startTime, vm::setStartTime, clearable = true, importance = Importance.OPTIONAL)
							Text(stringResource(R.string.time_now_hint), style = MaterialTheme.typography.bodySmall)
						}
						if (error != null)
							Text(reasonText(error!!), color = MaterialTheme.colorScheme.error)
					}
				}
			}
			/* green play (owner request); the label stays for accessibility */
			val start = commandStyle(if (f.mode == SegmentKind.TRAVEL) EntryCommand.TRAVEL else EntryCommand.WORK)!!
			val label = stringResource(R.string.start_action)
			Button(
				onClick = { vm.requestStart(onBack) },
				enabled = f.canStart && pending.isEmpty(),
				colors = ButtonDefaults.buttonColors(containerColor = start.container, contentColor = start.content),
				modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
			) { Icon(painterResource(start.icon), contentDescription = label, modifier = Modifier.size(32.dp)) }
		}
	}

	pending.firstOrNull()?.let { r -> RunningDialog(r, vm) }
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
