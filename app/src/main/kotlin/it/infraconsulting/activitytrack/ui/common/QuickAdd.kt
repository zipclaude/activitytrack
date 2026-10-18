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

	fun addCompany(name: String, done: (Long) -> Unit) {
		var id = 0L
		run({ done(id) }) { id = master.saveCompany(CompanyEntity(name = name)) }
	}

	fun addClient(companyId: Long?, name: String, done: (Long, Long) -> Unit) {
		if (companyId == null) {
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
fun WithAddButton(label: String, onAdd: () -> Unit, field: @Composable () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(Modifier.weight(1f)) { field() }
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
	) { TextInput(stringResource(R.string.field_name), name, { name = it }) }
}

/* [companyId]: proposed company (the current filter); done gets the client and its company. */
@Composable
fun QuickClientDialog(companyId: Long?, onDone: (Long, Long) -> Unit, onDismiss: () -> Unit) {
	val vm = quickAddViewModel()
	val error by vm.error.collectAsState()
	val companies by vm.companies.collectAsState()
	var name by remember { mutableStateOf("") }
	var company by remember { mutableStateOf(companyId) }
	val chosen = company ?: companies.singleOrNull()?.id
	EditDialog(
		title = stringResource(R.string.quick_client),
		error = error,
		onDismiss = { vm.clearError(); onDismiss() },
		onConfirm = { vm.addClient(chosen, name, onDone) },
	) {
		TextInput(stringResource(R.string.field_name), name, { name = it })
		ChoiceField(stringResource(R.string.field_company), companies.filter { !it.archived }.map { it.id to it.name }, chosen) { company = it }
	}
}
