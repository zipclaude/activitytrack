/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.ui.common.Importance
import androidx.compose.foundation.layout.Box
import it.infraconsulting.activitytrack.ui.common.MAGENTA
import it.infraconsulting.activitytrack.ui.common.ColoredActionButton
import it.infraconsulting.activitytrack.data.db.AttachmentKind
import it.infraconsulting.activitytrack.data.db.ExpenseEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.AttachmentRepository
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.parseDecimal
import it.infraconsulting.activitytrack.ui.common.reasonText
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.UUID
import kotlinx.coroutines.flow.first

/* Expenses of entries (SPEC §4): EUR amount, description, optional receipt photo. */
class ExpenseViewModel(
	private val entries: EntryRepository,
	private val attachments: AttachmentRepository,
	private val settings: SettingsRepository,
) : ActionViewModel() {
	fun expenses(entryId: Long) = entries.expenses(entryId).state(emptyList())

	/*
	 * Validates first, then copies the receipt from the cache and records
	 * the expense: an invalid form leaves no orphan receipt.
	 */
	fun save(entryId: Long, amount: String, description: String, receipt: File?, done: () -> Unit) = run(done) {
		val cents = parseDecimal(amount)?.let { BigDecimal.valueOf(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() }
			?.takeIf { it > 0 } ?: throw ValidationException(Reason.INVALID_AMOUNT)
		if (description.isBlank())
			throw ValidationException(Reason.BLANK_VALUE)
		val receiptId = receipt?.let { f ->
			try {
				val tree = settings.current().rootUri?.toUri() ?: throw ValidationException(Reason.STORAGE)
				attachments.add(tree, entryId, AttachmentKind.RECEIPT, Uri.fromFile(f), "image/jpeg", "jpg")
			} catch (e: ValidationException) {
				throw e
			} catch (e: Exception) {
				throw ValidationException(Reason.STORAGE, e.message)
			} finally {
				f.delete()
			}
		}
		entries.addExpense(entryId, cents, description, receiptId)
	}

	fun delete(e: ExpenseEntity) = run { entries.deleteExpense(e.id) }
}

fun formatEur(cents: Long): String =
	NumberFormat.getCurrencyInstance().apply { currency = Currency.getInstance("EUR") }
		.format(BigDecimal.valueOf(cents, 2))

/* Entry screen section: list, add, delete. */
@Composable
fun ExpensesSection(entryId: Long, request: AddRequest? = null, onHandled: () -> Unit = {}) {
	val vm = appViewModel(key = "expenses/$entryId") {
		ExpenseViewModel(it.entries, it.attachments, it.settings)
	}
	val items by remember(entryId) { vm.expenses(entryId) }.collectAsState()
	var adding by remember { mutableStateOf(false) }
	var deleting by remember { mutableStateOf<ExpenseEntity?>(null) }
	/* "Expense" in the add row at the top of the screen (issue #37) */
	androidx.compose.runtime.LaunchedEffect(request) {
		if (request == AddRequest.EXPENSE) {
			vm.clearError()
			adding = true
			onHandled()
		}
	}

	if (items.isNotEmpty())
		Column(modifier = Modifier.padding(bottom = 8.dp)) {
			SectionHeader(stringResource(R.string.expenses))
			for (e in items)
				ClickableRow(
					"${formatEur(e.amountCents)} · ${e.description}",
					if (e.attachmentId != null) stringResource(R.string.att_receipt) else null,
				) { deleting = e }
		}
	if (adding) {
		val error by vm.error.collectAsState()
		ExpenseFormDialog(error, onDismiss = { adding = false }) { amount, text, receipt ->
			vm.save(entryId, amount, text, receipt) { adding = false }
		}
	}
	deleting?.let { e ->
		AlertDialog(
			onDismissRequest = { deleting = null },
			text = { Text(stringResource(R.string.confirm_delete, "${formatEur(e.amountCents)} · ${e.description}")) },
			confirmButton = {
				TextButton(onClick = { vm.delete(e); deleting = null }) {
					Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
}

/* Amount, description and an optional receipt photo kept in the cache until saved. */
@Composable
private fun ExpenseFields(
	amount: String,
	onAmount: (String) -> Unit,
	description: String,
	onDescription: (String) -> Unit,
	receipt: String?,
	onReceipt: (String?) -> Unit,
) {
	val context = LocalContext.current
	var pending by rememberSaveable { mutableStateOf<String?>(null) }
	val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
		val f = pending ?: return@rememberLauncherForActivityResult
		pending = null
		if (ok) {
			receipt?.let { File(it).delete() }
			onReceipt(f)
		} else
			File(f).delete()
	}
	TextInput(stringResource(R.string.expense_amount), amount, onAmount, KeyboardType.Decimal, importance = Importance.REQUIRED)
	TextInput(stringResource(R.string.expense_description), description, onDescription, importance = Importance.REQUIRED)
	OutlinedButton(onClick = {
		val f = receiptFile(context)
		pending = f.path
		takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
	}) { Text(stringResource(if (receipt == null) R.string.expense_receipt else R.string.expense_receipt_again)) }
}

private fun receiptFile(context: Context): File =
	File(context.cacheDir, "captures").apply { mkdirs() }.let { File(it, "${UUID.randomUUID()}.jpg") }

@Composable
private fun ExpenseFormDialog(error: Reason?, onDismiss: () -> Unit, onSave: (String, String, File?) -> Unit) {
	var amount by rememberSaveable { mutableStateOf("") }
	var description by rememberSaveable { mutableStateOf("") }
	var receipt by rememberSaveable { mutableStateOf<String?>(null) }
	EditDialog(
		title = stringResource(R.string.expense_add),
		error = error,
		onDismiss = { receipt?.let { File(it).delete() }; onDismiss() },
		onConfirm = { onSave(amount, description, receipt?.let(::File)) },
	) {
		ExpenseFields(amount, { amount = it }, description, { description = it }, receipt) { receipt = it }
	}
}

/*
 * Quick expense (DESIGN §10, from the widget and the launcher shortcut): for
 * the most recent ACTIVE entry, else the most recent open one.
 */
@Composable
fun QuickExpenseScreen(onBack: () -> Unit) {
	val context = LocalContext.current
	val container = (context.applicationContext as ActivityTrackApp).container
	val vm = appViewModel(key = "quick-expense") { ExpenseViewModel(it.entries, it.attachments, it.settings) }
	val error by vm.error.collectAsState()
	val target = produceState<Pair<TimeEntryEntity, String>?>(null) {
		value = quickTarget(container.entries, container.masterData)
	}.value
	var amount by rememberSaveable { mutableStateOf("") }
	var description by rememberSaveable { mutableStateOf("") }
	var receipt by rememberSaveable { mutableStateOf<String?>(null) }

	ScreenScaffold(title = stringResource(R.string.expense_add), onBack = onBack) { padding ->
		Column(
			modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			if (target == null) {
				EmptyText(stringResource(R.string.expense_no_entry))
				return@Column
			}
			Text(target.second, style = MaterialTheme.typography.titleMedium)
			ExpenseFields(amount, { amount = it }, description, { description = it }, receipt) { receipt = it }
			error?.let { Text(reasonText(it), color = MaterialTheme.colorScheme.error) }
			Button(
				onClick = { vm.save(target.first.id, amount, description, receipt?.let(::File)) { onBack() } },
				modifier = Modifier.fillMaxWidth(),
			) { Text(stringResource(R.string.action_save)) }
		}
	}
}

private suspend fun quickTarget(entries: EntryRepository, master: MasterDataRepository): Pair<TimeEntryEntity, String>? {
	val open = entries.openEntries.first()
	val e = open.firstOrNull { it.status == EntryStatus.ACTIVE } ?: open.firstOrNull() ?: return null
	val name = e.clientId?.let { master.client(it)?.name } ?: e.companyId?.let { master.company(it)?.name } ?: ""
	return e to name
}
