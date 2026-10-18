/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.todo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.TodoRepository
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.AddButton
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.parseDate
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import it.infraconsulting.activitytrack.data.repo.ValidationException
import java.time.LocalDate

/* To-dos (M4): list, add/edit in the app, start an entry, mark done. */
class TodosViewModel(private val todos: TodoRepository, master: MasterDataRepository) : ActionViewModel() {
	val open = todos.open.state(emptyList())
	val all = todos.all.state(emptyList())
	val clients = master.clients.state(emptyList())
	val companies = master.companies.state(emptyList())

	fun setDone(t: TodoEntity, done: Boolean) = run { todos.setDone(t.id, done) }
	fun save(t: TodoEntity, done: () -> Unit) = run(done) { todos.save(t) }

	/* a to-do belongs to a company: through its client or chosen directly */
	fun missingCompany() = fail(ValidationException.Reason.CLIENT_REQUIRED)
}

@Composable
private fun todosViewModel() = appViewModel(key = "todos") { TodosViewModel(it.todos, it.masterData) }

/* "client · due date time" under the title. */
@Composable
private fun subtitle(t: TodoEntity, clientName: String?): String {
	val due = t.dueEpochDay?.let { d ->
		val date = LocalDate.ofEpochDay(d)
		val day = if (date == LocalDate.now()) stringResource(R.string.todo_today) else date.toString()
		day + (t.dueTimeMin?.let { " ${formatMin(it)}" } ?: "")
	}
	return listOfNotNull(clientName, due, t.priority?.let { "P$it" }).joinToString(" · ")
}

/* Home section: open to-dos due today or overdue. */
@Composable
fun TodoTodaySection(onStart: (Long) -> Unit) {
	val vm = todosViewModel()
	val open by vm.open.collectAsState()
	val clients by vm.clients.collectAsState()
	val today = LocalDate.now()
	val due = open.filter { (it.dueEpochDay ?: Long.MAX_VALUE) <= today.toEpochDay() }
	if (due.isEmpty())
		return
	var selected by remember { mutableStateOf<TodoEntity?>(null) }
	Column {
		SectionHeader(stringResource(R.string.todo_due_today))
		for (t in due)
			ClickableRow(t.title, subtitle(t, clients.firstOrNull { it.id == t.clientId }?.name)) { selected = t }
	}
	selected?.let { t -> TodoActions(t, vm, onStart = { onStart(t.id) }, onEdit = null) { selected = null } }
}

@Composable
private fun TodoActions(t: TodoEntity, vm: TodosViewModel, onStart: () -> Unit, onEdit: (() -> Unit)?, onDismiss: () -> Unit) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(t.title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				t.notes?.let { Text(it) }
				if (t.refs.isNotBlank())
					Text(t.refs.lines().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
				if (!t.done)
					TextButton(onClick = { onDismiss(); onStart() }) { Text(stringResource(R.string.todo_start)) }
				TextButton(onClick = { vm.setDone(t, !t.done); onDismiss() }) {
					Text(stringResource(if (t.done) R.string.todo_reopen else R.string.todo_mark_done))
				}
				onEdit?.let { TextButton(onClick = { onDismiss(); it() }) { Text(stringResource(R.string.todo_edit)) } }
			}
		},
		confirmButton = {},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
	)
}

@Composable
fun TodosScreen(onBack: () -> Unit, onStart: (Long) -> Unit) {
	val vm = todosViewModel()
	val open by vm.open.collectAsState()
	val all by vm.all.collectAsState()
	val clients by vm.clients.collectAsState()
	val error by vm.error.collectAsState()
	var showDone by remember { mutableStateOf(false) }
	var selected by remember { mutableStateOf<TodoEntity?>(null) }
	var editing by remember { mutableStateOf<TodoEntity?>(null) }
	val list = if (showDone) all else open

	ScreenScaffold(
		title = stringResource(R.string.todo_title),
		onBack = onBack,
		actions = { AddButton { vm.clearError(); editing = TodoEntity(uid = "", companyId = 0, title = "", createdUtcMs = 0, updatedUtcMs = 0) } },
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			item { Column(Modifier.padding(horizontal = 16.dp)) { SwitchRow(stringResource(R.string.todo_show_done), showDone) { showDone = it } } }
			if (list.isEmpty())
				item { EmptyText() }
			items(list, key = { it.id }) { t ->
				val title = if (t.done) "✓ ${t.title}" else t.title
				ClickableRow(title, subtitle(t, clients.firstOrNull { it.id == t.clientId }?.name)) { selected = t }
			}
		}
	}
	selected?.let { t -> TodoActions(t, vm, onStart = { onStart(t.id) }, onEdit = { vm.clearError(); editing = t }) { selected = null } }
	editing?.let { t -> TodoEditDialog(t, vm, error) { editing = null } }
}

/* Title, client (its company follows), due date and time, priority, notes, references. */
@Composable
private fun TodoEditDialog(initial: TodoEntity, vm: TodosViewModel, error: ValidationException.Reason?, onClose: () -> Unit) {
	val clients by vm.clients.collectAsState()
	val companies by vm.companies.collectAsState()
	var title by remember { mutableStateOf(initial.title) }
	var clientId by remember { mutableStateOf(initial.clientId) }
	var companyId by remember { mutableStateOf(initial.companyId.takeIf { it != 0L } ?: companies.firstOrNull()?.id) }
	var due by remember { mutableStateOf(initial.dueEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
	var time by remember { mutableStateOf(initial.dueTimeMin?.let(::formatMin).orEmpty()) }
	var priority by remember { mutableStateOf(initial.priority?.toString().orEmpty()) }
	var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
	var refs by remember { mutableStateOf(initial.refs) }
	val coNames = companies.associate { it.id to it.name }
	EditDialog(
		title = stringResource(R.string.todo_edit),
		error = error,
		onDismiss = onClose,
		onConfirm = {
			val company = clientId?.let { id -> clients.firstOrNull { it.id == id }?.companyId } ?: companyId
			if (company == null) {
				vm.missingCompany()
				return@EditDialog
			}
			vm.save(
				initial.copy(
					title = title, companyId = company, clientId = clientId,
					dueEpochDay = due.takeIf { it.isNotBlank() }?.let(::parseDate)?.toEpochDay(),
					dueTimeMin = time.takeIf { it.isNotBlank() }?.let(::parseHhMm),
					priority = priority.trim().toIntOrNull(), notes = notes, refs = refs,
				),
				onClose,
			)
		},
	) {
		TextInput(stringResource(R.string.todo_field_title), title, { title = it })
		SearchableChoiceField(
			stringResource(R.string.field_client),
			listOf<Pair<Long?, String>>(null to stringResource(R.string.field_none)) +
				clients.filter { !it.archived }.map { it.id to "${coNames[it.companyId].orEmpty()} · ${it.name}" },
			clientId,
		) { clientId = it }
		if (clientId == null)
			ChoiceField(stringResource(R.string.field_company), companies.map { it.id to it.name }, companyId) { companyId = it }
		TextInput(stringResource(R.string.todo_field_due), due, { due = it })
		TextInput(stringResource(R.string.todo_field_time), time, { time = it })
		TextInput(stringResource(R.string.todo_field_priority), priority, { priority = it })
		TextInput(stringResource(R.string.field_notes), notes, { notes = it }, singleLine = false)
		TextInput(stringResource(R.string.todo_field_refs), refs, { refs = it }, singleLine = false)
	}
}
