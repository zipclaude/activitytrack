/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.location.CurrentPosition
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.commandStyle
import it.infraconsulting.activitytrack.ui.common.reasonText

@Composable
fun StartScreen(onBack: () -> Unit) {
	val context = LocalContext.current.applicationContext
	val vm = appViewModel { StartViewModel(it.entries, it.masterData) { CurrentPosition.get(context) } }
	val f by vm.form.collectAsState()
	val companies by vm.companies.collectAsState()
	val clients by vm.clients.collectAsState()
	val roles by vm.roles.collectAsState()
	val presets by vm.presets.collectAsState()
	val pending by vm.pending.collectAsState()
	val error by vm.error.collectAsState()
	val none = stringResource(R.string.field_none)

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
					Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
						ChoiceField(
							stringResource(R.string.field_company),
							listOf<Pair<Long?, String>>(null to stringResource(R.string.all_companies)) +
								companies.filter { !it.archived || it.id == f.companyId }.map { it.id to it.name },
							f.companyId,
						) { vm.setCompany(it) }
					}
				}
				item { SectionHeader(stringResource(R.string.field_client)) }
				if (f.mode == SegmentKind.TRAVEL)
					item { ClientRow(stringResource(R.string.client_unknown), f.clientId == null) { vm.setClient(null) } }
				if (clients.isEmpty())
					item { EmptyText(stringResource(R.string.start_no_clients)) }
				items(clients, key = { it.id }) { c ->
					ClientRow(c.name, f.clientId == c.id) { vm.setClient(c.id) }
				}
				item {
					Column(
						modifier = Modifier.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						val companyId = clients.firstOrNull { it.id == f.clientId }?.companyId ?: f.companyId
						val usable = presets.filter { it.companyId == null || it.companyId == companyId }
						if (usable.isNotEmpty())
							ChoiceField(
								stringResource(R.string.field_preset),
								listOf<Pair<Long?, String>>(null to none) + usable.map { it.id to it.text },
								null,
							) { id -> id?.let(vm::applyPreset) }
						TextInput(stringResource(R.string.field_activity), f.activity, vm::setActivity, singleLine = false)
						ChoiceField(
							stringResource(R.string.field_role),
							listOf<Pair<Long?, String>>(null to none) +
								roles.filter { !it.archived || it.id == f.roleId }.map { it.id to it.name },
							f.roleId,
						) { vm.setRole(it) }
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
}

@Composable
private fun ClientRow(name: String, selected: Boolean, onClick: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
			.padding(horizontal = 16.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = selected, onClick = null)
		Text(name, modifier = Modifier.padding(start = 12.dp))
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
