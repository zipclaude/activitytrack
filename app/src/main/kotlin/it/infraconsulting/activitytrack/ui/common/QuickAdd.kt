/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.ActivityPresetEntity
import it.infraconsulting.activitytrack.data.db.RoleEntity
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel

/*
 * New company or client from the screens that pick them (M11, owner
 * 2026-10-18): a name (and the company, for a client), then the new one is
 * selected; the full master data stay in Master data.
 */
class QuickAddViewModel(private val master: MasterDataRepository) : ActionViewModel() {
	val companies = master.companies.state(emptyList())
	val roles = master.roles.state(emptyList())

	fun addCompany(name: String, done: (Long) -> Unit) {
		var id = 0L
		run({ done(id) }) { id = master.saveCompany(CompanyEntity(name = name)) }
	}

	fun addRole(name: String, done: (Long) -> Unit) {
		var id = 0L
		run({ done(id) }) { id = master.saveRole(RoleEntity(name = name)) }
	}

	/* [companyId] null = a preset for every company */
	fun addPreset(text: String, companyId: Long?, roleId: Long?, done: (String) -> Unit) {
		run({ done(text.trim()) }) { master.savePreset(ActivityPresetEntity(text = text, companyId = companyId, defaultRoleId = roleId)) }
	}

	/* [withoutCompany]: the policy hides the companies, the client belongs to none (issue #48) */
	fun addClient(companyId: Long?, name: String, withoutCompany: Boolean, done: (Long, Long?) -> Unit) {
		if (companyId == null && !withoutCompany) {
			fail(it.infraconsulting.activitytrack.data.repo.ValidationException.Reason.CLIENT_REQUIRED)
			return
		}
		var id = 0L
		run({ done(id, companyId) }) { id = master.saveClient(ClientEntity(companyId = companyId, name = name)) }
	}
}

@Composable
private fun quickAddViewModel() = appViewModel(key = "quick-add") { QuickAddViewModel(it.masterData) }

/* [field] with a "+" beside it. */
@Composable
fun WithAddButton(label: String, onAdd: () -> Unit, masterData: Boolean = false, field: @Composable () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(Modifier.weight(1f)) { field() }
		/* companies, clients and contacts may come from imports only (policy masterData, issue #48) */
		if (!masterData || canCreateMasterData())
			IconButton(onClick = onAdd) { Icon(painterResource(R.drawable.ic_add), contentDescription = label) }
	}
}

@Composable
fun QuickCompanyDialog(onDone: (Long) -> Unit, onDismiss: () -> Unit) {
	val vm = quickAddViewModel()
	val error by vm.error.collectAsState()
	var name by remember { mutableStateOf("") }
	EditDialog(
		title = stringResource(R.string.quick_company),
		error = error,
		onDismiss = { vm.clearError(); onDismiss() },
		onConfirm = { vm.addCompany(name) { onDone(it) } },
	) { TextInput(stringResource(R.string.field_name), name, { name = it }, importance = Importance.REQUIRED) }
}

@Composable
fun QuickRoleDialog(onDone: (Long) -> Unit, onDismiss: () -> Unit) {
	val vm = quickAddViewModel()
	val error by vm.error.collectAsState()
	var name by remember { mutableStateOf("") }
	EditDialog(
		title = stringResource(R.string.quick_role),
		error = error,
		onDismiss = { vm.clearError(); onDismiss() },
		onConfirm = { vm.addRole(name, onDone) },
	) { TextInput(stringResource(R.string.field_name), name, { name = it }, importance = Importance.REQUIRED) }
}

/*
 * A preset (activity text, optional company and default role); [text]
 * starts from the activity typed so far. done gets the saved text.
 */
@Composable
fun QuickPresetDialog(text: String, companyId: Long?, onDone: (String) -> Unit, onDismiss: () -> Unit) {
	val vm = quickAddViewModel()
	val error by vm.error.collectAsState()
	val companies by vm.companies.collectAsState()
	val roles by vm.roles.collectAsState()
	var value by remember { mutableStateOf(text) }
	var company by remember { mutableStateOf(companyId) }
	var role by remember { mutableStateOf<Long?>(null) }
	var addRole by remember { mutableStateOf(false) }
	var addCompany by remember { mutableStateOf(false) }
	val none = stringResource(R.string.field_none)
	EditDialog(
		title = stringResource(R.string.quick_preset),
		error = error,
		onDismiss = { vm.clearError(); onDismiss() },
		onConfirm = { vm.addPreset(value, company, role, onDone) },
	) {
		TextInput(stringResource(R.string.field_activity), value, { value = it }, importance = Importance.REQUIRED)
		WithAddButton(stringResource(R.string.quick_company), { addCompany = true }) {
			ChoiceField(
				stringResource(R.string.field_company),
				listOf<Pair<Long?, String>>(null to stringResource(R.string.all_companies)) + companies.filter { !it.archived }.map { it.id to it.name },
				company,
			) { company = it }
		}
		WithAddButton(stringResource(R.string.quick_role), { addRole = true }) {
			ChoiceField(
				stringResource(R.string.field_role),
				listOf<Pair<Long?, String>>(null to none) + roles.filter { !it.archived }.map { it.id to it.name },
				role,
			) { role = it }
		}
	}
	if (addCompany)
		QuickCompanyDialog(onDone = { addCompany = false; company = it }, onDismiss = { addCompany = false })
	if (addRole)
		QuickRoleDialog(onDone = { addRole = false; role = it }, onDismiss = { addRole = false })
}

/* [companyId]: proposed company (the current filter); done gets the client and its company. */
@Composable
fun QuickClientDialog(companyId: Long?, onDone: (Long, Long?) -> Unit, onDismiss: () -> Unit) {
	val withCompanies = companiesShown()
	val vm = quickAddViewModel()
	val error by vm.error.collectAsState()
	val companies by vm.companies.collectAsState()
	var name by remember { mutableStateOf("") }
	var company by remember { mutableStateOf(companyId) }
	var addCompany by remember { mutableStateOf(false) }
	val chosen = company ?: companies.singleOrNull()?.id
	EditDialog(
		title = stringResource(R.string.quick_client),
		error = error,
		onDismiss = { vm.clearError(); onDismiss() },
		onConfirm = { vm.addClient(chosen.takeIf { withCompanies }, name, !withCompanies, onDone) },
	) {
		TextInput(stringResource(R.string.field_name), name, { name = it }, importance = Importance.REQUIRED)
		if (withCompanies)
			WithAddButton(stringResource(R.string.quick_company), { addCompany = true }, masterData = true) {
				ChoiceField(
					fieldLabel(it.infraconsulting.activitytrack.domain.policy.Field.COMPANY, R.string.field_company),
					companies.filter { !it.archived }.map { it.id to it.name }, chosen,
					importance = Importance.REQUIRED, colorOf = { id -> companies.firstOrNull { it.id == id }?.color },
				) { company = it }
			}
	}
	if (addCompany)
		QuickCompanyDialog(onDone = { addCompany = false; company = it }, onDismiss = { addCompany = false })
}
