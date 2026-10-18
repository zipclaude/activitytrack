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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.TodoRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.domain.todo.dueOverdue
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
import it.infraconsulting.activitytrack.ui.common.DateField
import it.infraconsulting.activitytrack.ui.common.TimeField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* To-dos (M4, planned M9): list, add/edit in the app, plan, start an entry, mark done. */
class TodosViewModel(
	private val todos: TodoRepository,
	master: MasterDataRepository,
	settings: SettingsRepository,
) : ActionViewModel() {
	val open = todos.open.state(emptyList())
	val all = todos.all.state(emptyList())
	val clients = master.clients.state(emptyList())
	val companies = master.companies.state(emptyList())
	val settings = settings.settings.state(null)

	fun setDone(t: TodoEntity, done: Boolean) = run { todos.setDone(t.id, done) }
	fun save(t: TodoEntity, done: () -> Unit) = run(done) { todos.save(t) }
	fun postpone(t: TodoEntity, minutes: Int) = run { todos.postpone(t.id, minutes) }

	/* a to-do belongs to a company: through its client or chosen directly */
	fun missingCompany() = fail(ValidationException.Reason.CLIENT_REQUIRED)
	fun invalid(reason: ValidationException.Reason) = fail(reason)
}

@Composable
internal fun todosViewModel() = appViewModel(key = "todos") { TodosViewModel(it.todos, it.masterData, it.settings) }

/* A new to-do, planned at [day] [startMin] when given (agenda). */
internal fun newTodo(day: LocalDate? = null, startMin: Int? = null) = TodoEntity(
	uid = "", companyId = 0, title = "", createdUtcMs = 0, updatedUtcMs = 0,
	planEpochDay = day?.toEpochDay(), planStartMin = startMin,
)

/*
 * Still open past its planned duration or first hour (M9), or past its due
 * date and time (owner, 2026-10-18): "overdue" on Home and in the lists.
 */
internal fun overdue(t: TodoEntity, now: Instant = Instant.now()): Boolean {
	if (t.done)
		return false
	val zone = ZoneId.systemDefault()
	return t.plan()?.let { now.isAfter(it.end(zone, open = false, now = now)) } == true ||
		dueOverdue(t.dueEpochDay?.let(LocalDate::ofEpochDay), t.dueTimeMin, now, zone)
}

@Composable
private fun dayText(epochDay: Long): String {
	val date = LocalDate.ofEpochDay(epochDay)
	return if (date == LocalDate.now()) stringResource(R.string.todo_today) else date.toString()
}

/* "client · planned day time (min) · due date time · P1" under the title. */
@Composable
private fun subtitle(t: TodoEntity, clientName: String?): String {
	val planned = t.plan()?.let { p ->
		val text = "${dayText(p.day.toEpochDay())} ${formatMin(p.startMin)}" + (p.durationMin?.let { " ($it min)" } ?: "")
		stringResource(R.string.todo_planned, text)
	}
	val due = t.dueEpochDay?.let { d -> dayText(d) + (t.dueTimeMin?.let { " ${formatMin(it)}" } ?: "") }
	val late = stringResource(R.string.todo_overdue).takeIf { overdue(t) }
	return listOfNotNull(late, clientName, planned, due, t.priority?.let { "P$it" }).joinToString(" · ")
}

/* Home section: open to-dos due or planned today, or overdue. */
@Composable
fun TodoTodaySection(onStart: (Long) -> Unit) {
	val vm = todosViewModel()
	val open by vm.open.collectAsState()
	val clients by vm.clients.collectAsState()
	val today = LocalDate.now().toEpochDay()
	val due = open.filter { t -> listOfNotNull(t.dueEpochDay, t.planEpochDay).any { it <= today } }
		.sortedWith(compareBy({ it.planEpochDay ?: it.dueEpochDay }, { it.planStartMin ?: it.dueTimeMin ?: 1440 }))
	if (due.isEmpty())
		return
	var selected by remember { mutableStateOf<TodoEntity?>(null) }
	var editing by remember { mutableStateOf<TodoEntity?>(null) }
	Column {
		SectionHeader(stringResource(R.string.todo_due_today))
		for (t in due)
			ClickableRow(t.title, subtitle(t, clients.firstOrNull { it.id == t.clientId }?.name)) { selected = t }
	}
	selected?.let { t -> TodoActions(t, vm, onStart = { onStart(t.id) }, onEdit = { vm.clearError(); editing = t }) { selected = null } }
	editing?.let { t -> TodoEditDialog(t, vm) { editing = null } }
}

/* Start / postpone / done / edit (re-plan) for one to-do. */
@Composable
internal fun TodoActions(t: TodoEntity, vm: TodosViewModel, onStart: () -> Unit, onEdit: (() -> Unit)?, onDismiss: () -> Unit) {
	val clients by vm.clients.collectAsState()
	val settings by vm.settings.collectAsState()
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(t.title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(subtitle(t, clients.firstOrNull { it.id == t.clientId }?.name), style = MaterialTheme.typography.bodySmall)
				t.notes?.let { Text(it) }
				if (t.refs.isNotBlank())
					Text(t.refs.lines().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
				if (!t.done)
					TextButton(onClick = { onDismiss(); onStart() }) { Text(stringResource(R.string.todo_start)) }
				val snooze = settings?.planSnoozeMin
				if (!t.done && t.plan() != null && snooze != null)
					TextButton(onClick = { vm.postpone(t, snooze); onDismiss() }) { Text(stringResource(R.string.action_postpone, snooze)) }
				TextButton(onClick = { vm.setDone(t, !t.done); onDismiss() }) {
					Text(stringResource(if (t.done) R.string.todo_reopen else R.string.todo_mark_done))
				}
				onEdit?.let {
					TextButton(onClick = { onDismiss(); it() }) {
						Text(stringResource(if (t.plan() != null) R.string.action_replan else R.string.todo_edit))
					}
				}
			}
		},
		confirmButton = {},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
	)
}

/* [editId]: open that to-do in the editor at once (re-plan from the reminder). */
@Composable
fun TodosScreen(onBack: () -> Unit, editId: Long? = null, onStart: (Long) -> Unit) {
	val vm = todosViewModel()
	val open by vm.open.collectAsState()
	val all by vm.all.collectAsState()
	val clients by vm.clients.collectAsState()
	var showDone by remember { mutableStateOf(false) }
	var selected by remember { mutableStateOf<TodoEntity?>(null) }
	var editing by remember { mutableStateOf<TodoEntity?>(null) }
	var opened by rememberSaveable { mutableStateOf(false) }
	val list = if (showDone) all else open

	LaunchedEffect(editId, all) {
		if (editId != null && !opened)
			all.firstOrNull { it.id == editId }?.let {
				vm.clearError()
				editing = it
				opened = true
			}
	}

	ScreenScaffold(
		title = stringResource(R.string.todo_title),
		onBack = onBack,
		actions = { AddButton { vm.clearError(); editing = newTodo() } },
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
	editing?.let { t -> TodoEditDialog(t, vm) { editing = null } }
}

/* "" -> null; anything else must parse, or the field is reported. */
private inline fun <T> optional(text: String, parse: (String) -> T?): Result<T?> =
	if (text.isBlank()) Result.success(null) else parse(text)?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(text))

/*
 * Title, client (its company follows), planned date, time and duration,
 * due date and time, priority, notes, references.
 */
@Composable
internal fun TodoEditDialog(initial: TodoEntity, vm: TodosViewModel, onClose: () -> Unit) {
	val clients by vm.clients.collectAsState()
	val companies by vm.companies.collectAsState()
	val error by vm.error.collectAsState()
	var title by remember { mutableStateOf(initial.title) }
	var clientId by remember { mutableStateOf(initial.clientId) }
	/* the companies may still be loading when the dialog opens (agenda): default to the first once known */
	var picked by remember { mutableStateOf(initial.companyId.takeIf { it != 0L }) }
	val companyId = picked ?: companies.firstOrNull()?.id
	var planDay by remember { mutableStateOf(initial.planEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
	var planTime by remember { mutableStateOf(initial.planStartMin?.let(::formatMin).orEmpty()) }
	var duration by remember { mutableStateOf(initial.durationMin?.toString().orEmpty()) }
	var due by remember { mutableStateOf(initial.dueEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
	var time by remember { mutableStateOf(initial.dueTimeMin?.let(::formatMin).orEmpty()) }
	var priority by remember { mutableStateOf(initial.priority?.toString().orEmpty()) }
	var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
	var refs by remember { mutableStateOf(initial.refs) }
	val coNames = companies.associate { it.id to it.name }
	EditDialog(
		title = stringResource(if (initial.id == 0L) R.string.todo_new else R.string.todo_edit),
		error = error,
		onDismiss = onClose,
		onConfirm = {
			val company = clientId?.let { id -> clients.firstOrNull { it.id == id }?.companyId } ?: companyId
			if (company == null) {
				vm.missingCompany()
				return@EditDialog
			}
			val pd = optional(planDay) { parseDate(it)?.toEpochDay() }
			val dd = optional(due) { parseDate(it)?.toEpochDay() }
			if (pd.isFailure || dd.isFailure) {
				vm.invalid(ValidationException.Reason.INVALID_DATE)
				return@EditDialog
			}
			/* 24:00 is an end, not a start */
			val pt = optional(planTime) { parseHhMm(it)?.takeIf { m -> m < 1440 } }
			val dt = optional(time, ::parseHhMm)
			if (pt.isFailure || dt.isFailure) {
				vm.invalid(ValidationException.Reason.INVALID_TIME)
				return@EditDialog
			}
			val dur = optional(duration) { it.trim().toIntOrNull()?.takeIf { d -> d > 0 } }
			if (dur.isFailure) {
				vm.invalid(ValidationException.Reason.INVALID_TIME_RANGE)
				return@EditDialog
			}
			vm.save(
				initial.copy(
					title = title, companyId = company, clientId = clientId,
					planEpochDay = pd.getOrNull(), planStartMin = pt.getOrNull(), durationMin = dur.getOrNull(),
					dueEpochDay = dd.getOrNull(), dueTimeMin = dt.getOrNull(),
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
			ChoiceField(stringResource(R.string.field_company), companies.map { it.id to it.name }, companyId) { picked = it }
		DateField(stringResource(R.string.todo_field_plan_date), planDay, { planDay = it }, clearable = true)
		TimeField(stringResource(R.string.todo_field_plan_time), planTime, { planTime = it }, clearable = true)
		TextInput(stringResource(R.string.todo_field_duration), duration, { duration = it }, KeyboardType.Number)
		DateField(stringResource(R.string.todo_field_due), due, { due = it }, clearable = true)
		TimeField(stringResource(R.string.todo_field_time), time, { time = it }, clearable = true)
		TextInput(stringResource(R.string.todo_field_priority), priority, { priority = it })
		TextInput(stringResource(R.string.field_notes), notes, { notes = it }, singleLine = false)
		TextInput(stringResource(R.string.todo_field_refs), refs, { refs = it }, singleLine = false)
	}
}
