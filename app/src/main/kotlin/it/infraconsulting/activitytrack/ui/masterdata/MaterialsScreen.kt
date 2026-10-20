/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.MaterialEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.ui.common.AddButton
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.entry.centsOf
import it.infraconsulting.activitytrack.ui.entry.centsText
import it.infraconsulting.activitytrack.ui.entry.formatEur
import it.infraconsulting.activitytrack.ui.entry.materialsViewModel

/* Consumables (issue #41): description, unit of measure, unit cost, optional company. */
@Composable
fun MaterialsScreen(onBack: () -> Unit) {
	val vm = materialsViewModel()
	val md = masterDataViewModel()
	val items by vm.catalogue.collectAsState()
	val companies by md.companies.collectAsState()
	val error by vm.error.collectAsState()
	var editing by remember { mutableStateOf<MaterialEntity?>(null) }

	ScreenScaffold(
		title = stringResource(R.string.md_materials),
		onBack = onBack,
		actions = { AddButton { vm.clearError(); editing = MaterialEntity(name = "") } },
	) { padding ->
		LazyColumn(Modifier.fillMaxSize().padding(padding)) {
			if (items.isEmpty())
				item { EmptyText() }
			items(items, key = { it.id }) { m ->
				val detail = listOfNotNull(
					m.unitCostCents?.let { formatEur(it) + (m.unit?.let { u -> " / $u" } ?: "") } ?: m.unit,
					m.companyId?.let { id -> companies.firstOrNull { it.id == id }?.name },
					archivedSuffix(m.archived),
				).joinToString(" · ").ifEmpty { null }
				ClickableRow(m.name, detail) { vm.clearError(); editing = m }
			}
		}
	}

	editing?.let { m ->
		var name by remember(m) { mutableStateOf(m.name) }
		var unit by remember(m) { mutableStateOf(m.unit.orEmpty()) }
		var cost by remember(m) { mutableStateOf(centsText(m.unitCostCents)) }
		var companyId by remember(m) { mutableStateOf(m.companyId) }
		var archived by remember(m) { mutableStateOf(m.archived) }
		val delete = if (m.id != 0L) confirmDelete(m.name) { vm.deleteMaterial(m) { editing = null } } else null
		EditDialog(
			stringResource(R.string.material_edit), error, { vm.clearError(); editing = null },
			{
				val c = centsOf(cost)
				if (c.isFailure)
					vm.invalid(Reason.INVALID_AMOUNT)
				else
					vm.saveMaterial(m.copy(name = name, unit = unit, unitCostCents = c.getOrNull(), companyId = companyId, archived = archived)) { editing = null }
			},
			delete,
		) {
			TextInput(stringResource(R.string.field_description), name, { name = it }, importance = Importance.REQUIRED)
			TextInput(stringResource(R.string.material_unit), unit, { unit = it }, importance = Importance.OPTIONAL)
			TextInput(stringResource(R.string.material_unit_cost), cost, { cost = it }, KeyboardType.Decimal, importance = Importance.OPTIONAL)
			ChoiceField(
				stringResource(R.string.field_company),
				listOf<Pair<Long?, String>>(null to stringResource(R.string.all_companies)) +
					companies.filter { !it.archived || it.id == companyId }.map { it.id to it.name },
				companyId,
				importance = Importance.OPTIONAL,
			) { companyId = it }
			SwitchRow(stringResource(R.string.field_archived), archived) { archived = it }
		}
	}
}
