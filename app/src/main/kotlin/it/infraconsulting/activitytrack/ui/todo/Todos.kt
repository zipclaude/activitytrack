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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.domain.activity.ActivityDisplay
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.activity.Priority
import it.infraconsulting.activitytrack.ui.common.displayName
import it.infraconsulting.activitytrack.ui.common.displayStyle
import it.infraconsulting.activitytrack.ui.common.priorityName
import it.infraconsulting.activitytrack.ui.common.snapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* To-dos (M4, planned M9, cards and swipes M10): list, add/edit, plan, start, done, ignore. */
class TodosViewModel(
	private val entries: EntryRepository,
	master: MasterDataRepository,
	private val settingsRepo: SettingsRepository,
) : ActionViewModel() {
	val open = entries.todos.state(emptyList())
	/* the bin: every deleted activity, newest first (ACTIVITY-MODEL.md 1.1) */
	val deleted = entries.deleted.state(emptyList())

	/* the planned segments of each TODO activity (they make it PLANNED or LATE, 2.2) */
	@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
	val planned: StateFlow<Map<Long, List<SegmentEntity>>> = entries.todos.flatMapLatest { list ->
		if (list.isEmpty()) flowOf(emptyMap())
		else combine(list.map { t -> entries.segments(t.id).map { segs -> t.id to segs.filter { it.planned } } }) { it.toMap() }
	}.state(emptyMap())
	val clients = master.clients.state(emptyList())
	val companies = master.companies.state(emptyList())
	val settings = settingsRepo.settings.state(null)

	/* to-dos of one company only (M12, owner: kept until removed); null = all */
	fun setCompanyFilter(companyId: Long?) = run { settingsRepo.update { it.copy(todoCompanyFilter = companyId) } }

	/* next 24 hours (and overdue) only, or all (backlog 9, owner 2026-10-19) */
	fun setWindow(hours: Int) = run { settingsRepo.update { it.copy(todoWindowHours = hours) } }
	fun setHomeWindow(hours: Int) = run { settingsRepo.update { it.copy(homeTodoWindowHours = hours) } }
	fun setHomeFilterEntries(on: Boolean) = run { settingsRepo.update { it.copy(homeFilterEntries = on) } }


	/* the change, then "Annulla" puts [t] back as it was (issue #97) */
	private fun undoable(t: TimeEntryEntity, @androidx.annotation.StringRes message: Int, action: suspend () -> Unit) =
		run({ Undo.post(UndoEvent(message, t.title()) { entries.putBack(t) }) }, action)

	/* tick: TODO -> CLOSED, the planned segments go (3); "Annulla" puts it back to do (untick: putBack skips a changed status) */
	fun tick(t: TimeEntryEntity) = run({ Undo.post(UndoEvent(R.string.undo_todo_done, t.title()) { entries.untick(t.id) }) }) { entries.tick(t.id) }
	/* to the bin, restorable (ignoreTodos maps to delete/restore, 7) */
	fun delete(t: TimeEntryEntity) = undoable(t, R.string.undo_todo_deleted) { entries.delete(t.id) }
	fun restore(t: TimeEntryEntity) = run { entries.restore(t.id) }
	/* out of the bin for good; refused with attachments (HAS_ATTACHMENTS) */
	fun purge(t: TimeEntryEntity, done: () -> Unit = {}) = run(done) { entries.purge(t.id) }
	fun clearPlan(t: TimeEntryEntity) = undoable(t, R.string.undo_todo_unplanned) { entries.clearPlan(t.id) }
	fun postpone(t: TimeEntryEntity, minutes: Int) = undoable(t, R.string.undo_todo_postponed) { entries.postpone(t.id, minutes) }

	/* a to-do belongs to a company: through its client or chosen directly */
	fun missingCompany() = fail(ValidationException.Reason.CLIENT_REQUIRED)
	fun invalid(reason: ValidationException.Reason) = fail(reason)
}

@Composable
internal fun todosViewModel() = appViewModel(key = "todos") { c -> TodosViewModel(c.entries, c.masterData, c.settings) }

/* the default colour of an absence (issue #158): the palette's red */
internal const val ABSENCE_COLOR = 0xD64545

/* A new to-do (no id), planned at [day] [startMin] when given (agenda). */
internal fun newTodo(day: LocalDate? = null, startMin: Int? = null) = TimeEntryEntity(
	uid = null, companyId = null, clientId = null, status = ActivityStatus.TODO, zoneId = ZoneId.systemDefault().id,
	planEpochDay = day?.toEpochDay(), planStartMin = startMin, createdUtcMs = 0, updatedUtcMs = 0,
)

/* the description of a to-do, "" when it has none */
internal fun TimeEntryEntity.title(): String = activity.orEmpty()

/*
 * Still open past its planned duration or first hour (M9), or past its due
 * date and time (owner, 2026-10-18): "overdue" on Home and in the lists.
 */
/* planned start, else due time: what "the next 24 hours" looks at */
internal fun moment(t: TimeEntryEntity, zone: ZoneId): Instant? =
	todoMoment(t.plan(), t.dueEpochDay?.let(LocalDate::ofEpochDay), t.dueTimeMin, zone)

/* The state shown for an activity (ACTIVITY-MODEL.md 2.2); [planned] its planned segments when known. */
internal fun displayOf(t: TimeEntryEntity, now: Instant = Instant.now(), planned: List<SegmentEntity> = emptyList()): DisplayState =
	ActivityDisplay.display(t.snapshot(planned), now, ZoneId.systemDefault()).state

internal fun overdue(t: TimeEntryEntity, now: Instant = Instant.now(), planned: List<SegmentEntity> = emptyList()): Boolean =
	displayOf(t, now, planned) == DisplayState.LATE

/* When a done to-do was done: "today 20:05", "mer 21/10 20:05"; null for the open ones. */
@Composable
private fun doneText(t: TimeEntryEntity): String? {
	if (t.status != ActivityStatus.CLOSED)
		return null
	val at = Instant.ofEpochMilli(t.closedUtcMs ?: t.updatedUtcMs).atZone(ZoneId.systemDefault())
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
private fun subtitle(t: TimeEntryEntity, clientName: String?): String {
	doneText(t)?.let { return listOfNotNull(clientName, stringResource(R.string.todo_done_at, it)).joinToString(" · ") }
	val planned = t.plan()?.let { p ->
		val text = dayText(p.day.toEpochDay()) + (p.startMin?.let { " ${formatMin(it)}" } ?: "") + (p.durationMin?.let { " ($it min)" } ?: "")
		stringResource(R.string.todo_planned, text)
	}
	val due = t.dueEpochDay?.let { d -> dayText(d) + (t.dueTimeMin?.let { " ${formatMin(it)}" } ?: "") }
	val late = stringResource(R.string.todo_overdue).takeIf { overdue(t) }
	val priority = Priority.of(t.priority).takeIf { it != Priority.NONE }?.let { stringResource(priorityName(it)) }
	return listOfNotNull(late, clientName, planned, due, priority).joinToString(" · ")
}

/* The time shown at the card's top right: when it was done, else the planned block, else the due date. */
@Composable
internal fun cardTime(t: TimeEntryEntity, planned: List<SegmentEntity> = emptyList()): String? {
	doneText(t)?.let { return it }
	val today = LocalDate.now()
	/* planned segments without a plan: the first one's day and time */
	if (t.plan() == null)
		planned.minByOrNull { it.startUtcMs }?.let { s ->
			val at = Instant.ofEpochMilli(s.startUtcMs).atZone(ZoneId.systemDefault())
			val time = formatMin(at.hour * 60 + at.minute)
			return if (at.toLocalDate() == today) time else "${dayText(at.toLocalDate().toEpochDay())} $time"
		}
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
 * One to-do as a card (M10): the state's icon and colour (2.2), title and
 * time, client and priority by name; a deleted one is struck through.
 */
@Composable
fun TodoCard(t: TimeEntryEntity, clientName: String?, band: Int? = null, planned: List<SegmentEntity> = emptyList(), onClick: () -> Unit) {
	val state = displayOf(t, planned = planned)
	val style = displayStyle(state)
	val deleted = state == DisplayState.DELETED
	val title = t.title().ifBlank { if (t.isAbsence()) stringResource(R.string.state_absence) else "" }
	val compact = LocalCompact.current
	val body: @Composable () -> Unit = {
		Row(
			Modifier.padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 7.dp else 12.dp),
			verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Box(Modifier.size(28.dp).clip(CircleShape).background(style.container), contentAlignment = Alignment.Center) {
				Icon(painterResource(style.icon), contentDescription = stringResource(displayName(state)), tint = style.content, modifier = Modifier.size(18.dp))
			}
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
				Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						title,
						style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
						textDecoration = if (deleted) TextDecoration.LineThrough else null,
						maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
					)
					cardTime(t, planned)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
				}
				FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
					clientName?.let {
						Text(
							it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
							textDecoration = if (deleted) TextDecoration.LineThrough else null,
						)
					}
					val cs = MaterialTheme.colorScheme
					if (state == DisplayState.LATE)
						Chip(stringResource(displayName(state)), cs.errorContainer, cs.onErrorContainer)
					else if (state == DisplayState.PLANNED)
						Chip(stringResource(displayName(state)), cs.secondaryContainer, cs.onSecondaryContainer)
					Priority.of(t.priority).takeIf { it != Priority.NONE && !deleted }?.let { Chip(stringResource(priorityName(it)), cs.surfaceVariant, cs.onSurfaceVariant) }
				}
			}
		}
	}
	if (deleted)
		OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().alpha(0.6f)) { Banded(band) { body() } }
	else
		Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Banded(band) { body() } }
}

/* The buttons a swipe reveals on a to-do card ([swipe] for that direction). */
@Composable
internal fun todoSwipeActions(
	t: TimeEntryEntity,
	swipe: TodoSwipe,
	vm: TodosViewModel,
	snooze: Int,
	onStart: (Long) -> Unit,
	onReplan: (TimeEntryEntity) -> Unit,
): List<SwipeAction> {
	val grey = MaterialTheme.colorScheme.outline
	val canIgnore = LocalPolicy.current.enabled(Feature.IGNORE_TODOS)
	return Gestures.todoActions(swipe, ignored = t.deletedUtcMs != null, planned = t.plan() != null, done = t.status == ActivityStatus.CLOSED)
		.filter { canIgnore || (it != TodoAction.IGNORE && it != TodoAction.RESTORE) }.mapNotNull { a ->
		when (a) {
			TodoAction.START -> SwipeAction(stringResource(R.string.swipe_start), R.drawable.ic_cmd_play, GREEN, Color.White) { onStart(t.id) }
			TodoAction.POSTPONE -> SwipeAction(stringResource(R.string.action_postpone, snooze), R.drawable.ic_clock, BLUE, Color.White) { vm.postpone(t, snooze) }
			TodoAction.IGNORE -> SwipeAction(stringResource(R.string.action_delete), R.drawable.ic_ignore, grey, Color.White) { vm.delete(t) }
			TodoAction.RESTORE -> SwipeAction(stringResource(R.string.swipe_restore), R.drawable.ic_restore, BLUE, Color.White) { vm.restore(t) }
			TodoAction.DONE -> SwipeAction(stringResource(R.string.todo_mark_done), R.drawable.ic_done, GREEN, Color.White) { vm.tick(t) }
			TodoAction.REPLAN -> SwipeAction(stringResource(R.string.action_replan), R.drawable.ic_calendar, BLUE, Color.White) { onReplan(t) }
			/* a ticked to-do leaves this list: nothing to reopen here */
			TodoAction.REOPEN -> null
		}
	}
}

/* A to-do card with its swipe gestures (Home, To-dos, Agenda list). */
@Composable
fun TodoSwipeCard(
	t: TimeEntryEntity,
	clientName: String?,
	vm: TodosViewModel,
	onStart: (Long) -> Unit,
	onReplan: (TimeEntryEntity) -> Unit,
	band: Int? = null,
	planned: List<SegmentEntity> = emptyList(),
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
	) { TodoCard(t, clientName, band, planned, onClick) }
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
			label = { Text(label?.let { "$it ▾" } ?: current?.name ?: stringResource(R.string.all_companies), maxLines = 1, overflow = TextOverflow.Ellipsis) },
			leadingIcon = current?.color?.takeIf { label == null }?.let { c -> { Box(Modifier.size(10.dp).clip(CircleShape).background(rgb(c))) } },
			trailingIcon = if (current != null && label == null) ({
				Icon(
					painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.all_companies),
					modifier = Modifier.size(18.dp).clickable { vm.setCompanyFilter(null) },
				)
			}) else null,
		)
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			DropdownMenuItem(text = { Text(stringResource(R.string.all_companies)) }, onClick = { open = false; vm.setCompanyFilter(null) })
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

/* The band of each activity: its client's colour, else its company's. */
internal fun bands(list: List<TimeEntryEntity>, companies: List<CompanyEntity>, clients: List<ClientEntity>): Map<Long, Int?> {
	val co = companies.associate { it.id to it.color }
	val cl = clients.associate { it.id to it.color }
	/* the client's colour, else its company's (owner, 2026-11-11: the to-do has none) */
	return list.associate { t -> t.id to (t.clientId?.let { cl[it] } ?: companyColor(t.companyId, co[t.companyId])) }
}

/* Start / tick / postpone / re-plan / edit / delete (to the bin) for one to-do; the bin's ones: restore or purge. */
@Composable
internal fun TodoActions(t: TimeEntryEntity, vm: TodosViewModel, onStart: () -> Unit, onEdit: (() -> Unit)?, onDismiss: () -> Unit) {
	if (t.deletedUtcMs != null) {
		BinActions(t, vm, onDismiss)
		return
	}
	val clients by vm.clients.collectAsState()
	val settings by vm.settings.collectAsState()
	val planned by vm.planned.collectAsState()
	var deleting by remember { mutableStateOf(false) }
	if (deleting) {
		AlertDialog(
			onDismissRequest = { deleting = false },
			text = { Text(stringResource(R.string.confirm_delete, t.title())) },
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
		title = { Text(t.title()) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				val state = displayOf(t, planned = planned[t.id].orEmpty())
				val style = displayStyle(state)
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Box(Modifier.size(24.dp).clip(CircleShape).background(style.container), contentAlignment = Alignment.Center) {
						Icon(painterResource(style.icon), contentDescription = null, tint = style.content, modifier = Modifier.size(16.dp))
					}
					Text(stringResource(displayName(state)), style = MaterialTheme.typography.labelLarge)
				}
				Text(subtitle(t, clients.firstOrNull { it.id == t.clientId }?.name), style = MaterialTheme.typography.bodySmall)
				t.notes?.let { Text(it) }
				TextButton(onClick = { onDismiss(); onStart() }) { Text(stringResource(R.string.action_start_entry)) }
				TextButton(onClick = { vm.tick(t); onDismiss() }) { Text(stringResource(R.string.todo_mark_done)) }
				val snooze = settings?.planSnoozeMin
				if (t.plan() != null && snooze != null)
					TextButton(onClick = { vm.postpone(t, snooze); onDismiss() }) { Text(stringResource(R.string.action_postpone, snooze)) }
				if (t.plan() != null)
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

/* A deleted activity: "Ripristina", or "Elimina definitivamente" after a confirmation. */
@Composable
internal fun BinActions(t: TimeEntryEntity, vm: TodosViewModel, onDismiss: () -> Unit) {
	val error by vm.error.collectAsState()
	var purging by remember { mutableStateOf(false) }
	if (purging) {
		AlertDialog(
			onDismissRequest = { purging = false; vm.clearError() },
			text = {
				Column {
					Text(stringResource(R.string.bin_purge_confirm, t.title().ifBlank { stringResource(R.string.state_absence) }))
					error?.let { Text(stringResource(R.string.bin_purge_failed), color = MaterialTheme.colorScheme.error) }
				}
			},
			confirmButton = {
				TextButton(onClick = { vm.purge(t) { onDismiss() } }) {
					Text(stringResource(R.string.bin_purge), color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = { TextButton(onClick = { purging = false; vm.clearError() }) { Text(stringResource(R.string.action_cancel)) } },
		)
		return
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(t.title().ifBlank { stringResource(R.string.state_absence) }) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				TextButton(onClick = { vm.restore(t); onDismiss() }) { Text(stringResource(R.string.swipe_restore)) }
				TextButton(onClick = { vm.clearError(); purging = true }) { Text(stringResource(R.string.bin_purge), color = MaterialTheme.colorScheme.error) }
			}
		},
		confirmButton = {},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
	)
}

/*
 * The "Da fare" list: the TODO activities (displayed as TODO, PLANNED or
 * LATE), highest priority first, then by plan and due date; or the bin.
 * [editId]: open that to-do in the editor at once (re-plan from the
 * reminder). [onOpen]: the activity's detail route; without it a tap opens
 * the actions dialog.
 */
@Composable
fun TodosScreen(onBack: (() -> Unit)?, editId: Long? = null, onOpen: ((Long) -> Unit)? = null, onStart: (Long) -> Unit) {
	val vm = todosViewModel()
	val open by vm.open.collectAsState()
	val deleted by vm.deleted.collectAsState()
	val planned by vm.planned.collectAsState()
	val clients by vm.clients.collectAsState()
	var showBin by rememberSaveable { mutableStateOf(false) }
	var selected by remember { mutableStateOf<TimeEntryEntity?>(null) }
	var editing by remember { mutableStateOf<TimeEntryEntity?>(null) }
	var opened by rememberSaveable { mutableStateOf(false) }
	val companies by vm.companies.collectAsState()
	val settings by vm.settings.collectAsState()
	val filter = settings?.todoCompanyFilter
	val window = settings?.todoWindowHours ?: 0
	/* the advanced filters (owner, 2026-10-19), kept while the screen is open */
	var more by rememberSaveable(stateSaver = TodoFilters.Saver) { mutableStateOf(TodoFilters()) }
	var editingFilters by remember { mutableStateOf(false) }
	val zone = ZoneId.systemDefault()
	val now = Instant.now()
	val canBin = LocalPolicy.current.enabled(Feature.IGNORE_TODOS) || deleted.isNotEmpty()
	val list = if (showBin) deleted.filter { filter == null || it.companyId == filter }
	else open.filter { t ->
		(filter == null || t.companyId == filter) &&
			(window == 0 || withinHours(moment(t, zone), now, window.toLong())) &&
			more.accepts(t, now, planned[t.id].orEmpty())
	}.sortedByDescending { it.priority }
	val band = bands(list, companies, clients)

	LaunchedEffect(editId, open) {
		if (editId != null && !opened)
			open.firstOrNull { it.id == editId }?.let {
				vm.clearError()
				editing = it
				opened = true
			}
	}

	ScreenScaffold(
		title = stringResource(if (showBin) R.string.bin_title else R.string.todo_title),
		onBack = onBack,
		actions = { if (!showBin) AddButton { vm.clearError(); editing = newTodo() } },
	) { padding ->
		LazyColumn(
			modifier = Modifier.fillMaxSize().padding(padding),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			item {
				Column(Modifier.padding(horizontal = 16.dp)) {
					/* scrolls sideways when the chips do not fit: the bin chip wrapped its label on two lines */
					Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
						if (!showBin) {
							WindowFilter(window, vm::setWindow)
							FilterChip(
								selected = more.count > 0,
								onClick = { editingFilters = true },
								label = { Text(stringResource(R.string.todo_filters) + if (more.count > 0) " (${more.count})" else "") },
							)
						}
						CompanyFilter(vm)
						if (canBin)
							FilterChip(
								selected = showBin, onClick = { showBin = !showBin },
								leadingIcon = { Icon(painterResource(R.drawable.ic_state_deleted), contentDescription = null, modifier = Modifier.size(18.dp)) },
								label = { Text(stringResource(R.string.bin_title) + if (deleted.isNotEmpty()) " (${deleted.size})" else "") },
							)
					}
				}
			}
			if (list.isEmpty())
				item { EmptyText() }
			items(list, key = { it.id }) { t ->
				Column(Modifier.padding(horizontal = 16.dp)) {
					TodoSwipeCard(
						t, clients.firstOrNull { it.id == t.clientId }?.name, vm, onStart,
						onReplan = { vm.clearError(); editing = it }, band = band[t.id], planned = planned[t.id].orEmpty(),
					) { if (onOpen != null && !showBin) onOpen(t.id) else selected = t }
				}
			}
		}
	}
	selected?.let { t -> TodoActions(t, vm, onStart = { onStart(t.id) }, onEdit = { vm.clearError(); editing = t }) { selected = null } }
	editing?.let { t -> it.infraconsulting.activitytrack.ui.start.TodoDialog(t) { editing = null } }
	if (editingFilters)
		TodoFiltersDialog(more, clients.filter { filter == null || it.companyId == filter }.map { it.id to it.name }, { editingFilters = false }) { more = it }
}

/* Advanced to-do filters (owner, 2026-10-19): client, priority, displayed state (TODO, PLANNED, LATE), planned or not. */
data class TodoFilters(
	val clientId: Long? = null,
	val priority: Priority? = null,
	val state: DisplayState? = null,
	val planned: Boolean? = null,
) {
	val count get() = listOfNotNull(clientId, priority, state, planned).size

	fun accepts(t: TimeEntryEntity, now: Instant, plannedSegments: List<SegmentEntity> = emptyList()): Boolean =
		(clientId == null || t.clientId == clientId) &&
			(priority == null || Priority.of(t.priority) == priority) &&
			(planned == null || (t.plan() != null || plannedSegments.isNotEmpty()) == planned) &&
			(state == null || displayOf(t, now, plannedSegments) == state)

	companion object {
		val Saver = androidx.compose.runtime.saveable.Saver<TodoFilters, List<Any?>>(
			save = { listOf(it.clientId, it.priority?.name, it.state?.name, it.planned) },
			restore = { TodoFilters(it[0] as Long?, (it[1] as String?)?.let(Priority::valueOf), (it[2] as String?)?.let(DisplayState::valueOf), it[3] as Boolean?) },
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
					stringResource(R.string.priority),
					listOf<Pair<Priority?, String>>(null to any) + Priority.entries.reversed().map { it to stringResource(priorityName(it)) },
					f.priority,
				) { f = f.copy(priority = it) }
				ChoiceField(
					stringResource(R.string.home_filter_state),
					listOf<Pair<DisplayState?, String>>(null to any) +
						listOf(DisplayState.TODO, DisplayState.PLANNED, DisplayState.LATE).map { it to stringResource(displayName(it)) },
					f.state,
				) { f = f.copy(state = it) }
				ChoiceField(
					stringResource(R.string.state_planned),
					listOf(null to any, true to stringResource(R.string.action_yes), false to stringResource(R.string.action_no)),
					f.planned,
				) { f = f.copy(planned = it) }
			}
		},
		confirmButton = { TextButton(onClick = { onApply(f); onClose() }) { Text(stringResource(R.string.action_ok)) } },
		dismissButton = { TextButton(onClick = { onApply(TodoFilters()); onClose() }) { Text(stringResource(R.string.todo_filters_reset)) } },
	)
}
