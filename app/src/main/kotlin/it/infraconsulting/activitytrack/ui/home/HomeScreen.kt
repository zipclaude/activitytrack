/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.ui.common.companyColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.alpha
import it.infraconsulting.activitytrack.ui.common.SwipeCard
import it.infraconsulting.activitytrack.domain.settings.GestureSettings
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.ui.common.Banded
import it.infraconsulting.activitytrack.data.repo.StartRequest
import it.infraconsulting.activitytrack.ui.common.SwipeAction
import it.infraconsulting.activitytrack.ui.LocalCompact
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.CommandIconButton
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.StatePill
import it.infraconsulting.activitytrack.ui.common.commandStyle
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.stateStyle
import it.infraconsulting.activitytrack.ui.todo.TodoActions
import it.infraconsulting.activitytrack.ui.todo.TodoCard
import it.infraconsulting.activitytrack.ui.todo.TodoEditDialog
import it.infraconsulting.activitytrack.ui.todo.TodoSwipeCard
import it.infraconsulting.activitytrack.ui.todo.bands
import it.infraconsulting.activitytrack.ui.todo.todosViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.LocalConfiguration
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/* An open entry with display names and its segments. */
data class EntryCard(
	val entry: TimeEntryEntity,
	val client: String?,
	val company: String?,
	val segments: List<SegmentEntity>,
	/* company colour for the card band (M12) */
	val companyColor: Int? = null,
	/* the entry's tags, set on the "Chiuse" rows only (issue #59) */
	val tags: Set<String> = emptySet(),
	/* the last segment end: the sort key of the "Chiuse" list (issue #59) */
	val endUtcMs: Long? = null,
) {
	val title: String? get() = client ?: company

	/* latest start or end of a segment: "the most recent" entry for the big card */
	val lastChange: Long get() = segments.maxOfOrNull { maxOf(it.startUtcMs, it.endUtcMs ?: 0L) } ?: entry.updatedUtcMs
}

class HomeViewModel(
	private val entries: EntryRepository,
	private val master: MasterDataRepository,
	private val settingsRepo: SettingsRepository,
) : EntryCommander(entries) {
	val cards = combine(
		entries.openEntries, entries.openEntrySegments, master.clients, master.companies,
	) { list, segs, clients, companies ->
		val cl = clients.associateBy { it.id }
		val co = companies.associateBy { it.id }
		val bySeg = segs.groupBy { it.entryId }
		list.map { e ->
			EntryCard(e, e.clientId?.let { cl[it]?.name }, e.companyId?.let { co[it]?.name }, bySeg[e.id].orEmpty(), companyColor(e.companyId, e.companyId?.let { co[it]?.color }))
		}.sortedByDescending { it.lastChange }
	}.state(emptyList())

	val settings = settingsRepo.settings.state(null)

	/*
	 * The "Chiuse" tab (issue #59): the entries that stopped being worked on
	 * (closed or to complete) with their last segment ending in the last 31
	 * days — the largest window offered — newest end first. The window and
	 * the company/tag filters are applied on top of it.
	 */
	private val closedFrom = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
	val closed = combine(
		entries.closedEntriesSince(closedFrom, CLOSED_LIMIT),
		entries.closedSegmentsSince(closedFrom, CLOSED_LIMIT),
		entries.closedTagsSince(closedFrom, CLOSED_LIMIT),
		master.clients, master.companies,
	) { list, segs, tags, clients, companies ->
		val cl = clients.associateBy { it.id }
		val co = companies.associateBy { it.id }
		val bySeg = segs.groupBy { it.entryId }
		val byTag = tags.groupBy { it.entryId }
		list.map { e ->
			EntryCard(
				e, e.clientId?.let { cl[it]?.name }, e.companyId?.let { co[it]?.name }, bySeg[e.id].orEmpty(),
				companyColor(e.companyId, e.companyId?.let { co[it]?.color }),
				tags = byTag[e.id].orEmpty().map { it.tag }.toSet(),
				endUtcMs = bySeg[e.id].orEmpty().maxOfOrNull { it.endUtcMs ?: 0L },
			)
		}
	}.state(emptyList())

	/* every tag in use, for the "Chiuse" filter */
	val tags = entries.allTags.state(emptyList())

	/* the last tab chosen and its window are remembered (issue #59, answer 5) */
	fun setTab(tab: HomeTab) = run { settingsRepo.update { it.copy(homeTab = tab.name) } }
	fun setClosedWindow(hours: Int) = run { settingsRepo.update { it.copy(homeClosedWindow = hours) } }

	/* the one-off gesture hint was read (issue #77) */
	fun dismissGestureHint() = run { settingsRepo.update { it.copy(gestureHintSeen = true) } }

	/* the entry shown in the big card; null = the most recent one */
	private val _selected = MutableStateFlow<Long?>(null)
	val selected = _selected.asStateFlow()

	fun select(id: Long) {
		_selected.value = id
	}

	/*
	 * Travel from the empty card (owner, 2026-10-18: it starts at once): no
	 * destination yet, the company proposed as for a new entry; arrival or
	 * the entry screen sets the client later.
	 */
	fun startTravel() = run {
		val p = entries.startProposal(master.knownSites())
		entries.start(StartRequest(SegmentKind.TRAVEL, clientId = null, companyId = p.companyId, locationKind = p.locationKind), emptyMap())
	}

	companion object {
		private const val CLOSED_LIMIT = 200
	}
}

/*
 * Home (M10, owner decisions 2026-10-18): the big card always shows (an
 * empty-state message when nothing runs) with the most recent open entry,
 * or the one tapped; every other open entry gets a small card below; the
 * to-dos follow under a separator. Cards swipe as set in Gestures.
 */
@Composable
fun HomeScreen(
	onStart: () -> Unit,
	onStartTravel: () -> Unit,
	onOpenEntry: (Long) -> Unit,
	onStartTodo: (Long) -> Unit,
	onOpenGestures: () -> Unit = {},
) {
	val vm = appViewModel { HomeViewModel(it.entries, it.masterData, it.settings) }
	val allCards by vm.cards.collectAsState()
	val selected by vm.selected.collectAsState()
	val settings by vm.settings.collectAsState()
	val gestures = settings?.gestures ?: GestureSettings()
	val fallback = stringResource(R.string.entry_no_client)
	val run = rememberEntryCommands(vm, settings?.editableTimes == true) { id -> allCards.firstOrNull { it.entry.id == id }?.title ?: fallback }

	/* elapsed times refresh every 30 s; the DB keeps exact timestamps */
	val now by produceState(System.currentTimeMillis()) {
		while (true) {
			delay(30_000)
			value = System.currentTimeMillis()
		}
	}
	/* the company filter on the entries too, when chosen (issue #44) */
	val only = settings?.todoCompanyFilter?.takeIf { settings?.homeFilterEntries == true }
	val cards = if (only == null) allCards else allCards.filter { it.entry.companyId == only }
	val big = cards.firstOrNull { it.entry.id == selected } ?: cards.firstOrNull()
	val small = cards.filter { it !== big }

	/*
	 * The bottom tabs (issue #59): planned to-dos ahead, entries and done
	 * to-dos behind; the filters act on the list shown. The to-do state
	 * comes from the shared To-dos view model, as before.
	 */
	val todosVm = todosViewModel()
	val openTodos by todosVm.open.collectAsState()
	val ignoredTodos by todosVm.ignored.collectAsState()
	val doneTodos by todosVm.done.collectAsState()
	val todoClients by todosVm.clients.collectAsState()
	val todoCompanies by todosVm.companies.collectAsState()
	val closedCards by vm.closed.collectAsState()
	val allTags by vm.tags.collectAsState()
	var closedTag by rememberSaveable { mutableStateOf<String?>(null) }
	var todoDialog by remember { mutableStateOf<TodoEntity?>(null) }
	var todoEditing by remember { mutableStateOf<TodoEntity?>(null) }

	/* policy (issue #48): entries only from to-dos, no travel, no to-dos */
	val policy = LocalPolicy.current
	val canStart = policy.entrySource == it.infraconsulting.activitytrack.domain.policy.EntrySource.FREE
	val canTravel = policy.enabled(Feature.TRAVEL)
	val showPlanned = policy.enabled(Feature.TODOS)
	val tab = if (showPlanned) HomeTab.of(settings?.homeTab) else HomeTab.CLOSED
	val zone = ZoneId.systemDefault()
	val todoCompany = settings?.todoCompanyFilter
	val plannedWindow = settings?.homeTodoWindowHours ?: 24
	val closedWindow = settings?.homeClosedWindow ?: 0
	val split = plannedSplit(openTodos, todoCompany, plannedWindow, Instant.now(), zone)
	val boundary = closedBoundary(closedWindow, settings?.carryOverMin ?: 6 * 60, Instant.now(), zone)
	val closedRows = buildList {
		for (c in closedCards)
			if ((todoCompany == null || c.entry.companyId == todoCompany) &&
				(closedTag == null || closedTag in c.tags) &&
				(c.endUtcMs ?: c.entry.updatedUtcMs) > boundary.toEpochMilli()
			)
				add(ClosedRow.Entry(c))
		for (t in doneTodos)
			if ((todoCompany == null || t.companyId == todoCompany) && Instant.ofEpochMilli(t.updatedUtcMs).isAfter(boundary))
				add(ClosedRow.Todo(t, todoClients.firstOrNull { it.id == t.clientId }?.name))
	}.sortedByDescending { it.sortMs }
	/* the ones ignored today stay at the bottom of the planned list (owner, 2026-10-18) */
	val ignoredToday = if (settings?.todoShowIgnored == false) emptyList() else ignoredTodos.filter { t ->
		(todoCompany == null || t.companyId == todoCompany) &&
			t.ignoredUtcMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } == LocalDate.now()
	}
	val plannedCount = split.dated.size + split.noDate.size + ignoredToday.size
	/* with a filter the header stays, so the filter can be removed */
	val showTabs = plannedCount + closedRows.size > 0 || todoCompany != null || closedTag != null
	val plannedLabels = listOf(
		24 to stringResource(R.string.todo_window_24h),
		72 to stringResource(R.string.todo_window_3d),
		168 to stringResource(R.string.todo_window_7d),
		720 to stringResource(R.string.todo_window_30d),
		0 to stringResource(R.string.todo_window_all),
	)
	val closedLabels = listOf(
		0 to stringResource(R.string.home_window_today),
		24 to stringResource(R.string.todo_window_24h),
		168 to stringResource(R.string.todo_window_7d),
		720 to stringResource(R.string.todo_window_30d),
	)
	val plannedBands = bands(split.dated + split.noDate + ignoredToday, todoCompanies, todoClients)
	ScreenScaffold(
		title = stringResource(R.string.app_name),
		onBack = null,
		/* with entries open the empty card is gone: start and travel move to the bar (owner, 2026-10-18) */
		actions = {
			if (cards.isNotEmpty())
				for ((cmd, label, go) in listOfNotNull(
					Triple(EntryCommand.WORK, R.string.start_title, onStart).takeIf { canStart },
					Triple(EntryCommand.TRAVEL, R.string.start_travel, onStartTravel).takeIf { canStart && canTravel },
				)) {
					val st = commandStyle(cmd)!!
					IconButton(onClick = go) { Icon(painterResource(st.icon), contentDescription = stringResource(label), tint = st.container) }
				}
		},
	) { padding ->
		Column(modifier = Modifier.fillMaxSize().padding(padding)) {
			/*
			 * How the cards move, once (issue #77); Settings → Gestures shows it
			 * again. Above the list, not in it: the settings load after the first
			 * frame and an item inserted on top would land above the scrolled view.
			 */
			if (settings?.gestureHintSeen == false && policy.enabled(Feature.GESTURES))
				OutlinedCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth()) {
					Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
						Text(stringResource(R.string.gesture_hint_text), style = MaterialTheme.typography.bodyMedium)
						Row(Modifier.align(Alignment.End)) {
							TextButton(onClick = onOpenGestures) { Text(stringResource(R.string.gestures_title)) }
							TextButton(onClick = vm::dismissGestureHint) { Text(stringResource(R.string.gesture_hint_ok)) }
						}
					}
				}
			LazyColumn(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 6.dp else 10.dp),
				contentPadding = PaddingValues(vertical = 8.dp),
			) {
				item(key = "big") {
					if (big == null)
						EmptyEntryCard(gestures.fullSwipe, onStart.takeIf { canStart }, vm::startTravel.takeIf { canStart && canTravel }, Modifier.padding(horizontal = 16.dp))
					else
						SwipeCard(
							toStart = entrySwipeActions(big.entry, gestures.entryToStart) { run(big.entry, it) },
							toEnd = entrySwipeActions(big.entry, gestures.entryToEnd) { run(big.entry, it) },
							fullSwipe = gestures.fullSwipe,
							shape = CardDefaults.shape,
							modifier = Modifier.padding(horizontal = 16.dp),
						) {
							EntryCardView(card = big, now = now, onCommand = { run(big.entry, it) }, onOpen = { onOpenEntry(big.entry.id) })
						}
				}
				items(small, key = { it.entry.id }) { c ->
					SwipeCard(
						toStart = entrySwipeActions(c.entry, gestures.entryToStart) { run(c.entry, it) },
						toEnd = entrySwipeActions(c.entry, gestures.entryToEnd) { run(c.entry, it) },
						fullSwipe = gestures.fullSwipe,
						shape = CardDefaults.shape,
						modifier = Modifier.padding(horizontal = 16.dp),
					) {
						SmallEntryCard(c, now, onSelect = { vm.select(c.entry.id) }, onCommand = { run(c.entry, it) }, onOpen = { onOpenEntry(c.entry.id) })
					}
				}
				if (showTabs) {
					item(key = "tabs") {
						HomeTabsHeader(
							tab = tab,
							showPlanned = showPlanned,
							count = if (tab == HomeTab.PLANNED) plannedCount else closedRows.size,
							windowHours = if (tab == HomeTab.PLANNED) plannedWindow else closedWindow,
							windowLabels = if (tab == HomeTab.PLANNED) plannedLabels else closedLabels,
							onWindow = { if (tab == HomeTab.PLANNED) todosVm.setHomeWindow(it) else vm.setClosedWindow(it) },
							todosVm = todosVm,
							showTagFilter = tab == HomeTab.CLOSED,
							tag = closedTag,
							tags = allTags,
							onTag = { closedTag = it },
							onTab = { vm.setTab(it) },
						)
					}
					when (tab) {
						HomeTab.PLANNED -> {
							items(split.dated, key = { "todo-${it.id}" }) { t ->
								TodoSwipeCard(
									t, todoClients.firstOrNull { it.id == t.clientId }?.name, todosVm, onStartTodo,
									onReplan = { todosVm.clearError(); todoEditing = it }, band = plannedBands[t.id],
								) { todoDialog = t }
							}
							if (split.noDate.isNotEmpty()) {
								item(key = "no-date") {
									Text(
										stringResource(R.string.home_no_date),
										style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
										modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
									)
								}
								items(split.noDate, key = { "todo-nodate-${it.id}" }) { t ->
									TodoSwipeCard(
										t, todoClients.firstOrNull { it.id == t.clientId }?.name, todosVm, onStartTodo,
										onReplan = { todosVm.clearError(); todoEditing = it }, band = plannedBands[t.id],
									) { todoDialog = t }
								}
							}
							items(ignoredToday, key = { "todo-ignored-${it.id}" }) { t ->
								TodoSwipeCard(
									t, todoClients.firstOrNull { it.id == t.clientId }?.name, todosVm, onStartTodo,
									onReplan = { todosVm.clearError(); todoEditing = it }, band = plannedBands[t.id],
								) { todoDialog = t }
							}
							if (plannedCount == 0)
								item(key = "planned-empty") { EmptyText(stringResource(R.string.home_planned_empty)) }
						}
						HomeTab.CLOSED -> {
							items(
								closedRows,
								key = { row ->
									when (row) {
										is ClosedRow.Entry -> "closed-entry-${row.card.entry.id}"
										is ClosedRow.Todo -> "closed-todo-${row.todo.id}"
									}
								},
							) { row ->
								when (row) {
									is ClosedRow.Entry -> Box(Modifier.padding(horizontal = 16.dp).alpha(0.75f)) {
										SmallEntryCard(
											row.card, now,
											onSelect = { onOpenEntry(row.card.entry.id) },
											onCommand = {},
											onOpen = { onOpenEntry(row.card.entry.id) },
											showDate = true,
										)
									}
									is ClosedRow.Todo -> Box(Modifier.padding(horizontal = 16.dp)) {
										TodoCard(row.todo, row.client) { todoDialog = row.todo }
									}
								}
							}
							if (closedRows.isEmpty())
								item(key = "closed-empty") { EmptyText(stringResource(R.string.home_closed_empty)) }
						}
					}
				}
			}
		}
		todoDialog?.let { t ->
			TodoActions(t, todosVm, onStart = { onStartTodo(t.id) }, onEdit = { todosVm.clearError(); todoEditing = t }) { todoDialog = null }
		}
		todoEditing?.let { t -> TodoEditDialog(t, todosVm) { todoEditing = null } }
	}
}

/*
 * The big card when no entry is open (owner, 2026-10-18): the message and
 * the start and travel buttons, which replace the old bottom button; a
 * swipe to the left starts like the button, to the right starts a travel.
 */
@Composable
private fun EmptyEntryCard(fullSwipe: Boolean, onStart: (() -> Unit)?, onTravel: (() -> Unit)?, modifier: Modifier) {
	val compact = LocalCompact.current
	val start = commandStyle(EntryCommand.WORK)!!
	val travel = commandStyle(EntryCommand.TRAVEL)!!
	val startLabel = stringResource(R.string.swipe_start)
	val travelLabel = stringResource(R.string.cmd_travel)
	SwipeCard(
		toStart = listOfNotNull(onStart?.let { SwipeAction(startLabel, start.icon, start.container, start.content, it) }),
		toEnd = listOfNotNull(onTravel?.let { SwipeAction(travelLabel, travel.icon, travel.container, travel.content, it) }),
		fullSwipe = fullSwipe,
		shape = CardDefaults.shape,
		modifier = modifier,
	) {
		Card(modifier = Modifier.fillMaxWidth()) {
			Column(
				modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 120.dp else 180.dp).padding(if (compact) 12.dp else 18.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp, Alignment.CenterVertically),
			) {
				Text(stringResource(R.string.home_none), style = MaterialTheme.typography.titleLarge)
				Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
					val size = if (compact) 48.dp else 64.dp
					onStart?.let { CommandIconButton(start, startLabel, it, Modifier.size(size)) }
					onTravel?.let { CommandIconButton(travel, stringResource(R.string.start_travel), it, Modifier.size(size)) }
				}
			}
		}
	}
}

/*
 * A background entry (paused, suspended, to complete, or another running
 * one), about a third of the big card: a tap brings it to the big card; the
 * button resumes, pauses or opens it for completion.
 */
@Composable
private fun SmallEntryCard(
	card: EntryCard,
	now: Long,
	onSelect: () -> Unit,
	onCommand: (EntryCommand) -> Unit,
	onOpen: () -> Unit,
	/* the "Chiuse" rows (issue #87): the day too, "oggi" or "lun 26/10" */
	showDate: Boolean = false,
) {
	val e = card.entry
	val zone = ZoneId.of(e.zoneId)
	val compact = LocalCompact.current
	val button = Modifier.size(if (compact) 36.dp else 44.dp)
	Card(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
		Banded(card.companyColor) {
			Row(
				modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 72.dp)
					.padding(start = if (compact) 12.dp else 16.dp, end = 10.dp, top = if (compact) 4.dp else 8.dp, bottom = if (compact) 4.dp else 8.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(
						listOfNotNull(card.title ?: stringResource(R.string.entry_no_client), e.activity).joinToString(" · "),
						style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
					)
					val state = statusText(e.status, e.mode)
					/* "11:00–11:45 (0:45)": from the first start to the last end, work and travel (owner, 2026-10-19) */
					val from = card.segments.minOfOrNull { it.startUtcMs }
					val to = card.segments.takeIf { s -> s.all { it.endUtcMs != null } }?.maxOfOrNull { it.endUtcMs!! }
					val day = from?.takeIf { showDate }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
					val dayText = day?.let {
						if (it == LocalDate.now(zone)) stringResource(R.string.todo_today)
						else it.format(DateTimeFormatter.ofPattern("EEE d/MM", LocalConfiguration.current.locales[0]))
					}
					val span = from?.let { listOfNotNull(dayText, clock(it, zone) + "–" + (to?.let { t -> clock(t, zone) } ?: "…")).joinToString(" ") }
					val length = hm(total(card.segments, SegmentKind.WORK, now) + total(card.segments, SegmentKind.TRAVEL, now))
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						val style = stateStyle(e.status, e.mode)
						if (style != null) StatePill(style, state) else Text(state, style = MaterialTheme.typography.bodySmall)
						Text(
							listOfNotNull(span, "($length)").joinToString(" "),
							style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
				val resting = (e.status == EntryStatus.ACTIVE && e.mode == SegmentKind.PAUSE) || e.status == EntryStatus.SUSPENDED
				val running = e.status == EntryStatus.ACTIVE && !resting
				when {
					resting -> commandStyle(EntryCommand.WORK)?.let { CommandIconButton(it, stringResource(R.string.cmd_resume), { onCommand(EntryCommand.WORK) }, button) }
					running -> commandStyle(EntryCommand.PAUSE)?.let { CommandIconButton(it, stringResource(R.string.cmd_pause), { onCommand(EntryCommand.PAUSE) }, button) }
					e.status == EntryStatus.TO_COMPLETE -> TextButton(onClick = onOpen) { Text(stringResource(R.string.cmd_complete)) }
				}
			}
		}
	}
}

@Composable
private fun EntryCardView(
	card: EntryCard,
	now: Long,
	onCommand: (EntryCommand) -> Unit,
	onOpen: () -> Unit,
) {
	val e = card.entry
	val zone = ZoneId.of(e.zoneId)
	val compact = LocalCompact.current
	Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
		Banded(card.companyColor) {
			Column(modifier = Modifier.padding(if (compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
				val last = card.segments.lastOrNull()
				val since = when {
					e.status == EntryStatus.ACTIVE -> last?.startUtcMs
					else -> card.segments.mapNotNull { it.endUtcMs }.maxOrNull()
				}
				Row(verticalAlignment = Alignment.Top) {
					Column(Modifier.weight(1f)) {
						if (card.client != null && card.company != null)
							Text(card.company, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
						Text(card.title ?: stringResource(R.string.entry_no_client), style = MaterialTheme.typography.titleLarge)
						e.activity?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
					}
					/* the running segment's time, the number one looks for */
					if (e.status == EntryStatus.ACTIVE && last?.endUtcMs == null && last != null)
						Text(hm(now - last.startUtcMs), style = MaterialTheme.typography.headlineMedium)
				}

				val state = statusText(e.status, e.mode)
				val text = if (since != null) stringResource(R.string.since_fmt, state, clock(since, zone)) else state
				val style = stateStyle(e.status, e.mode)
				if (style != null)
					StatePill(style, text)
				else
					Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
				/* the end chosen when starting (M22), asked about at that time */
				e.plannedEndUtcMs?.let { Text(stringResource(R.string.planned_end_fmt, clock(it, zone)), style = MaterialTheme.typography.bodySmall) }
				Text(
					stringResource(
						R.string.totals_fmt,
						hm(total(card.segments, SegmentKind.WORK, now)),
						hm(total(card.segments, SegmentKind.TRAVEL, now)),
					),
					style = MaterialTheme.typography.bodySmall,
				)

				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
					itemVerticalAlignment = Alignment.CenterVertically,
				) {
					for ((cmd, label) in commands(e.status, e.mode)) {
						val style = commandStyle(cmd)
						if (style != null)
							CommandIconButton(style, stringResource(label), { onCommand(cmd) }, if (compact) Modifier.size(44.dp) else Modifier)
						else
							OutlinedButton(onClick = { onCommand(cmd) }) { Text(stringResource(label)) }
					}
					if (e.status == EntryStatus.TO_COMPLETE)
						Button(onClick = onOpen) { Text(stringResource(R.string.cmd_complete)) }
				}
			}
		}
	}
}

/* Commands offered per state; the state machine stays the authority. */
private fun commands(status: EntryStatus, mode: SegmentKind?): List<Pair<EntryCommand, Int>> = when (status) {
	EntryStatus.ACTIVE -> when (mode) {
		SegmentKind.WORK -> listOf(
			EntryCommand.PAUSE to R.string.cmd_pause,
			EntryCommand.TRAVEL to R.string.cmd_travel,
			EntryCommand.SUSPEND to R.string.cmd_suspend,
			EntryCommand.STOP to R.string.cmd_stop,
		)
		SegmentKind.TRAVEL -> listOf(
			EntryCommand.WORK to R.string.cmd_work,
			EntryCommand.PAUSE to R.string.cmd_pause,
			EntryCommand.SUSPEND to R.string.cmd_suspend,
			EntryCommand.STOP to R.string.cmd_stop,
		)
		else -> listOf(
			EntryCommand.WORK to R.string.cmd_resume,
			EntryCommand.STOP to R.string.cmd_stop,
		)
	}
	EntryStatus.SUSPENDED -> listOf(
		EntryCommand.WORK to R.string.cmd_resume,
		EntryCommand.TRAVEL to R.string.cmd_travel,
		EntryCommand.STOP to R.string.cmd_stop,
	)
	EntryStatus.TO_COMPLETE -> listOf(EntryCommand.REOPEN to R.string.cmd_reopen)
	EntryStatus.CLOSED -> emptyList()
}

private fun total(segments: List<SegmentEntity>, kind: SegmentKind, now: Long): Long =
	segments.filter { it.kind == kind }.sumOf { maxOf(0L, (it.endUtcMs ?: now) - it.startUtcMs) }

/* Duration as H:MM. */
private fun hm(ms: Long): String {
	val min = ms / 60_000
	return "%d:%02d".format(min / 60, min % 60)
}

private fun clock(ms: Long, zone: ZoneId): String {
	val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
	return formatMin(t.hour * 60 + t.minute)
}

@Composable
fun statusText(status: EntryStatus, mode: SegmentKind?): String = when (status) {
	EntryStatus.ACTIVE -> stringResource(
		when (mode) {
			SegmentKind.TRAVEL -> R.string.mode_travel
			SegmentKind.PAUSE -> R.string.mode_pause
			else -> R.string.mode_work
		},
	)
	EntryStatus.SUSPENDED -> stringResource(R.string.status_suspended)
	EntryStatus.TO_COMPLETE -> stringResource(R.string.status_to_complete)
	EntryStatus.CLOSED -> stringResource(R.string.status_closed)
}
