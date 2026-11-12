/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.todo

import it.infraconsulting.activitytrack.ui.common.companiesShown
import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.domain.policy.Field as PField
import it.infraconsulting.activitytrack.ui.common.enabled
import it.infraconsulting.activitytrack.ui.common.fieldShown
import it.infraconsulting.activitytrack.ui.common.fieldImportance
import it.infraconsulting.activitytrack.ui.common.fieldLabel
import it.infraconsulting.activitytrack.ui.common.PolicyField
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.ui.common.DateTimeField
import it.infraconsulting.activitytrack.ui.common.RangeField
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
import it.infraconsulting.activitytrack.data.repo.isAbsence
import it.infraconsulting.activitytrack.ui.common.ColorPalette
import it.infraconsulting.activitytrack.ui.common.NO_COLOR
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.domain.settings.GestureSettings
import it.infraconsulting.activitytrack.domain.settings.Gestures
import it.infraconsulting.activitytrack.domain.settings.TodoAction
import it.infraconsulting.activitytrack.domain.settings.TodoSwipe
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import it.infraconsulting.activitytrack.domain.todo.alignedDue
import it.infraconsulting.activitytrack.domain.todo.dueOverdue
import it.infraconsulting.activitytrack.domain.todo.startsAfterDue
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
import it.infraconsulting.activitytrack.ui.common.ORANGE
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.SwipeAction
import it.infraconsulting.activitytrack.ui.common.SwipeCard
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.SettingLock
import it.infraconsulting.activitytrack.ui.common.prefix
import it.infraconsulting.activitytrack.ui.common.settingLock
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.Undo
import it.infraconsulting.activitytrack.ui.common.lastPick
import it.infraconsulting.activitytrack.ui.common.lastPickViewModel
import it.infraconsulting.activitytrack.ui.common.UndoEvent
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
	/* the policy in force: the fields it makes required (issue #48) */
	private val policy: () -> it.infraconsulting.activitytrack.domain.policy.Policy = { it.infraconsulting.activitytrack.domain.policy.Policy.NONE },
) : ActionViewModel() {
	val open = todos.open.state(emptyList())
	val ignored = todos.ignored.state(emptyList())
	val all = todos.all.state(emptyList())
	/* the done ones, most recently marked first (Home "Chiuse", issue #59) */
	val done = todos.done(100).state(emptyList())
	val clients = master.clients.state(emptyList())
	val companies = master.companies.state(emptyList())
	val settings = settingsRepo.settings.state(null)

	/* to-dos of one company only (M12, owner: kept until removed); null = all */
	fun setCompanyFilter(companyId: Long?) = run { settingsRepo.update { it.copy(todoCompanyFilter = companyId) } }

	/* next 24 hours (and overdue) only, or all (backlog 9, owner 2026-10-19) */
	fun setWindow(hours: Int) = run { settingsRepo.update { it.copy(todoWindowHours = hours) } }
	fun setHomeWindow(hours: Int) = run { settingsRepo.update { it.copy(homeTodoWindowHours = hours) } }
	fun setHomeFilterEntries(on: Boolean) = run { settingsRepo.update { it.copy(homeFilterEntries = on) } }

	/* ignored to-dos listed or hidden (owner, 2026-10-19) */
	fun setShowIgnored(on: Boolean) = run { settingsRepo.update { it.copy(todoShowIgnored = on) } }

	/* the change, then "Annulla" puts [t] back as it was (issue #97) */
	private fun undoable(t: TodoEntity, @androidx.annotation.StringRes message: Int, action: suspend () -> Unit) =
		run({ Undo.post(UndoEvent(message, t.title) { todos.restore(t) }) }, action)

	fun setDone(t: TodoEntity, done: Boolean) =
		undoable(t, if (done) R.string.undo_todo_done else R.string.undo_todo_open) { todos.setDone(t.id, done) }
	fun delete(t: TodoEntity) = run { todos.delete(t.id) }
	fun setIgnored(t: TodoEntity, ignored: Boolean) =
		undoable(t, if (ignored) R.string.undo_todo_ignored else R.string.undo_todo_open) { todos.setIgnored(t.id, ignored) }
	/* the state chosen by hand (issue #89) */
	fun setState(t: TodoEntity, state: TodoState) = undoable(
		t,
		when (state) {
			TodoState.DONE -> R.string.undo_todo_done
			TodoState.IGNORED -> R.string.undo_todo_ignored
			else -> R.string.undo_todo_open
		},
	) { todos.setState(t.id, done = state == TodoState.DONE, ignored = state == TodoState.IGNORED) }
	fun clearPlan(t: TodoEntity) = undoable(t, R.string.undo_todo_unplanned) { todos.clearPlan(t.id) }
	/* TODO(activity-1.0-ui): the last day of an absence typed in the 0.9 dialog (no column for it since 1.0) */
	var absenceLastDay: Long? = null

	fun save(t: TodoEntity, done: () -> Unit) {
		/* an absence: an ABSENCE activity, one segment per working day (ACTIVITY-MODEL.md 3.1) */
		if (t.isAbsence()) {
			run(done) { todos.saveAbsence(t.planEpochDay ?: throw ValidationException(ValidationException.Reason.INVALID_DATE), absenceLastDay, t.planStartMin, t.durationMin, t.notes) }
			return
		}
		/* an absence has none of the fields a policy may require (issue #158) */
		if (!t.isAbsence()) policy().missingRequired(
			mapOf(
				PField.CLIENT to (t.clientId != null), PField.NOTES to !t.notes.isNullOrBlank(), PField.REFS to t.refs.isNotBlank(),
				PField.TODO_PRIORITY to (t.priority != null), PField.TODO_DUE to (t.dueEpochDay != null), PField.TODO_PLAN to (t.planEpochDay != null),
			),
		)?.let { _ -> return fail(it.infraconsulting.activitytrack.data.repo.ValidationException.Reason.POLICY_REQUIRED) }
		run(done) { todos.save(t) }
	}
	fun postpone(t: TodoEntity, minutes: Int) = undoable(t, R.string.undo_todo_postponed) { todos.postpone(t.id, minutes) }

	/* a to-do belongs to a company: through its client or chosen directly */
	fun missingCompany() = fail(ValidationException.Reason.CLIENT_REQUIRED)
	fun invalid(reason: ValidationException.Reason) = fail(reason)
}

@Composable
internal fun todosViewModel() = appViewModel(key = "todos") { c -> TodosViewModel(c.todos, c.masterData, c.settings, { c.policies.current.value }) }

/* the default colour of an absence (issue #158): the palette's red */
internal const val ABSENCE_COLOR = 0xD64545

/* A new to-do, planned at [day] [startMin] when given (agenda). */
internal fun newTodo(day: LocalDate? = null, startMin: Int? = null) = todoDraft(
	companyId = null, clientId = null, title = "", notes = null, dueEpochDay = null, dueTimeMin = null, priority = null,
	planEpochDay = day?.toEpochDay(), planStartMin = startMin, durationMin = null,
)

/*
 * Still open past its planned duration or first hour (M9), or past its due
 * date and time (owner, 2026-10-18): "overdue" on Home and in the lists.
 */
/* planned start, else due time: what "the next 24 hours" looks at */
internal fun moment(t: TodoEntity, zone: ZoneId): Instant? =
	todoMoment(t.plan(), t.dueEpochDay?.let(LocalDate::ofEpochDay), t.dueTimeMin, zone)

internal fun overdue(t: TodoEntity, now: Instant = Instant.now()): Boolean {
	if (t.done || t.ignoredUtcMs != null || t.isAbsence())
		return false
	val zone = ZoneId.systemDefault()
	return t.plan()?.over(now, zone) == true ||
		dueOverdue(t.dueEpochDay?.let(LocalDate::ofEpochDay), t.dueTimeMin, now, zone)
}

/*
 * The one state a to-do shows (issue #89): done and ignored are set by hand,
 * overdue and planned follow its dates; a done one is no longer "planned".
 */
internal fun todoState(t: TodoEntity, now: Instant = Instant.now()): TodoState = when {
	t.done -> TodoState.DONE
	t.ignoredUtcMs != null -> TodoState.IGNORED
	overdue(t, now) -> TodoState.OVERDUE
	t.plan() != null -> TodoState.PLANNED
	else -> TodoState.OPEN
}

/* When a done to-do was done: "today 20:05", "mer 21/10 20:05"; null for the open ones. */
@Composable
private fun doneText(t: TodoEntity): String? {
	if (!t.done)
		return null
	val at = Instant.ofEpochMilli(t.doneUtcMs ?: t.updatedUtcMs).atZone(ZoneId.systemDefault())
	return dayText(at.toLocalDate().toEpochDay()) + " " + formatMin(at.hour * 60 + at.minute)
}

/* "today", else a short local date ("sab 17/10") */
@Composable
internal fun dayText(epochDay: Long): String {
	val date = LocalDate.ofEpochDay(epochDay)
	val locale = LocalConfiguration.current.locales[0]
	return if (date == LocalDate.now()) stringResource(R.string.todo_today) else date.format(DateTimeFormatter.ofPattern("EEE d/MM", locale))
}

/* "client · planned day time (min) · due date time · P1" (the actions dialog). */
@Composable
private fun subtitle(t: TodoEntity, clientName: String?): String {
	doneText(t)?.let { return listOfNotNull(clientName, stringResource(R.string.todo_done_at, it)).joinToString(" · ") }
	val planned = t.plan()?.let { p ->
		val text = dayText(p.day.toEpochDay()) + (p.startMin?.let { " ${formatMin(it)}" } ?: "") + (p.durationMin?.let { " ($it min)" } ?: "")
		stringResource(R.string.todo_planned, text)
	}
	val due = t.dueEpochDay?.let { d -> dayText(d) + (t.dueTimeMin?.let { " ${formatMin(it)}" } ?: "") }
	val late = stringResource(R.string.todo_overdue).takeIf { overdue(t) }
	return listOfNotNull(late, clientName, planned, due, t.priority.takeIf { it > 0 }?.let { "P$it" }).joinToString(" · ")
}

/* The time shown at the card's top right: when it was done, else the planned block, else the due date. */
@Composable
internal fun cardTime(t: TodoEntity): String? {
	doneText(t)?.let { return it }
	val today = LocalDate.now()
	t.plan()?.let { p ->
		val zone = ZoneId.systemDefault()
		/* the day only; a point; a block (issue #52) */
		val time = p.startMin?.let { start ->
			formatMin(start) + (p.durationMin?.let { _ -> p.end(zone).atZone(zone).toLocalTime().let { e -> "–" + formatMin(e.hour * 60 + e.minute) } } ?: "")
		}
		return when {
			time == null -> dayText(p.day.toEpochDay())
			p.day == today -> time
			else -> "${dayText(p.day.toEpochDay())} $time"
		}
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
	val state = todoState(t)
	/* its own colour wins over the company's; an absence is red unless chosen (issue #158) */
	val band = t.color ?: if (t.isAbsence()) ABSENCE_COLOR else band
	val title = t.title.ifBlank { if (t.isAbsence()) stringResource(R.string.todo_absence) else "" }
	val ignored = state == TodoState.IGNORED
	val compact = LocalCompact.current
	val body: @Composable () -> Unit = {
		Column(
			Modifier.padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 7.dp else 12.dp),
			verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
		) {
			Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					(if (t.done) "✓ " else "") + title,
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
				when (state) {
					TodoState.DONE -> Chip(stringResource(R.string.todo_done_chip), cs.surfaceVariant, cs.onSurfaceVariant)
					TodoState.IGNORED -> Chip(stringResource(R.string.todo_ignored), cs.surfaceVariant, cs.onSurfaceVariant)
					TodoState.OVERDUE -> Chip(stringResource(R.string.todo_overdue), cs.errorContainer, cs.onErrorContainer)
					TodoState.PLANNED -> Chip(stringResource(R.string.todo_planned_chip), cs.secondaryContainer, cs.onSecondaryContainer)
					TodoState.OPEN -> {}
				}
				t.priority.takeIf { it > 0 }?.takeIf { state != TodoState.IGNORED && state != TodoState.DONE }?.let { Chip("P$it", cs.surfaceVariant, cs.onSurfaceVariant) }
			}
		}
	}
	if (ignored)
		OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().alpha(0.6f)) { Banded(band) { body() } }
	else
		Card(onClick = onClick, modifier = Modifier.fillMaxWidth().alpha(if (state == TodoState.DONE) 0.75f else 1f)) { Banded(band) { body() } }
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
	val canIgnore = LocalPolicy.current.enabled(Feature.IGNORE_TODOS)
	return Gestures.todoActions(swipe, ignored = t.ignoredUtcMs != null, planned = t.plan() != null, done = t.done)
		.filter { canIgnore || (it != TodoAction.IGNORE && it != TodoAction.RESTORE) }.map { a ->
		when (a) {
			TodoAction.START -> SwipeAction(stringResource(R.string.swipe_start), R.drawable.ic_cmd_play, GREEN, Color.White) { onStart(t.id) }
			TodoAction.POSTPONE -> SwipeAction(stringResource(R.string.action_postpone, snooze), R.drawable.ic_clock, BLUE, Color.White) { vm.postpone(t, snooze) }
			TodoAction.IGNORE -> SwipeAction(stringResource(R.string.swipe_ignore), R.drawable.ic_ignore, grey, Color.White) { vm.setIgnored(t, true) }
			TodoAction.RESTORE -> SwipeAction(stringResource(R.string.swipe_restore), R.drawable.ic_restore, BLUE, Color.White) { vm.setIgnored(t, false) }
			TodoAction.DONE -> SwipeAction(stringResource(R.string.todo_mark_done), R.drawable.ic_done, GREEN, Color.White) { vm.setDone(t, true) }
			TodoAction.REPLAN -> SwipeAction(stringResource(R.string.action_replan), R.drawable.ic_calendar, BLUE, Color.White) { onReplan(t) }
			TodoAction.REOPEN -> SwipeAction(stringResource(R.string.todo_reopen_short), R.drawable.ic_restore, ORANGE, Color.White) { vm.setDone(t, false) }
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

/* To-dos screen window: 24 h, 7 days, 30 days or all (owner, 2026-10-19); overdue always shown. */
@Composable
private fun WindowFilter(hours: Int, onPick: (Int) -> Unit) {
	val lock = settingLock("todoWindowHours")
	if (lock == SettingLock.HIDDEN)
		return
	var open by remember { mutableStateOf(false) }
	val labels = mapOf(
		/* the same choices as the Home tab (issue #96) */
		24 to stringResource(R.string.todo_window_24h), 72 to stringResource(R.string.todo_window_3d), 168 to stringResource(R.string.todo_window_7d),
		720 to stringResource(R.string.todo_window_30d), 0 to stringResource(R.string.todo_window_all),
	)
	Box {
		FilterChip(selected = hours != 0, onClick = { open = true }, enabled = lock == SettingLock.FREE, label = { Text(lock.prefix() + labels[hours].orEmpty() + " ▾") })
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
internal fun CompanyFilter(vm: TodosViewModel, onHome: Boolean = false, label: String? = null) {
	val settings by vm.settings.collectAsState()
	val companies by vm.companies.collectAsState()
	val current = companies.firstOrNull { it.id == settings?.todoCompanyFilter }
	var open by remember { mutableStateOf(false) }
	Box {
		FilterChip(
			selected = current != null,
			onClick = { open = true },
			/* [label]: a compact chip naming the filter, coloured while a company is chosen (Home, owner 2026-10-28) */
			label = { Text(label?.let { "$it ▾" } ?: current?.name ?: stringResource(R.string.todo_filter_all), maxLines = 1, overflow = TextOverflow.Ellipsis) },
			leadingIcon = current?.color?.takeIf { label == null }?.let { c -> { Box(Modifier.size(10.dp).clip(CircleShape).background(rgb(c))) } },
			trailingIcon = if (current != null && label == null) ({
				Icon(
					painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.todo_filter_all),
					modifier = Modifier.size(18.dp).clickable { vm.setCompanyFilter(null) },
				)
			}) else null,
		)
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			DropdownMenuItem(text = { Text(stringResource(R.string.todo_filter_all)) }, onClick = { open = false; vm.setCompanyFilter(null) })
			/* on Home: the filter may hide the entries above too (owner, issue #44; off by default) */
			val entriesLock = settingLock("homeFilterEntries")
			if (onHome && entriesLock != SettingLock.HIDDEN)
				DropdownMenuItem(enabled = entriesLock == SettingLock.FREE,
					text = { Text(stringResource(R.string.home_filter_entries)) },
					leadingIcon = { androidx.compose.material3.Checkbox(checked = settings?.homeFilterEntries == true, onCheckedChange = null) },
					onClick = { open = false; vm.setHomeFilterEntries(settings?.homeFilterEntries != true) },
				)
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
internal fun bands(list: List<TodoEntity>, companies: List<CompanyEntity>, clients: List<ClientEntity>): Map<Long, Int?> {
	val one = list.map { it.companyId }.distinct().size == 1
	val co = companies.associate { it.id to it.color }
	val cl = clients.associate { it.id to it.color }
	return list.associate { t -> t.id to if (one) t.clientId?.let { cl[it] } else companyColor(t.companyId, co[t.companyId]) }
}

/* Start / postpone / done / ignore / edit (re-plan) for one to-do. */
@Composable
internal fun TodoActions(t: TodoEntity, vm: TodosViewModel, onStart: () -> Unit, onEdit: (() -> Unit)?, onDismiss: () -> Unit) {
	val clients by vm.clients.collectAsState()
	val settings by vm.settings.collectAsState()
	val ignored = t.ignoredUtcMs != null
	var deleting by remember { mutableStateOf(false) }
	if (deleting) {
		AlertDialog(
			onDismissRequest = { deleting = false },
			text = { Text(stringResource(R.string.confirm_delete, t.title)) },
			confirmButton = {
				TextButton(onClick = { vm.delete(t); onDismiss() }) {
					Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
		)
		return
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(t.title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(subtitle(t, clients.firstOrNull { it.id == t.clientId }?.name), style = MaterialTheme.typography.bodySmall)
				t.notes?.let { Text(it) }
				if (t.refs.isNotBlank())
					Text(t.refs.lines().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
				/* the state set by hand, whatever it was (issue #89); planned and overdue follow the dates */
				val state = todoState(t)
				val chosen = when (state) {
					TodoState.DONE, TodoState.IGNORED -> state
					else -> TodoState.OPEN
				}
				val states = listOfNotNull(
					TodoState.OPEN to R.string.todo_state_open,
					TodoState.DONE to R.string.todo_state_done,
					(TodoState.IGNORED to R.string.todo_state_ignored)
						.takeIf { ignored || LocalPolicy.current.enabled(Feature.IGNORE_TODOS) },
				)
				Text(stringResource(R.string.todo_state), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
				FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					for ((s, label) in states)
						FilterChip(
							selected = s == chosen,
							onClick = { if (s != chosen) vm.setState(t, s); onDismiss() },
							label = { Text(stringResource(label)) },
						)
				}
				if (chosen == TodoState.OPEN) {
					TextButton(onClick = { onDismiss(); onStart() }) { Text(stringResource(R.string.todo_start)) }
					val snooze = settings?.planSnoozeMin
					if (t.plan() != null && snooze != null)
						TextButton(onClick = { vm.postpone(t, snooze); onDismiss() }) { Text(stringResource(R.string.action_postpone, snooze)) }
				}
				if (t.plan() != null && chosen != TodoState.DONE)
					TextButton(onClick = { vm.clearPlan(t); onDismiss() }) { Text(stringResource(R.string.todo_clear_plan)) }
				onEdit?.let {
					TextButton(onClick = { onDismiss(); it() }) {
						Text(stringResource(R.string.todo_edit))
					}
				}
				/* only the ones created by hand (issue #40): imported ones would come back */
				if (t.externalKey == null && LocalPolicy.current.enabled(Feature.DELETE_TODOS))
					TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
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
fun TodosScreen(onBack: (() -> Unit)?, editId: Long? = null, onStart: (Long) -> Unit) {
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
						settingLock("todoShowIgnored").takeIf { it != SettingLock.HIDDEN }?.let { lock ->
							FilterChip(selected = showIgnored, onClick = { vm.setShowIgnored(!showIgnored) }, enabled = lock == SettingLock.FREE, label = { Text(lock.prefix() + stringResource(R.string.todo_filter_ignored)) })
						}
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

/* the advanced filters offer the first three; done and ignored are set by hand (issue #89) */
enum class TodoState { OPEN, PLANNED, OVERDUE, DONE, IGNORED }

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
			(state == null || todoState(t, now) == state)

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

/*
 * TODO(activity-1.0-ui): the to-do dialog moved to ui/start (TodoDialog, the
 * "Da fare" creation dialog); the lists switch to it and drop this name.
 */
@Composable
internal fun TodoEditDialog(initial: TodoEntity, @Suppress("UNUSED_PARAMETER") vm: TodosViewModel, onClose: () -> Unit) =
	it.infraconsulting.activitytrack.ui.start.TodoDialog(initial, onClose)
