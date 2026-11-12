/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.agenda

import it.infraconsulting.activitytrack.ui.common.TagDots
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import it.infraconsulting.activitytrack.ui.common.companyColor
import it.infraconsulting.activitytrack.ui.common.NO_COLOR
import it.infraconsulting.activitytrack.ui.common.ClearButton
import it.infraconsulting.activitytrack.ui.common.TagFilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.R
import kotlinx.coroutines.flow.first
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import it.infraconsulting.activitytrack.ui.common.rgb
import it.infraconsulting.activitytrack.data.repo.isAbsence
import it.infraconsulting.activitytrack.domain.time.atMinute
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import it.infraconsulting.activitytrack.ui.todo.todoSwipeActions
import it.infraconsulting.activitytrack.ui.todo.TodosViewModel
import it.infraconsulting.activitytrack.ui.home.RunAction
import it.infraconsulting.activitytrack.ui.home.rememberRunCommands
import it.infraconsulting.activitytrack.ui.home.runSwipeActions
import it.infraconsulting.activitytrack.ui.home.resumeKindOf
import it.infraconsulting.activitytrack.ui.home.QuickActioner
import it.infraconsulting.activitytrack.ui.common.SwipeCard
import it.infraconsulting.activitytrack.domain.settings.GestureSettings
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import androidx.compose.ui.graphics.RectangleShape
import it.infraconsulting.activitytrack.data.repo.ReportRepository
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.ui.common.displayName
import it.infraconsulting.activitytrack.ui.common.displayStyle
import it.infraconsulting.activitytrack.ui.todo.displayOf
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.AddButton
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.fold
import it.infraconsulting.activitytrack.ui.todo.TodoActions
import it.infraconsulting.activitytrack.ui.start.TodoDialog
import it.infraconsulting.activitytrack.ui.todo.newTodo
import it.infraconsulting.activitytrack.ui.todo.todosViewModel
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

enum class AgendaMode { LIST, DAY, WEEK, MONTH }

/* One report line (entry and day), ready to draw. */
data class AgendaItem(
	val entryId: Long,
	val day: LocalDate,
	val start: Instant,
	val end: Instant,
	val title: String,
	val activity: String,
	val reported: Duration,
	val companyId: Long?,
	val running: Boolean,
	/* the entry itself: open ones swipe (M10) */
	val entry: TimeEntryEntity? = null,
	val clientId: Long? = null,
	val tags: List<String> = emptyList(),
	/* "Riprendi" of a running entry's swipe: the kind open before the pause (3) */
	val resumeKind: SegmentKind = SegmentKind.WORK,
)

/*
 * A TODO activity on the day it is planned (a block from [start] to [end],
 * dashed: it is a plan), or, without a plan, on its due day (a line,
 * [timeMin] the due time); or one absence segment, solid in the ABSENCE
 * style. [state] is the displayed state (ACTIVITY-MODEL.md 2.2).
 */
data class AgendaTodo(
	val todo: TimeEntryEntity,
	val day: LocalDate,
	val timeMin: Int?,
	val start: Instant? = null,
	val end: Instant? = null,
	/* planned by day only (issue #52): the all-day strip */
	val allDay: Boolean = false,
	val state: DisplayState = DisplayState.TODO,
) {
	val id get() = todo.id
	val title get() = todo.activity.orEmpty()
	val absence get() = todo.isAbsence()
	val planned get() = start != null || allDay
}

/* the title; an absence without one is just "Absence" (issue #158) */
@Composable
private fun AgendaTodo.label(): String = title.ifBlank {
	if (!absence) "" else todo.absenceKind?.let { stringResource(absenceKindName(it)) } ?: stringResource(R.string.state_absence)
}

private fun absenceKindName(k: AbsenceKind): Int = when (k) {
	AbsenceKind.ABSENCE -> R.string.absence_kind_absence
	AbsenceKind.HOLIDAY -> R.string.absence_kind_holiday
	AbsenceKind.LEAVE -> R.string.absence_kind_leave
	AbsenceKind.SICK -> R.string.absence_kind_sick
}

data class AgendaData(
	val from: LocalDate,
	val to: LocalDate,
	val items: List<AgendaItem>,
	val todos: List<AgendaTodo>,
	val reported: Map<LocalDate, Duration>,
	/* working hours of the visible days, minutes of the day (backlog 6); null = none set */
	val workFrom: Int? = null,
	val workTo: Int? = null,
)

/*
 * Consultation (M6): the same lines as the reports (day attribution,
 * rounding, a running entry ending now) for the visible period, plus the
 * open to-dos planned or due in it. Reloads on every entry or to-do change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModel(
	private val reports: ReportRepository,
	private val settings: SettingsRepository,
	private val entries: EntryRepository,
	private val master: MasterDataRepository,
) : ViewModel() {
	val mode = MutableStateFlow(AgendaMode.LIST)
	val anchor = MutableStateFlow(LocalDate.now())

	/* the last view picked comes back (issue #44) and every change is kept */
	init {
		viewModelScope.launch {
			runCatching { AgendaMode.valueOf(settings.current().agendaView) }.getOrNull()?.let { mode.value = it }
			mode.drop(1).collect { m -> settings.update { it.copy(agendaView = m.name) } }
		}
	}

	val data = combine(mode, anchor, reports.changes, entries.todos, entries.absences) { m, a, _, open, absences -> Triple(m, a, open + absences) }
		.mapLatest { (m, a, open) -> load(m, a, open) }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	fun move(step: Int) {
		anchor.value = when (mode.value) {
			AgendaMode.DAY -> anchor.value.plusDays(step.toLong())
			AgendaMode.WEEK -> anchor.value.plusWeeks(step.toLong())
			AgendaMode.LIST, AgendaMode.MONTH -> anchor.value.plusMonths(step.toLong())
		}
	}

	fun open(day: LocalDate, m: AgendaMode) {
		anchor.value = day
		mode.value = m
	}

	private suspend fun load(m: AgendaMode, a: LocalDate, open: List<TimeEntryEntity>): AgendaData {
		val (from, to) = range(m, a)
		val s = settings.current()
		val zone = ZoneId.systemDefault()
		val report = reports.report(from, to, zone, s.rounding, s.carryOverMin)
		val around = reports.entriesAround(from, to, zone).associateBy { it.entry.id }
		val items = report.days.flatMap { d ->
			d.lines.map { l ->
				val meta = report.meta[l.entryId]
				val e = around[l.entryId]?.entry
				AgendaItem(
					entryId = l.entryId,
					day = d.day,
					start = l.firstStart,
					end = maxOf(l.lastEnd, l.firstStart.plusSeconds(60)),
					title = meta?.client?.ifBlank { meta.company }.orEmpty(),
					activity = meta?.activity.orEmpty(),
					reported = l.reported,
					companyId = e?.companyId,
					running = e?.let { it.status == ActivityStatus.RUNNING && it.mode != null } == true,
					entry = e,
					clientId = e?.clientId,
					tags = meta?.tags.orEmpty(),
					resumeKind = around[l.entryId]?.let { resumeKindOf(it.entry, it.segments) } ?: SegmentKind.WORK,
				)
			}
		}
		val now = Instant.now()
		val weekdays = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.map { it.dayOfWeek.value }.toSet()
		val work = master.schedule.first().filter { it.dayOfWeek in weekdays }
		val absences = open.filter { it.isAbsence() }.associateBy { it.id }
		/* one block per absence segment (3.1), solid in the ABSENCE style */
		val absenceBlocks = entries.segmentsTouching(from.atStartOfDay(zone).toInstant().toEpochMilli(), to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
			.filter { it.kind == SegmentKind.ABSENCE && it.endUtcMs != null }
			.mapNotNull { sg ->
				val t = absences[sg.entryId] ?: return@mapNotNull null
				val start = Instant.ofEpochMilli(sg.startUtcMs)
				val day = start.atZone(zone).toLocalDate()
				AgendaTodo(t, day, start.atZone(zone).let { it.hour * 60 + it.minute }, start, Instant.ofEpochMilli(sg.endUtcMs!!), state = DisplayState.ABSENCE)
			}
		/* a TODO activity: its planned segments (dashed blocks), else its plan, else its due day */
		val todos = open.filter { it.status == ActivityStatus.TODO }.flatMap { t ->
			val segments = entries.segments(t.id).first().filter { it.planned && it.endUtcMs != null }
			val state = displayOf(t, now, segments)
			val p = t.plan()
			when {
				segments.isNotEmpty() -> segments.map { sg ->
					val start = Instant.ofEpochMilli(sg.startUtcMs)
					val local = start.atZone(zone)
					AgendaTodo(t, local.toLocalDate(), local.hour * 60 + local.minute, start, Instant.ofEpochMilli(sg.endUtcMs!!), state = state)
				}
				p != null -> listOf(AgendaTodo(t, p.day, p.startMin, p.start(zone).takeIf { !p.allDay }, p.end(zone).takeIf { !p.allDay }, allDay = p.allDay, state = state))
				else -> listOfNotNull(t.dueEpochDay?.let { AgendaTodo(t, LocalDate.ofEpochDay(it), t.dueTimeMin, state = state) })
			}.filter { it.day in from..to }
		} + absenceBlocks
		return AgendaData(
			from, to, items, todos, report.days.associate { it.day to it.reported },
			work.minOfOrNull { it.startMin }, work.maxOfOrNull { it.endMin },
		)
	}

	companion object {
		/* LIST: the month; MONTH: whole weeks around it; WEEK: Monday..Sunday; DAY: the day. */
		fun range(m: AgendaMode, a: LocalDate): Pair<LocalDate, LocalDate> = when (m) {
			AgendaMode.DAY -> a to a
			AgendaMode.WEEK -> a.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
			AgendaMode.LIST -> YearMonth.from(a).let { it.atDay(1) to it.atEndOfMonth() }
			AgendaMode.MONTH -> YearMonth.from(a).let {
				it.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to
					it.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
			}
		}
	}
}

/* company colours chosen by the owner (M12); the others keep the automatic one */
private val LocalCompanyColors = staticCompositionLocalOf { emptyMap<Long, Int>() }
private val LocalClientColors = staticCompositionLocalOf { emptyMap<Long, Int>() }

/* an activity takes its client's colour, else its company's (owner, 2026-11-11) */
@Composable
private fun colorOf(clientId: Long?, companyId: Long?): Color {
	val client = clientId?.let { LocalClientColors.current[it] }
	return rgb(client ?: companyColor(companyId, companyId?.let { LocalCompanyColors.current[it] }) ?: NO_COLOR)
}

/* the outline of a plan: dashed (ACTIVITY-MODEL.md 1.2) */
private fun Modifier.dashed(color: Color, radius: Dp = 4.dp): Modifier = drawBehind {
	val w = 1.5.dp.toPx()
	drawRoundRect(
		color, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
		size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
		cornerRadius = CornerRadius(radius.toPx()), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))),
	)
}

/* the icon of a displayed state in its colour */
@Composable
private fun StateDot(state: DisplayState, size: Dp = 16.dp) {
	val style = displayStyle(state)
	Box(Modifier.size(size).clip(CircleShape).background(style.container), contentAlignment = Alignment.Center) {
		Icon(painterResource(style.icon), contentDescription = stringResource(displayName(state)), tint = style.content, modifier = Modifier.size(size * 0.7f))
	}
}

private fun hm(d: Duration) = "%d:%02d".format(d.toHours(), d.toMinutesPart())

private fun clock(i: Instant, zone: ZoneId): String = i.atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

@Composable
fun AgendaScreen(onOpenEntry: (Long) -> Unit, onStartTodo: (Long) -> Unit) {
	val vm = appViewModel { AgendaViewModel(it.reports, it.settings, it.entries, it.masterData) }
	/* vertical scale of the day and week views, by pinch (issue #44); 1 = the working hours fill the screen */
	var zoom by rememberSaveable { mutableStateOf(1f) }
	val todosVm = todosViewModel()
	val mode by vm.mode.collectAsState()
	val anchor by vm.anchor.collectAsState()
	val loaded by vm.data.collectAsState()
	/* tag filter (M19): only the entries with that tag, to-dos hidden, totals of what is shown */
	var tag by rememberSaveable { mutableStateOf<String?>(null) }
	val data = loaded?.let { d -> tag?.let { d.withTag(it) } ?: d }
	val locale = LocalConfiguration.current.locales[0]
	var selected by remember { mutableStateOf<TimeEntryEntity?>(null) }
	var editing by remember { mutableStateOf<TimeEntryEntity?>(null) }
	/* planning from the agenda (M9): a tap on a free slot or "Add" opens a new planned to-do */
	val policy = it.infraconsulting.activitytrack.ui.common.LocalPolicy.current
	val canPlan = policy.enabled(it.infraconsulting.activitytrack.domain.policy.Feature.PLANNING) && policy.enabled(it.infraconsulting.activitytrack.domain.policy.Feature.TODOS)
	val plan: (LocalDate, Int) -> Unit = { day, min -> if (canPlan) { todosVm.clearError(); editing = newTodo(day, min) } }
	val onTodo: (TimeEntryEntity) -> Unit = { if (it.isAbsence()) onOpenEntry(it.id) else selected = it }
	/* swipes on open entries run the commands like Home (owner, 2026-10-18: gestures everywhere) */
	val commander = appViewModel(key = "agenda-commands") { QuickActioner(it.entries) }
	val settings by todosVm.settings.collectAsState()
	val gestures = settings?.gestures ?: GestureSettings()
	val fallback = stringResource(R.string.entry_no_client)
	val run = rememberRunCommands(commander, settings?.editableTimes == true) { id -> data?.items?.firstOrNull { it.entryId == id }?.title?.ifBlank { null } ?: fallback }
	val swipes = ListSwipes(gestures, run, todosVm, onStartTodo) { todosVm.clearError(); editing = it }

	val companies by todosVm.companies.collectAsState()
	val companyColors = companies.mapNotNull { c -> c.color?.let { c.id to it } }.toMap()
	val clientColors = todosVm.clients.collectAsState().value.mapNotNull { c -> c.color?.let { c.id to it } }.toMap()
	CompositionLocalProvider(LocalCompanyColors provides companyColors, LocalClientColors provides clientColors) {
		ScreenScaffold(
			title = stringResource(R.string.nav_agenda),
			onBack = null,
			actions = {
				AddButton { plan(anchor, defaultSlot(anchor)) }
				TextButton(onClick = { vm.anchor.value = LocalDate.now() }) { Text(stringResource(R.string.day_today)) }
			},
		) { padding ->
			Column(Modifier.fillMaxSize().padding(padding)) {
				Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					/* the view may be fixed by the policy (agendaView): locked, the others greyed; hidden, no choice */
					val viewLock = it.infraconsulting.activitytrack.ui.common.settingLock("agendaView")
					if (viewLock != it.infraconsulting.activitytrack.ui.common.SettingLock.HIDDEN)
						for ((m, label) in listOf(
							AgendaMode.LIST to R.string.agenda_list, AgendaMode.DAY to R.string.agenda_day,
							AgendaMode.WEEK to R.string.agenda_week, AgendaMode.MONTH to R.string.agenda_month,
						))
							FilterChip(
								selected = mode == m, onClick = { vm.mode.value = m },
								enabled = viewLock == it.infraconsulting.activitytrack.ui.common.SettingLock.FREE || mode == m,
								label = { Text(stringResource(label)) },
							)
					TagFilterChip(loaded?.items?.flatMap { it.tags }?.distinct().orEmpty(), tag) { tag = it }
				}
				Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
					TextButton(onClick = { vm.move(-1) }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
					Text(periodTitle(mode, anchor, locale), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
					data?.let { d ->
						val total = d.reported.values.fold(Duration.ZERO, Duration::plus)
						if (!total.isZero && mode != AgendaMode.MONTH)
							Text(hm(total), style = MaterialTheme.typography.titleMedium)
					}
					TextButton(onClick = { vm.move(1) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
				}
				HorizontalDivider()
				val d = data ?: return@Column
				when (mode) {
					AgendaMode.LIST -> ListView(d, locale, onOpenEntry, onTodo, swipes)
					AgendaMode.DAY -> Timeline(listOf(anchor), d, locale, zoom, { zoom = it }, onOpenEntry, onTodo, plan)
					AgendaMode.WEEK -> Timeline((0L..6L).map { d.from.plusDays(it) }, d, locale, zoom, { zoom = it }, onOpenEntry, onTodo, plan) {
						vm.open(it, AgendaMode.DAY)
					}
					AgendaMode.MONTH -> MonthView(anchor, d, locale) { vm.open(it, AgendaMode.DAY) }
				}
			}
		}
		selected?.let { t ->
			TodoActions(t, todosVm, onStart = { onStartTodo(t.id) }, onEdit = { todosVm.clearError(); editing = t }) { selected = null }
		}
		editing?.let { t -> TodoDialog(t) { editing = null } }
	}
}

/* Only the entries carrying [tag]; to-dos have no tags and leave; day totals of what stays. */
private fun AgendaData.withTag(tag: String): AgendaData {
	val kept = items.filter { tag in it.tags }
	return copy(
		items = kept,
		todos = emptyList(),
		reported = kept.groupBy { it.day }.mapValues { (_, l) -> l.fold(Duration.ZERO) { a, i -> a + i.reported } },
	)
}

/* What the list rows need to swipe (M10). */
private class ListSwipes(
	val gestures: GestureSettings,
	val run: (TimeEntryEntity, RunAction) -> Unit,
	val todos: TodosViewModel,
	val onStartTodo: (Long) -> Unit,
	val onReplan: (TimeEntryEntity) -> Unit,
)

/* "Add": the next half hour today, 09:00 on other days. */
private fun defaultSlot(day: LocalDate): Int {
	if (day != LocalDate.now())
		return 9 * 60
	val now = LocalTime.now()
	return minOf(((now.hour * 60 + now.minute) / 30 + 1) * 30, 23 * 60 + 30)
}

private fun periodTitle(m: AgendaMode, a: LocalDate, locale: Locale): String = when (m) {
	AgendaMode.DAY -> a.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
	AgendaMode.WEEK -> AgendaViewModel.range(m, a).let { (f, t) ->
		val short = DateTimeFormatter.ofPattern("d MMM", locale)
		"${f.format(short)} – ${t.format(short)}"
	}
	AgendaMode.LIST, AgendaMode.MONTH ->
		YearMonth.from(a).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }
}

/* Days of the month with their lines and to-dos; the search matches client, activity and tags ("#tag" too). */
@Composable
private fun ListView(d: AgendaData, locale: Locale, onOpenEntry: (Long) -> Unit, onTodo: (TimeEntryEntity) -> Unit, swipes: ListSwipes) {
	var query by rememberSaveable { mutableStateOf("") }
	val zone = ZoneId.systemDefault()
	val q = fold(query)
	val tq = q.removePrefix("#")
	val items = d.items.filter {
		q.isEmpty() || fold(it.title).contains(q) || fold(it.activity).contains(q) || (tq.isNotEmpty() && it.tags.any { t -> fold(t).contains(tq) })
	}
	val todos = d.todos.filter { q.isEmpty() || fold(it.title).contains(q) }
	val days = (items.map { it.day } + todos.map { it.day }).distinct().sortedDescending()
	val dayFmt = DateTimeFormatter.ofPattern("EEEE d", locale)
	Column {
		OutlinedTextField(
			value = query, onValueChange = { query = it }, singleLine = true,
			label = { Text(stringResource(R.string.agenda_search)) },
			trailingIcon = if (query.isEmpty()) null else ({ ClearButton { query = "" } }),
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
		)
		if (days.isEmpty()) {
			Text(stringResource(R.string.agenda_empty), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
			return
		}
		LazyColumn {
			for (day in days) {
				item(key = "h$day") {
					Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
						Text(day.format(dayFmt).replaceFirstChar { it.titlecase(locale) }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
						d.reported[day]?.takeIf { !it.isZero }?.let { Text(hm(it), style = MaterialTheme.typography.titleSmall) }
					}
				}
				items(items.filter { it.day == day }.sortedBy { it.start }, key = { "e${it.entryId}/$day" }) { it ->
					val e = it.entry?.takeIf { e -> e.status == ActivityStatus.RUNNING }
					SwipeCard(
						toStart = e?.let { e -> runSwipeActions(e, it.resumeKind, swipes.gestures.entryToStart) { swipes.run(e, it) } }.orEmpty(),
						toEnd = e?.let { e -> runSwipeActions(e, it.resumeKind, swipes.gestures.entryToEnd) { swipes.run(e, it) } }.orEmpty(),
						fullSwipe = swipes.gestures.fullSwipe,
						shape = RectangleShape,
					) {
					Row(
						Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable { onOpenEntry(it.entryId) }.padding(horizontal = 16.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Box(Modifier.size(10.dp).clip(CircleShape).background(colorOf(it.clientId, it.companyId)))
						Spacer(Modifier.width(12.dp))
						Column(Modifier.weight(1f)) {
							Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
							Text(
								"${clock(it.start, zone)}–${if (it.running) "…" else clock(it.end, zone)}" +
									(if (it.activity.isNotBlank()) " · ${it.activity}" else "") +
									it.tags.joinToString("") { t -> " #$t" },
								style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
							/* the tags' colours (issue #133) */
							TagDots(it.tags)
							Text(hm(it.reported), style = MaterialTheme.typography.bodyMedium)
						}
					}
					}
				}
				items(todos.filter { it.day == day }.sortedBy { it.timeMin ?: 1440 }, key = { "t${it.id}" }) { t ->
					val g = swipes.gestures
					val snooze = swipes.todos.settings.collectAsState().value?.planSnoozeMin ?: 30
					SwipeCard(
						toStart = todoSwipeActions(t.todo, g.todoToStart, swipes.todos, snooze, swipes.onStartTodo, swipes.onReplan),
						toEnd = todoSwipeActions(t.todo, g.todoToEnd, swipes.todos, snooze, swipes.onStartTodo, swipes.onReplan),
						fullSwipe = g.fullSwipe,
						shape = RectangleShape,
					) {
					Row(
						Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable { onTodo(t.todo) }.padding(horizontal = 16.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						StateDot(t.state)
						Spacer(Modifier.width(10.dp))
						Text(
							listOfNotNull(
								t.timeMin?.let { "%02d:%02d".format(it / 60, it % 60) + (t.end?.takeIf { e -> e != t.start }?.let { e -> "–${clock(e, zone)}" } ?: "") },
								t.label(),
							).joinToString(" "),
							maxLines = 1, overflow = TextOverflow.Ellipsis,
							color = if (t.absence) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
						)
					}
					}
				}
			}
		}
	}
}

/*
 * Day or week timeline: hours down the side, one column per day, entries as
 * blocks from start to end, planned to-dos as outlined blocks; concurrent
 * blocks share the column width. A tap on a free slot plans a to-do there
 * (half-hour steps); unplanned to-dos due in the period are listed on top.
 */
@Composable
private fun Timeline(
	days: List<LocalDate>,
	d: AgendaData,
	locale: Locale,
	zoom: Float,
	onZoom: (Float) -> Unit,
	onOpenEntry: (Long) -> Unit,
	onTodo: (TimeEntryEntity) -> Unit,
	onPlan: (LocalDate, Int) -> Unit,
	onDayHeader: ((LocalDate) -> Unit)? = null,
) {
	val zone = ZoneId.systemDefault()
	val byDay = days.associateWith { day ->
		d.items.filter { it.day == day }.map { Block(it.start, it.end, it, null) } +
			/* a point in time shows as a short block (issue #52) */
			d.todos.filter { it.day == day && it.planned && !it.allDay }.map { Block(it.start!!, maxOf(it.end!!, it.start.plusSeconds(15 * 60)), null, it) }
	}
	fun minuteOf(i: Instant, day: LocalDate): Int {
		val local = i.atZone(zone)
		val base = local.toLocalDate().toEpochDay() - day.toEpochDay()
		return (base * 1440 + local.hour * 60 + local.minute).toInt()
	}
	val all = byDay.flatMap { (day, blocks) -> blocks.map { minuteOf(it.start, day) to minuteOf(it.end, day) } }
	/*
	 * The whole day scrolls; at zoom 1 the working hours (and the blocks
	 * inside them) fill the screen (owner, issue #44), pinch changes the scale.
	 */
	val workFrom = d.workFrom ?: 420
	val workTo = d.workTo ?: 1200
	val from = workFrom.coerceAtLeast(0)
	val to = workTo.coerceAtMost(1440).coerceAtLeast(from + 60)
	val firstHour = 0
	val lastHour = maxOf(24, ((all.maxOfOrNull { it.second } ?: 0) + 59) / 60).coerceAtMost(30)
	val minutes = (lastHour - firstHour) * 60
	val today = LocalDate.now()

	Column {
		if (days.size > 1)
			Row(Modifier.padding(start = 40.dp)) {
				for (day in days)
					Column(
						Modifier.weight(1f).clickable(enabled = onDayHeader != null) { onDayHeader?.invoke(day) }.padding(vertical = 4.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
					) {
						Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelSmall)
						Text(
							day.dayOfMonth.toString(),
							fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
							color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
						)
					}
			}
		/* the strip above the grid: due dates without time and plans for the whole day (issue #52) */
		val dayTodos = d.todos.filter { it.day in days && (!it.planned || it.allDay) }
		for (t in dayTodos)
			Row(
				Modifier.fillMaxWidth().clickable { onTodo(t.todo) }.padding(horizontal = 16.dp, vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				StateDot(t.state, 14.dp)
				Spacer(Modifier.width(8.dp))
				Text("${t.day.dayOfMonth} · " + t.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
			}
		BoxWithConstraints(Modifier.fillMaxSize()) {
			val minuteHeight = maxHeight / (to - from) * zoom
			val scroll = rememberScrollState()
			val scope = rememberCoroutineScope()
			val zoomNow by rememberUpdatedState(zoom)
			/* opens on the running entry, else now (today), else the start of work (backlog 6) */
			val density = LocalDensity.current
			val focus = byDay.entries.firstNotNullOfOrNull { (day, blocks) -> blocks.firstOrNull { it.item?.running == true }?.let { minuteOf(it.start, day) } }
				?: days.firstOrNull { it == today }?.let { LocalTime.now().let { t -> t.hour * 60 + t.minute } }
				?: workFrom
			/* the working hours at the top, unless the focus (running entry, now) lies outside them */
			LaunchedEffect(days.first(), minutes) {
				val top = if (focus in from..to) from else focus - 60
				scroll.scrollTo(with(density) { (minuteHeight * maxOf(0, top - firstHour * 60)).roundToPx() })
			}
			Row(
				Modifier.fillMaxSize()
					/* two fingers: zoom the time axis around the fingers, before the scroll sees them */
					.pointerInput(Unit) {
						awaitEachGesture {
							awaitFirstDown(requireUnconsumed = false)
							do {
								val event = awaitPointerEvent(PointerEventPass.Initial)
								if (event.changes.count { it.pressed } >= 2) {
									val factor = event.calculateZoom()
									if (factor != 1f) {
										val old = zoomNow
										val new = (old * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
										val y = event.calculateCentroid().y
										onZoom(new)
										scope.launch { scroll.scrollTo(((scroll.value + y) * new / old - y).toInt().coerceAtLeast(0)) }
									}
									event.changes.forEach { it.consume() }
								}
							} while (event.changes.any { it.pressed })
						}
					}
					.verticalScroll(scroll),
			) {
				Box(Modifier.width(40.dp).height(minuteHeight * minutes)) {
					for (h in firstHour until lastHour)
						Text(
							"%02d".format(h % 24), style = MaterialTheme.typography.labelSmall,
							modifier = Modifier.offset(x = 8.dp, y = minuteHeight * ((h - firstHour) * 60)),
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
				}
				for (day in days)
					BoxWithConstraints(
						Modifier.weight(1f).height(minuteHeight * minutes).pointerInput(day, firstHour, minuteHeight) {
							detectTapGestures { o ->
								val min = firstHour * 60 + (o.y / minuteHeight.toPx()).toInt() / 30 * 30
								if (min >= 1440) onPlan(day.plusDays(1), min - 1440) else onPlan(day, min)
							}
						},
					) {
						for (h in firstHour until lastHour)
							HorizontalDivider(Modifier.offset(y = minuteHeight * ((h - firstHour) * 60)), color = MaterialTheme.colorScheme.outlineVariant)
						val blocks = byDay.getValue(day).sortedBy { it.start }
						val columns = columnsOf(blocks.map { minuteOf(it.start, day) to minuteOf(it.end, day) })
						val count = (columns.maxOrNull() ?: 0) + 1
						val width = maxWidth / count
						blocks.forEachIndexed { i, b ->
							val top = (minuteOf(b.start, day) - firstHour * 60).coerceAtLeast(0)
							val bottom = (minuteOf(b.end, day) - firstHour * 60).coerceAtMost(minutes)
							val place = Modifier.offset(x = width * columns[i], y = minuteHeight * top)
								.width(width).height(maxOf(minuteHeight * (bottom - top), 18.dp)).padding(1.dp)
							val one = days.size == 1
							val span = "${clock(b.start, zone)}–${clock(b.end, zone)}"
							b.item?.let {
								Box(
									place.clip(RoundedCornerShape(4.dp))
										.background(colorOf(it.clientId, it.companyId).copy(alpha = if (it.running) 0.6f else 0.85f))
										.clickable { onOpenEntry(it.entryId) }.padding(horizontal = 3.dp, vertical = 1.dp),
								) {
									Text(
										if (one) "${it.title}${if (it.activity.isNotBlank()) " · ${it.activity}" else ""}\n$span · ${hm(it.reported)}"
										else it.title,
										color = Color.White, fontSize = if (one) 12.sp else 10.sp, lineHeight = 13.sp,
										overflow = TextOverflow.Ellipsis,
									)
									/* the tags' colours where the block is wide enough: the day view (issue #133) */
									if (one)
										TagDots(it.tags, Modifier.align(Alignment.TopEnd).padding(top = 2.dp), max = 3, size = 7.dp)
								}
							}
							b.todo?.let {
								/* an absence: solid in the ABSENCE style; a plan: dashed in the activity's colour, brown when late */
								val c = when {
									it.absence -> displayStyle(DisplayState.ABSENCE).container
									it.state == DisplayState.LATE -> displayStyle(DisplayState.LATE).container
									else -> colorOf(it.todo.clientId, it.todo.companyId)
								}
								val shape = RoundedCornerShape(4.dp)
								Box(
									place.clip(shape)
										.then(if (it.absence) Modifier.background(c.copy(alpha = 0.85f)) else Modifier.background(c.copy(alpha = 0.12f)).dashed(c))
										.clickable { onTodo(it.todo) }.padding(horizontal = 3.dp, vertical = 1.dp),
								) {
									Text(
										it.label() + (if (one) "\n$span" else ""),
										color = if (it.absence) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = if (one) 12.sp else 10.sp, lineHeight = 13.sp,
										overflow = TextOverflow.Ellipsis,
									)
								}
							}
						}
					}
			}
		}
	}
}

/* A timeline block: a report line or a planned to-do. */
private class Block(val start: Instant, val end: Instant, val item: AgendaItem?, val todo: AgendaTodo?)

/* Greedy column assignment of intervals sorted by start: each takes the first free column. */
internal fun columnsOf(intervals: List<Pair<Int, Int>>): List<Int> {
	val ends = ArrayList<Int>()
	return intervals.map { (start, end) ->
		val free = ends.indexOfFirst { it <= start }
		if (free >= 0) {
			ends[free] = end
			free
		} else {
			ends += end
			ends.size - 1
		}
	}
}

/* Month grid: reported hours per day and a mark for to-dos due; a tap opens the day. */
@Composable
private fun MonthView(anchor: LocalDate, d: AgendaData, locale: Locale, onDay: (LocalDate) -> Unit) {
	val month = YearMonth.from(anchor)
	val today = LocalDate.now()
	val weeks = generateSequence(d.from) { it.plusDays(7) }.takeWhile { it <= d.to }.toList()
	Column(Modifier.padding(horizontal = 8.dp)) {
		Row {
			for (i in 0L..6L)
				Text(
					DayOfWeek.MONDAY.plus(i).getDisplayName(TextStyle.SHORT, locale),
					style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).padding(vertical = 4.dp),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
		}
		for (w in weeks)
			Row {
				for (i in 0L..6L) {
					val day = w.plusDays(i)
					val inMonth = YearMonth.from(day) == month
					val reported = d.reported[day]?.takeIf { !it.isZero }
					val dots = d.items.filter { it.day == day && it.companyId != null }.map { it.clientId to it.companyId }.distinct().take(4)
					Column(
						Modifier.weight(1f).aspectRatio(0.8f).padding(2.dp).clip(RoundedCornerShape(6.dp))
							.background(if (day == today) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (inMonth) 0.6f else 0.2f))
							.clickable { onDay(day) }.padding(4.dp),
					) {
						Text(
							day.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium,
							fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
							color = if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
						)
						Spacer(Modifier.weight(1f))
						Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
							for ((cl, c) in dots)
								Box(Modifier.size(6.dp).clip(CircleShape).background(colorOf(cl, c)))
							if (d.todos.any { it.day == day })
								Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
						}
						reported?.let { Text(hm(it), style = MaterialTheme.typography.labelSmall) }
					}
				}
			}
	}
}

/* pinch limits of the time axis: from the whole day on a screen to 4× the working hours */
private const val MIN_ZOOM = 0.3f
private const val MAX_ZOOM = 4f
