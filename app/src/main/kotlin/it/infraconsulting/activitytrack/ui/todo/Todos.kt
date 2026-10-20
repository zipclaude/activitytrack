/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.todo

import it.infraconsulting.activitytrack.ui.common.companyColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.ui.common.TimeGridSheet
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.todo.withinHours
import it.infraconsulting.activitytrack.domain.todo.todoMoment
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.rgb
import it.infraconsulting.activitytrack.ui.common.Banded
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.ClientEntity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import it.infraconsulting.activitytrack.ui.common.WithAddButton
import it.infraconsulting.activitytrack.ui.common.QuickCompanyDialog
import it.infraconsulting.activitytrack.ui.common.QuickClientDialog
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.TodoRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.domain.settings.GestureSettings
import it.infraconsulting.activitytrack.domain.settings.Gestures
import it.infraconsulting.activitytrack.domain.settings.TodoAction
import it.infraconsulting.activitytrack.domain.settings.TodoSwipe
import it.infraconsulting.activitytrack.domain.todo.dueOverdue
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.LocalCompact
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.AddButton
import it.infraconsulting.activitytrack.ui.common.BLUE
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.DateField
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.GREEN
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.SwipeAction
import it.infraconsulting.activitytrack.ui.common.SwipeCard
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.parseDate
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* To-dos (M4, planned M9, cards and swipes M10): list, add/edit, plan, start, done, ignore. */
class TodosViewModel(
	private val todos: TodoRepository,
	master: MasterDataRepository,
	private val settingsRepo: SettingsRepository,
) : ActionViewModel() {
	val open = todos.open.state(emptyList())
	val ignored = todos.ignored.state(emptyList())
	val all = todos.all.state(emptyList())
	val clients = master.clients.state(emptyList())
	val companies = master.companies.state(emptyList())
	val settings = settingsRepo.settings.state(null)

	/* to-dos of one company only (M12, owner: kept until removed); null = all */
	fun setCompanyFilter(companyId: Long?) = run { settingsRepo.update { it.copy(todoCompanyFilter = companyId) } }

	/* next 24 hours (and overdue) only, or all (backlog 9, owner 2026-10-19) */
	fun setWindow(hours: Int) = run { settingsRepo.update { it.copy(todoWindowHours = hours) } }

	/* ignored to-dos listed or hidden (owner, 2026-10-19) */
	fun setShowIgnored(on: Boolean) = run { settingsRepo.update { it.copy(todoShowIgnored = on) } }

	fun setDone(t: TodoEntity, done: Boolean) = run { todos.setDone(t.id, done) }
	fun setIgnored(t: TodoEntity, ignored: Boolean) = run { todos.setIgnored(t.id, ignored) }
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
/* planned start, else due time: what "the next 24 hours" looks at */
internal fun moment(t: TodoEntity, zone: ZoneId): Instant? =
	todoMoment(t.plan(), t.dueEpochDay?.let(LocalDate::ofEpochDay), t.dueTimeMin, zone)

internal fun overdue(t: TodoEntity, now: Instant = Instant.now()): Boolean {
	if (t.done || t.ignoredUtcMs != null)
		return false
	val zone = ZoneId.systemDefault()
	return t.plan()?.let { now.isAfter(it.end(zone, open = false, now = now)) } == true ||
		dueOverdue(t.dueEpochDay?.let(LocalDate::ofEpochDay), t.dueTimeMin, now, zone)
}

/* "today", else a short local date ("sab 17/10") */
@Composable
private fun dayText(epochDay: Long): String {
	val date = LocalDate.ofEpochDay(epochDay)
	val locale = LocalConfiguration.current.locales[0]
	return if (date == LocalDate.now()) stringResource(R.string.todo_today) else date.format(DateTimeFormatter.ofPattern("EEE d/MM", locale))
}

/* "client · planned day time (min) · due date time · P1" (the actions dialog). */
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

/* The time shown at the card's top right: the planned block, else the due date. */
@Composable
private fun cardTime(t: TodoEntity): String? {
	val today = LocalDate.now()
	t.plan()?.let { p ->
		val zone = ZoneId.systemDefault()
		val end = p.end(zone, open = false, now = Instant.now()).atZone(zone).toLocalTime()
		val span = "${formatMin(p.startMin)}–${formatMin(end.hour * 60 + end.minute)}"
		return if (p.day == today) span else "${dayText(p.day.toEpochDay())} $span"
	}
	val due = t.dueEpochDay?.let(LocalDate::ofEpochDay) ?: return null
	val text = (if (due == today) "" else "${dayText(due.toEpochDay())} ") + (t.dueTimeMin?.let(::formatMin) ?: "")
	return stringResource(R.string.todo_due_at, text.trim())
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
	Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
		Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
	}
}

/*
 * One to-do as a card (M10): title and time, client and labels; an ignored
 * one is struck through and dimmed.
 */
@Composable
fun TodoCard(t: TodoEntity, clientName: String?, band: Int? = null, onClick: () -> Unit) {
	val ignored = t.ignoredUtcMs != null
	val late = overdue(t)
	val compact = LocalCompact.current
	val body: @Composable () -> Unit = {
		Column(
			Modifier.padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 7.dp else 12.dp),
			verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
		) {
			Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					(if (t.done) "✓ " else "") + t.title,
					style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
					textDecoration = if (ignored) TextDecoration.LineThrough else null,
					maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
				)
				cardTime(t)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
			}
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
				clientName?.let {
					Text(
						it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
						textDecoration = if (ignored) TextDecoration.LineThrough else null,
					)
				}
				val cs = MaterialTheme.colorScheme
				when {
					ignored -> Chip(stringResource(R.string.todo_ignored), cs.surfaceVariant, cs.onSurfaceVariant)
					late -> Chip(stringResource(R.string.todo_overdue), cs.errorContainer, cs.onErrorContainer)
					t.plan() != null -> Chip(stringResource(R.string.todo_planned_chip), cs.secondaryContainer, cs.onSecondaryContainer)
				}
				t.priority?.takeIf { !ignored }?.let { Chip("P$it", cs.surfaceVariant, cs.onSurfaceVariant) }
			}
		}
	}
	if (ignored)
		OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().alpha(0.6f)) { Banded(band) { body() } }
	else
		Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Banded(band) { body() } }
}

/* The buttons a swipe reveals on a to-do card ([swipe] for that direction). */
@Composable
internal fun todoSwipeActions(
	t: TodoEntity,
	swipe: TodoSwipe,
	vm: TodosViewModel,
	snooze: Int,
	onStart: (Long) -> Unit,
	onReplan: (TodoEntity) -> Unit,
): List<SwipeAction> {
	val grey = MaterialTheme.colorScheme.outline
	return Gestures.todoActions(swipe, ignored = t.ignoredUtcMs != null, planned = t.plan() != null, done = t.done).map { a ->
		when (a) {
			TodoAction.START -> SwipeAction(stringResource(R.string.swipe_start), R.drawable.ic_cmd_play, GREEN, Color.White) { onStart(t.id) }
			TodoAction.POSTPONE -> SwipeAction(stringResource(R.string.action_postpone, snooze), R.drawable.ic_clock, BLUE, Color.White) { vm.postpone(t, snooze) }
			TodoAction.IGNORE -> SwipeAction(stringResource(R.string.swipe_ignore), R.drawable.ic_ignore, grey, Color.White) { vm.setIgnored(t, true) }
			TodoAction.RESTORE -> SwipeAction(stringResource(R.string.swipe_restore), R.drawable.ic_restore, BLUE, Color.White) { vm.setIgnored(t, false) }
			TodoAction.DONE -> SwipeAction(stringResource(R.string.todo_mark_done), R.drawable.ic_done, GREEN, Color.White) { vm.setDone(t, true) }
			TodoAction.REPLAN -> SwipeAction(stringResource(R.string.action_replan), R.drawable.ic_calendar, BLUE, Color.White) { onReplan(t) }
		}
	}
}

/* A to-do card with its swipe gestures (Home, To-dos, Agenda list). */
@Composable
fun TodoSwipeCard(
	t: TodoEntity,
	clientName: String?,
	vm: TodosViewModel,
	onStart: (Long) -> Unit,
	onReplan: (TodoEntity) -> Unit,
	band: Int? = null,
	onClick: () -> Unit,
) {
	val settings by vm.settings.collectAsState()
	val g = settings?.gestures ?: GestureSettings()
	val snooze = settings?.planSnoozeMin ?: 30
	SwipeCard(
		toStart = todoSwipeActions(t, g.todoToStart, vm, snooze, onStart, onReplan),
		toEnd = todoSwipeActions(t, g.todoToEnd, vm, snooze, onStart, onReplan),
		fullSwipe = g.fullSwipe,
		shape = CardDefaults.shape,
	) { TodoCard(t, clientName, band, onClick) }
}

/* "To do today —— n [company filter]" (M10 separator, M12 filter). */
@Composable
private fun TodoHeader(count: Int, vm: TodosViewModel) {
	Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(stringResource(R.string.todo_due_today), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
		HorizontalDivider(Modifier.weight(1f))
		Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		CompanyFilter(vm)
	}
}

/* To-dos screen window: 24 h, 7 days, 30 days or all (owner, 2026-10-19); overdue always shown. */
@Composable
private fun WindowFilter(hours: Int, onPick: (Int) -> Unit) {
	var open by remember { mutableStateOf(false) }
	val labels = mapOf(
		24 to stringResource(R.string.todo_window_24h), 168 to stringResource(R.string.todo_window_7d),
		720 to stringResource(R.string.todo_window_30d), 0 to stringResource(R.string.todo_window_all),
	)
	Box {
		FilterChip(selected = hours != 0, onClick = { open = true }, label = { Text(labels[hours].orEmpty() + " ▾") })
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			for ((h, label) in labels)
				DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(h) })
		}
	}
}

/*
 * The to-do company filter (M12): "All companies" or one, with its colour;
 * the ✕ removes it. Shared by Home and the To-dos screen.
 */
@Composable
private fun CompanyFilter(vm: TodosViewModel) {
	val settings by vm.settings.collectAsState()
	val companies by vm.companies.collectAsState()
	val current = companies.firstOrNull { it.id == settings?.todoCompanyFilter }
	var open by remember { mutableStateOf(false) }
	Box {
		FilterChip(
			selected = current != null,
			onClick = { open = true },
			label = { Text(current?.name ?: stringResource(R.string.todo_filter_all), maxLines = 1, overflow = TextOverflow.Ellipsis) },
			leadingIcon = current?.color?.let { c -> { Box(Modifier.size(10.dp).clip(CircleShape).background(rgb(c))) } },
			trailingIcon = if (current != null) ({
				Icon(
					painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.todo_filter_all),
					modifier = Modifier.size(18.dp).clickable { vm.setCompanyFilter(null) },
				)
			}) else null,
		)
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			DropdownMenuItem(text = { Text(stringResource(R.string.todo_filter_all)) }, onClick = { open = false; vm.setCompanyFilter(null) })
			for (c in companies.filter { !it.archived })
				DropdownMenuItem(
					text = { Text(c.name) },
					leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(c.color?.let(::rgb) ?: Color.Transparent)) },
					onClick = { open = false; vm.setCompanyFilter(c.id) },
				)
		}
	}
}

/*
 * The band of each to-do (owner, 2026-10-18): the company colour, or the
 * client colour when the list shows a single company.
 */
private fun bands(list: List<TodoEntity>, companies: List<CompanyEntity>, clients: List<ClientEntity>): Map<Long, Int?> {
	val one = list.map { it.companyId }.distinct().size == 1
	val co = companies.associate { it.id to it.color }
	val cl = clients.associate { it.id to it.color }
	return list.associate { t -> t.id to if (one) t.clientId?.let { cl[it] } else companyColor(t.companyId, co[t.companyId]) }
}

/*
 * Home section: open to-dos due or planned today, or overdue, one card
 * each; the ones ignored today at the bottom (owner, 2026-10-18).
 */
@Composable
fun TodoTodaySection(onStart: (Long) -> Unit) {
	val vm = todosViewModel()
	val open by vm.open.collectAsState()
	val ignored by vm.ignored.collectAsState()
	val clients by vm.clients.collectAsState()
	val companies by vm.companies.collectAsState()
	val settings by vm.settings.collectAsState()
	val filter = settings?.todoCompanyFilter
	val today = LocalDate.now()
	val zone = ZoneId.systemDefault()
	val now = Instant.now()
	/* the next 24 hours, overdue included (owner, 2026-10-19: not only the current day) */
	val due = open.filter { t -> (filter == null || t.companyId == filter) && withinHours(moment(t, zone), now) }
		.sortedBy { moment(it, zone) }
	val ignoredToday = if (settings?.todoShowIgnored == false) emptyList() else ignored.filter { t ->
		(filter == null || t.companyId == filter) && t.ignoredUtcMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } == today
	}
	/* with a filter the header stays, so the filter can be removed */
	if (due.isEmpty() && ignoredToday.isEmpty() && filter == null)
		return
	val band = bands(due + ignoredToday, companies, clients)
	var selected by remember { mutableStateOf<TodoEntity?>(null) }
	var editing by remember { mutableStateOf<TodoEntity?>(null) }
	Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 6.dp else 10.dp)) {
		TodoHeader(due.size, vm)
		for (t in due + ignoredToday)
			TodoSwipeCard(
				t, clients.firstOrNull { it.id == t.clientId }?.name, vm, onStart,
				onReplan = { vm.clearError(); editing = it }, band = band[t.id],
			) { selected = t }
	}
	selected?.let { t -> TodoActions(t, vm, onStart = { onStart(t.id) }, onEdit = { vm.clearError(); editing = t }) { selected = null } }
	editing?.let { t -> TodoEditDialog(t, vm) { editing = null } }
}

/* Start / postpone / done / ignore / edit (re-plan) for one to-do. */
@Composable
internal fun TodoActions(t: TodoEntity, vm: TodosViewModel, onStart: () -> Unit, onEdit: (() -> Unit)?, onDismiss: () -> Unit) {
	val clients by vm.clients.collectAsState()
	val settings by vm.settings.collectAsState()
	val ignored = t.ignoredUtcMs != null
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(t.title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(subtitle(t, clients.firstOrNull { it.id == t.clientId }?.name), style = MaterialTheme.typography.bodySmall)
				t.notes?.let { Text(it) }
				if (t.refs.isNotBlank())
					Text(t.refs.lines().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
				if (ignored)
					TextButton(onClick = { vm.setIgnored(t, false); onDismiss() }) { Text(stringResource(R.string.swipe_restore)) }
				else {
					if (!t.done)
						TextButton(onClick = { onDismiss(); onStart() }) { Text(stringResource(R.string.todo_start)) }
					val snooze = settings?.planSnoozeMin
					if (!t.done && t.plan() != null && snooze != null)
						TextButton(onClick = { vm.postpone(t, snooze); onDismiss() }) { Text(stringResource(R.string.action_postpone, snooze)) }
					TextButton(onClick = { vm.setDone(t, !t.done); onDismiss() }) {
						Text(stringResource(if (t.done) R.string.todo_reopen else R.string.todo_mark_done))
					}
					if (!t.done)
						TextButton(onClick = { vm.setIgnored(t, true); onDismiss() }) { Text(stringResource(R.string.swipe_ignore)) }
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

/*
 * [editId]: open that to-do in the editor at once (re-plan from the reminder).
 * Open ones first, ignored ones at the bottom, done ones on request.
 */
@Composable
fun TodosScreen(onBack: () -> Unit, editId: Long? = null, onStart: (Long) -> Unit) {
	val vm = todosViewModel()
	val open by vm.open.collectAsState()
	val ignored by vm.ignored.collectAsState()
	val all by vm.all.collectAsState()
	val clients by vm.clients.collectAsState()
	var showDone by remember { mutableStateOf(false) }
	var selected by remember { mutableStateOf<TodoEntity?>(null) }
	var editing by remember { mutableStateOf<TodoEntity?>(null) }
	var opened by rememberSaveable { mutableStateOf(false) }
	val companies by vm.companies.collectAsState()
	val settings by vm.settings.collectAsState()
	val filter = settings?.todoCompanyFilter
	val window = settings?.todoWindowHours ?: 0
	/* the advanced filters (owner, 2026-10-19), kept while the screen is open */
	var more by rememberSaveable(stateSaver = TodoFilters.Saver) { mutableStateOf(TodoFilters()) }
	var editingFilters by remember { mutableStateOf(false) }
	val showIgnored = settings?.todoShowIgnored != false
	val zone = ZoneId.systemDefault()
	val now = Instant.now()
	val list = (if (showDone) all else open + ignored).filter { t ->
		(filter == null || t.companyId == filter) &&
			(showIgnored || t.ignoredUtcMs == null) &&
			(window == 0 || withinHours(moment(t, zone), now, window.toLong())) &&
			more.accepts(t, now)
	}
	val band = bands(list, companies, clients)

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
		LazyColumn(
			modifier = Modifier.fillMaxSize().padding(padding),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			item {
				Column(Modifier.padding(horizontal = 16.dp)) {
					SwitchRow(stringResource(R.string.todo_show_done), showDone) { showDone = it }
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
						WindowFilter(window, vm::setWindow)
						FilterChip(
							selected = more.count > 0,
							onClick = { editingFilters = true },
							label = { Text(stringResource(R.string.todo_filters) + if (more.count > 0) " (${more.count})" else "") },
						)
						FilterChip(selected = showIgnored, onClick = { vm.setShowIgnored(!showIgnored) }, label = { Text(stringResource(R.string.todo_filter_ignored)) })
						CompanyFilter(vm)
					}
				}
			}
			if (list.isEmpty())
				item { EmptyText() }
			items(list, key = { it.id }) { t ->
				Column(Modifier.padding(horizontal = 16.dp)) {
					TodoSwipeCard(
						t, clients.firstOrNull { it.id == t.clientId }?.name, vm, onStart,
						onReplan = { vm.clearError(); editing = it }, band = band[t.id],
					) { selected = t }
				}
			}
		}
	}
	selected?.let { t -> TodoActions(t, vm, onStart = { onStart(t.id) }, onEdit = { vm.clearError(); editing = t }) { selected = null } }
	editing?.let { t -> TodoEditDialog(t, vm) { editing = null } }
	if (editingFilters)
		TodoFiltersDialog(more, clients.filter { filter == null || it.companyId == filter }.map { it.id to it.name }, { editingFilters = false }) { more = it }
}

enum class TodoState { OPEN, PLANNED, OVERDUE }

/* Advanced to-do filters (owner, 2026-10-19): client, priority, state, planned or not. */
data class TodoFilters(
	val clientId: Long? = null,
	val priority: Int? = null,
	val state: TodoState? = null,
	val planned: Boolean? = null,
) {
	val count get() = listOfNotNull(clientId, priority, state, planned).size

	fun accepts(t: TodoEntity, now: Instant): Boolean =
		(clientId == null || t.clientId == clientId) &&
			(priority == null || t.priority == priority) &&
			(planned == null || (t.plan() != null) == planned) &&
			when (state) {
				null -> true
				TodoState.OPEN -> t.plan() == null && !overdue(t, now)
				TodoState.PLANNED -> t.plan() != null && !overdue(t, now)
				TodoState.OVERDUE -> overdue(t, now)
			}

	companion object {
		val Saver = androidx.compose.runtime.saveable.Saver<TodoFilters, List<Any?>>(
			save = { listOf(it.clientId, it.priority, it.state?.name, it.planned) },
			restore = { TodoFilters(it[0] as Long?, it[1] as Int?, (it[2] as String?)?.let(TodoState::valueOf), it[3] as Boolean?) },
		)
	}
}

@Composable
private fun TodoFiltersDialog(current: TodoFilters, clients: List<Pair<Long, String>>, onClose: () -> Unit, onApply: (TodoFilters) -> Unit) {
	var f by remember { mutableStateOf(current) }
	val any = stringResource(R.string.todo_filter_any)
	AlertDialog(
		onDismissRequest = onClose,
		title = { Text(stringResource(R.string.todo_filters)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				ChoiceField(stringResource(R.string.field_client), listOf<Pair<Long?, String>>(null to any) + clients, f.clientId) { f = f.copy(clientId = it) }
				ChoiceField(
					stringResource(R.string.todo_field_priority),
					listOf<Pair<Int?, String>>(null to any) + (1..5).map { it to it.toString() },
					f.priority,
				) { f = f.copy(priority = it) }
				ChoiceField(
					stringResource(R.string.todo_filter_state),
					listOf(
						null to any, TodoState.OPEN to stringResource(R.string.todo_state_open),
						TodoState.PLANNED to stringResource(R.string.todo_state_planned), TodoState.OVERDUE to stringResource(R.string.todo_state_overdue),
					),
					f.state,
				) { f = f.copy(state = it) }
				ChoiceField(
					stringResource(R.string.todo_filter_planned),
					listOf(null to any, true to stringResource(R.string.action_yes), false to stringResource(R.string.action_no)),
					f.planned,
				) { f = f.copy(planned = it) }
			}
		},
		confirmButton = { TextButton(onClick = { onApply(f); onClose() }) { Text(stringResource(R.string.action_ok)) } },
		dismissButton = { TextButton(onClick = { onApply(TodoFilters()); onClose() }) { Text(stringResource(R.string.todo_filters_reset)) } },
	)
}

/* "" -> null; anything else must parse, or the field is reported. */
private inline fun <T> optional(text: String, parse: (String) -> T?): Result<T?> =
	if (text.isBlank()) Result.success(null) else parse(text)?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(text))

/*
 * Title, client (its company follows), planned date, start and end (the
 * duration follows; issue #37, picked as a range on the grid),
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
	var addCompany by remember { mutableStateOf(false) }
	var addClient by remember { mutableStateOf(false) }
	val companyId = picked ?: companies.firstOrNull()?.id
	var planDay by remember { mutableStateOf(initial.planEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
	var planTime by remember { mutableStateOf(initial.planStartMin?.let(::formatMin).orEmpty()) }
	var planEnd by remember {
		mutableStateOf(initial.planStartMin?.let { s0 -> initial.durationMin?.let { formatMin((s0 + it).coerceAtMost(1440)) } }.orEmpty())
	}
	var planGrid by remember { mutableStateOf(false) }
	var due by remember { mutableStateOf(initial.dueEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
	var time by remember { mutableStateOf(initial.dueTimeMin?.let(::formatMin).orEmpty()) }
	var priority by remember { mutableStateOf(initial.priority?.toString().orEmpty()) }
	var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
	var refs by remember { mutableStateOf(initial.refs) }
	/* a plan needs both day and time: the missing one is flagged on its field after Save (M13) */
	var tried by remember { mutableStateOf(false) }
	val planTimeMissing = tried && planDay.isNotBlank() && planTime.isBlank()
	val planDayMissing = tried && planTime.isNotBlank() && planDay.isBlank()
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
			if (planDay.isBlank() != planTime.isBlank()) {
				tried = true
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
			/* the end on the same day, after the start; empty = no duration (1 h growing) */
			val dur = optional(planEnd) { e -> parseHhMm(e)?.let { m -> pt.getOrNull()?.let { m - it } }?.takeIf { d -> d > 0 } }
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
		TextInput(stringResource(R.string.todo_field_title), title, { title = it }, importance = Importance.REQUIRED)
		WithAddButton(stringResource(R.string.quick_client), { addClient = true }) {
			SearchableChoiceField(
				stringResource(R.string.field_client),
				listOf<Pair<Long?, String>>(null to stringResource(R.string.field_none)) +
					clients.filter { !it.archived }.map { it.id to "${coNames[it.companyId].orEmpty()} · ${it.name}" },
				clientId,
				importance = Importance.IMPORTANT,
				colorOf = { id -> clients.firstOrNull { it.id == id }?.color },
			) { clientId = it }
		}
		if (clientId == null)
			WithAddButton(stringResource(R.string.quick_company), { addCompany = true }) {
				ChoiceField(
					stringResource(R.string.field_company), companies.map { it.id to it.name }, companyId,
					importance = Importance.REQUIRED, colorOf = { id -> companies.firstOrNull { it.id == id }?.color },
				) { picked = it }
			}
		DateField(
			stringResource(R.string.todo_field_plan_date), planDay, { planDay = it }, clearable = true,
			error = stringResource(R.string.todo_plan_date_missing).takeIf { planDayMissing }, importance = Importance.IMPORTANT,
		)
		androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Column(Modifier.weight(1f)) {
				TimeField(
					stringResource(R.string.field_start), planTime, { planTime = it }, clearable = true,
					error = stringResource(R.string.todo_plan_time_missing).takeIf { planTimeMissing }, importance = Importance.IMPORTANT,
					onOpen = { planGrid = true },
				)
			}
			Column(Modifier.weight(1f)) {
				TimeField(stringResource(R.string.field_end), planEnd, { planEnd = it }, clearable = true, endOfDay = true,
					importance = Importance.OPTIONAL, onOpen = { planGrid = true })
			}
		}
		DateField(stringResource(R.string.todo_field_due), due, { due = it }, clearable = true, importance = Importance.OPTIONAL)
		TimeField(stringResource(R.string.todo_field_time), time, { time = it }, clearable = true, importance = Importance.OPTIONAL)
		TextInput(stringResource(R.string.todo_field_priority), priority, { priority = it }, importance = Importance.OPTIONAL)
		TextInput(stringResource(R.string.field_notes), notes, { notes = it }, singleLine = false, importance = Importance.OPTIONAL)
		TextInput(stringResource(R.string.todo_field_refs), refs, { refs = it }, singleLine = false, importance = Importance.OPTIONAL)
	}
	if (planGrid) {
		val a = parseHhMm(planTime)
		val b = parseHhMm(planEnd)
		TimeGridSheet(
			title = stringResource(R.string.todo_field_plan_date),
			initial = if (a != null && b != null && b > a && a < 1440) a until b else null,
			range = true,
			busy = emptyList(),
			onDismiss = { planGrid = false },
		) { from, to ->
			planGrid = false
			planTime = formatMin(from)
			planEnd = formatMin(to)
		}
	}
	if (addCompany)
		QuickCompanyDialog(onDone = { addCompany = false; picked = it; clientId = null }, onDismiss = { addCompany = false })
	if (addClient)
		QuickClientDialog(companyId, onDone = { c, co -> addClient = false; clientId = c; picked = co }, onDismiss = { addClient = false })
}
