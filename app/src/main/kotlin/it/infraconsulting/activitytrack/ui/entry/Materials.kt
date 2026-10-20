/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.EntryMaterialEntity
import it.infraconsulting.activitytrack.data.db.MaterialEntity
import it.infraconsulting.activitytrack.data.repo.MaterialRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.parseDecimal
import java.math.BigDecimal
import java.math.RoundingMode

class MaterialsViewModel(private val repo: MaterialRepository) : ActionViewModel() {
	val catalogue = repo.materials.state(emptyList())
	fun lines(entryId: Long) = repo.lines(entryId)

	fun save(line: EntryMaterialEntity, done: () -> Unit) = run(done) { repo.saveLine(line) }
	fun delete(line: EntryMaterialEntity, done: () -> Unit) = run(done) { repo.deleteLine(line.id) }
	fun saveMaterial(m: MaterialEntity, done: () -> Unit) = run(done) { repo.saveMaterial(m) }
	fun deleteMaterial(m: MaterialEntity, done: () -> Unit) = run(done) { repo.deleteMaterial(m.id) }
	fun invalid(r: Reason) = fail(r)
}

@Composable
internal fun materialsViewModel() = appViewModel(key = "materials") { MaterialsViewModel(it.materials) }

/* "12,50" → 1250 cents; blank → null; anything else → failure */
internal fun centsOf(text: String): Result<Long?> = runCatching {
	if (text.isBlank()) null
	else parseDecimal(text)?.let { BigDecimal.valueOf(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() }
		?: throw ValidationException(Reason.INVALID_AMOUNT)
}

internal fun centsText(cents: Long?): String = cents?.let { BigDecimal.valueOf(it, 2).toPlainString().replace('.', ',') }.orEmpty()

private fun quantityText(q: Double?): String =
	q?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString().replace('.', ',') }.orEmpty()

/* "2 pz × 3,50 € = 7,00 €" or just the description's details that are known */
internal fun lineDetail(l: EntryMaterialEntity): String = listOfNotNull(
	l.quantity?.let { quantityText(it) + (l.unit?.let { u -> " $u" } ?: "") },
	l.unitCostCents?.let { "× " + formatEur(it) },
	l.totalCents?.takeIf { l.quantity != null }?.let { "= " + formatEur(it) },
).joinToString(" ")

/* The consumables left with the entry (issue #41), with their total; shown once there is one. */
@Composable
fun MaterialsSection(entryId: Long) {
	val vm = materialsViewModel()
	val lines by remember(entryId) { vm.lines(entryId) }.collectAsState(emptyList())
	var editing by remember { mutableStateOf<EntryMaterialEntity?>(null) }
	if (lines.isEmpty())
		return
	Column(modifier = Modifier.padding(bottom = 8.dp)) {
		val total = lines.mapNotNull { it.totalCents }.sum()
		SectionHeader(stringResource(R.string.materials) + if (total > 0) " · " + formatEur(total) else "")
		for (l in lines)
			ClickableRow(l.description, lineDetail(l).ifEmpty { null }) { vm.clearError(); editing = l }
	}
	editing?.let { l -> MaterialLineDialog(l, vm) { editing = null } }
}

/* "Material" from the add row at the top of the entry screen, outside the lazy list (issue #41). */
@Composable
fun MaterialAdder(entryId: Long, request: AddRequest?, onHandled: () -> Unit) {
	val vm = materialsViewModel()
	var adding by remember { mutableStateOf(false) }
	LaunchedEffect(request) {
		if (request == AddRequest.MATERIAL) {
			vm.clearError()
			adding = true
			onHandled()
		}
	}
	if (adding)
		MaterialLineDialog(EntryMaterialEntity(entryId = entryId, description = "", createdUtcMs = 0), vm) { adding = false }
}

/* Description (required), quantity, unit, unit cost; a pick from the master data fills them. */
@Composable
private fun MaterialLineDialog(initial: EntryMaterialEntity, vm: MaterialsViewModel, onClose: () -> Unit) {
	val error by vm.error.collectAsState()
	val catalogue by vm.catalogue.collectAsState()
	var materialId by remember { mutableStateOf(initial.materialId) }
	var description by remember { mutableStateOf(initial.description) }
	var quantity by remember { mutableStateOf(quantityText(initial.quantity)) }
	var unit by remember { mutableStateOf(initial.unit.orEmpty()) }
	var cost by remember { mutableStateOf(centsText(initial.unitCostCents)) }
	EditDialog(
		title = stringResource(R.string.material_line),
		error = error,
		onDismiss = { vm.clearError(); onClose() },
		onConfirm = {
			val q = if (quantity.isBlank()) null else parseDecimal(quantity)
			val c = centsOf(cost)
			if ((quantity.isNotBlank() && q == null) || c.isFailure) {
				vm.invalid(Reason.INVALID_AMOUNT)
				return@EditDialog
			}
			vm.save(initial.copy(materialId = materialId, description = description, quantity = q, unit = unit, unitCostCents = c.getOrNull()), onClose)
		},
		onDelete = if (initial.id != 0L) ({ vm.delete(initial, onClose) }) else null,
	) {
		val usable = catalogue.filter { !it.archived || it.id == materialId }
		if (usable.isNotEmpty())
			SearchableChoiceField(
				stringResource(R.string.material_from_list),
				listOf<Pair<Long?, String>>(null to stringResource(R.string.field_none)) + usable.map { it.id to it.name },
				materialId,
				importance = Importance.OPTIONAL,
			) { id ->
				materialId = id
				usable.firstOrNull { it.id == id }?.let { m ->
					description = m.name
					unit = m.unit.orEmpty()
					cost = centsText(m.unitCostCents)
				}
			}
		TextInput(stringResource(R.string.field_description), description, { description = it }, importance = Importance.REQUIRED)
		TextInput(stringResource(R.string.material_quantity), quantity, { quantity = it }, KeyboardType.Decimal, importance = Importance.OPTIONAL)
		TextInput(stringResource(R.string.material_unit), unit, { unit = it }, importance = Importance.OPTIONAL)
		TextInput(stringResource(R.string.material_unit_cost), cost, { cost = it }, KeyboardType.Decimal, importance = Importance.OPTIONAL)
		if (error == null)
			Text(stringResource(R.string.material_hint), style = MaterialTheme.typography.bodySmall)
	}
}
