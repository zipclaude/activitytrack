/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.StartRequest
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.policy.EntrySource
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.domain.settings.GestureSettings
import it.infraconsulting.activitytrack.ui.LocalCompact
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.Banded
import it.infraconsulting.activitytrack.ui.common.CommandIconButton
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.StatePill
import it.infraconsulting.activitytrack.ui.common.SwipeAction
import it.infraconsulting.activitytrack.ui.common.SwipeCard
import it.infraconsulting.activitytrack.ui.common.TagDots
import it.infraconsulting.activitytrack.ui.common.Undo
import it.infraconsulting.activitytrack.ui.common.UndoEvent
import it.infraconsulting.activitytrack.ui.common.companyColor
import it.infraconsulting.activitytrack.ui.common.displayName
import it.infraconsulting.activitytrack.ui.common.displayStyle
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.start.AbsenceDialog
import it.infraconsulting.activitytrack.ui.start.TodoDialog
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/* The activities the creation buttons make: [TODO] and [ABSENCE] open their dialogs, [WORK] the start dialog; Viaggio starts at once. */
enum class CreateMode { TODO, WORK, ABSENCE }

class HomeViewModel(
	private val entries: EntryRepository,
	private val master: MasterDataRepository,
	private val settingsRepo: SettingsRepository,
) : QuickActioner(entries) {
	/* the running activities: a segment open, or suspended */
	val cards = combine(
		entries.openEntries, entries.openEntrySegments, entries.openEntryTags, master.clients, master.companies,
	) { list, segs, tags, clients, companies ->
		val cl = clients.associateBy { it.id }
		val co = companies.associateBy { it.id }
		val bySeg = segs.groupBy { it.entryId }
		val byTag = tags.groupBy({ it.entryId }, { it.tag })
		list.map { e ->
			EntryCard(
				e, e.clientId?.let { cl[it]?.name }, e.companyId?.let { co[it]?.name }, bySeg[e.id].orEmpty(),
				companyColor(e.companyId, e.companyId?.let { co[it]?.color }), tags = byTag[e.id].orEmpty().toSet(),
			)
		}.sortedByDescending { it.lastChange }
	}.state(emptyList())

	val settings = settingsRepo.settings.state(null)

	val clients = master.clients.state(emptyList())
	val companies = master.companies.state(emptyList())

	/* the activities of [source] with their segments (the planned ones of the TODO activities too) */
	private fun withSegments(source: Flow<List<TimeEntryEntity>>): Flow<List<Pair<TimeEntryEntity, List<SegmentEntity>>>> =
		source.flatMapLatest { list ->
			if (list.isEmpty()) flowOf(emptyList())
			else combine(list.map { e -> entries.segments(e.id) }) { segs -> list.zip(segs.asList()) }
		}

	private fun cardsOf(rows: List<Pair<TimeEntryEntity, List<SegmentEntity>>>, clients: List<it.infraconsulting.activitytrack.data.db.ClientEntity>, companies: List<it.infraconsulting.activitytrack.data.db.CompanyEntity>): List<EntryCard> {
		val cl = clients.associateBy { it.id }
		val co = companies.associateBy { it.id }
		return rows.map { (e, segs) ->
			EntryCard(
				e, e.clientId?.let { cl[it]?.name }, e.companyId?.let { co[it]?.name }, segs,
				companyColor(e.companyId, e.companyId?.let { co[it]?.color }),
				endUtcMs = segs.filter { !it.planned }.maxOfOrNull { it.endUtcMs ?: 0L }?.takeIf { it > 0 },
			)
		}
	}

	/* the TODO activities (to do, planned, late), not deleted */
	val todoCards = combine(withSegments(entries.todos), master.clients, master.companies, ::cardsOf).state(emptyList())

	/* the absences, not deleted */
	val absenceCards = combine(withSegments(entries.absences), master.clients, master.companies, ::cardsOf).state(emptyList())

	/*
	 * The closed activities: those with their last segment ending in the last
	 * 31 days (the largest window offered), and the tasks ticked off in the
	 * same period (closed without any segment). The window and the company/tag filters are
	 * applied on top of it.
	 */
	private val closedFrom = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
	val closed = combine(
		combine(
			entries.closedEntriesSince(closedFrom, CLOSED_LIMIT), entries.closedSegmentsSince(closedFrom, CLOSED_LIMIT),
			entries.closedTagsSince(closedFrom, CLOSED_LIMIT), ::Triple,
		),
		combine(entries.tickedSince(closedFrom, CLOSED_LIMIT), entries.tickedTagsSince(closedFrom, CLOSED_LIMIT), ::Pair),
		master.clients, master.companies,
	) { (list, segs, tags), (ticked, tickedTags), clients, companies ->
		val cl = clients.associateBy { it.id }
		val co = companies.associateBy { it.id }
		val bySeg = segs.groupBy { it.entryId }
		val byTag = (tags + tickedTags).groupBy { it.entryId }
		(list + ticked).map { e ->
			EntryCard(
				e, e.clientId?.let { cl[it]?.name }, e.companyId?.let { co[it]?.name }, bySeg[e.id].orEmpty(),
				companyColor(e.companyId, e.companyId?.let { co[it]?.color }),
				tags = byTag[e.id].orEmpty().map { it.tag }.toSet(),
				endUtcMs = bySeg[e.id].orEmpty().maxOfOrNull { it.endUtcMs ?: 0L }?.takeIf { it > 0 },
			)
		}
	}.state(emptyList())

	/* no client yet (issue #98): the Home shows how to begin; null while loading */
	val hasClients = master.clients.map { l -> l.any { !it.archived } }.state(null)

	/* every tag in use, for the tag filter */
	val tags = entries.allTags.state(emptyList())

	/* the Home list's state filter, window and company filter (owner, 2026-10-27), remembered */
	fun setHomeShow(show: Set<HomeShow>) = run { settingsRepo.update { it.copy(homeShow = show.map { s -> s.name }.toSet()) } }
	fun setHomeWindow(window: Int) = run { settingsRepo.update { it.copy(homeWindow = window) } }
	fun setCompanyFilter(companyId: Long?) = run { settingsRepo.update { it.copy(todoCompanyFilter = companyId) } }
	fun setHomeFilterEntries(on: Boolean) = run { settingsRepo.update { it.copy(homeFilterEntries = on) } }

	/* the one-off gesture hint was read (issue #77) */
	fun dismissGestureHint() = run { settingsRepo.update { it.copy(gestureHintSeen = true) } }

	/* the activity shown in the big card; null = the most recent one */
	private val _selected = MutableStateFlow<Long?>(null)
	val selected = _selected.asStateFlow()

	fun select(id: Long) {
		_selected.value = id
	}

	/*
	 * Viaggio (owner, 2026-10-18: it starts at once): an anonymous activity
	 * with an open TRAVEL segment, the company proposed as for a new one;
	 * arrival or the detail screen sets the client later.
	 */
	fun startTravel() = run {
		val p = entries.startProposal(master.knownSites())
		entries.start(StartRequest(SegmentKind.TRAVEL, clientId = null, companyId = p.companyId, locationKind = p.locationKind), emptyMap())
	}

	/* a to-do ticked off, "Annulla" puts it back to do (untick) */
	fun tick(e: TimeEntryEntity, title: String) = run({
		Undo.post(UndoEvent(R.string.undo_todo_done, title) { entries.untick(e.id) })
	}) { entries.tick(e.id) }

	fun postpone(e: TimeEntryEntity, minutes: Int, title: String) = run({
		Undo.post(UndoEvent(R.string.undo_todo_postponed, title) { entries.putBack(e) })
	}) { entries.postpone(e.id, minutes) }

	/* "eliminata": to the bin, "Annulla" takes it out */
	fun delete(e: TimeEntryEntity, title: String) = run({
		Undo.post(UndoEvent(R.string.undo_todo_ignored, title) { entries.restore(e.id) })
	}) { entries.delete(e.id) }

	companion object {
		private const val CLOSED_LIMIT = 200
	}
}

/*
 * Home (M10, owner decisions 2026-10-18, rebuilt for 1.0): the big card shows
 * the most recent running activity, or the one tapped, with the buttons the
 * state machine allows; with none running the panel offers the four creation
 * buttons (Da fare, Lavoro, Viaggio, Assenza), which move to the top right
 * while one runs. Every other running activity gets a small card; the list
 * below holds the to-dos, the closed and the absences, each with its
 * displayed state. [openCreate] below is the one place that knows where
 * each creation mode opens: Lavoro the start screen, Da fare and Assenza
 * their dialogs, shown here.
 */
@Composable
fun HomeScreen(
	onStart: () -> Unit,
	onOpenEntry: (Long) -> Unit,
	onStartTodo: (Long) -> Unit,
	onOpenGestures: () -> Unit = {},
	onOpenTodos: (() -> Unit)? = null,
	onOpenImport: (() -> Unit)? = null,
) {
	val vm = appViewModel { HomeViewModel(it.entries, it.masterData, it.settings) }
	val allCards by vm.cards.collectAsState()
	val selected by vm.selected.collectAsState()
	val settings by vm.settings.collectAsState()
	val gestures = settings?.gestures ?: GestureSettings()
	val fallback = stringResource(R.string.entry_no_client)
	val titleOf: (Long) -> String = { id -> allCards.firstOrNull { it.entry.id == id }?.title ?: fallback }
	val run = rememberRunCommands(vm, settings?.editableTimes == true, titleOf)
	val askReopen = rememberReopen(vm, titleOf)
	var creating by remember { mutableStateOf<CreateMode?>(null) }
	val openCreate: (CreateMode) -> Unit = { if (it == CreateMode.WORK) onStart() else creating = it }
	when (creating) {
		CreateMode.TODO -> TodoDialog(null) { creating = null }
		CreateMode.ABSENCE -> AbsenceDialog { creating = null }
		else -> {}
	}

	/* elapsed times refresh every 30 s; the DB keeps exact timestamps */
	val now by produceState(System.currentTimeMillis()) {
		while (true) {
			delay(30_000)
			value = System.currentTimeMillis()
		}
	}
	/* the company filter on the running activities too, when chosen (issue #44) */
	val company = settings?.todoCompanyFilter
	val only = company?.takeIf { settings?.homeFilterEntries == true }
	val cards = if (only == null) allCards else allCards.filter { it.entry.companyId == only }
	val big = cards.firstOrNull { it.entry.id == selected } ?: cards.firstOrNull()
	val small = cards.filter { it !== big }

	val todoCards by vm.todoCards.collectAsState()
	val closedCards by vm.closed.collectAsState()
	val absenceCards by vm.absenceCards.collectAsState()
	val companies by vm.companies.collectAsState()
	val allTags by vm.tags.collectAsState()
	var tag by rememberSaveable { mutableStateOf<String?>(null) }

	/* policy (issue #48): activities only from planned ones, no travel, no to-dos */
	val policy = LocalPolicy.current
	val canStart = policy.entrySource == EntrySource.FREE
	val canTravel = policy.enabled(Feature.TRAVEL)
	val showPlanned = policy.enabled(Feature.TODOS)
	val zone = ZoneId.systemDefault()
	val offered = HomeShow.entries.filter { showPlanned || it != HomeShow.TODO }
	val show = settings?.homeShow.orEmpty().mapNotNull { n -> HomeShow.entries.firstOrNull { it.name == n } }.toSet().intersect(offered.toSet())
	val homeWindow = settings?.homeWindow ?: -1
	val sections = homeSections(
		todoCards, closedCards, absenceCards, show, company, tag, homeWindow, settings?.carryOverMin ?: 6 * 60,
		Instant.ofEpochMilli(now), zone,
	)
	val listed = sections.flatMap { it.second }

	/* Viaggio: while working it asks whether the work is done (3); otherwise an anonymous travel starts at once */
	val travel: () -> Unit = {
		val working = big?.entry?.takeIf { it.mode == SegmentKind.WORK }
		if (working != null) run(working, RunAction.Go(SegmentKind.TRAVEL)) else vm.startTravel()
	}
	val buttons = listOfNotNull(
		CreateButton(DisplayState.TODO, R.string.state_todo) { openCreate(CreateMode.TODO) }.takeIf { showPlanned },
		CreateButton(DisplayState.WORK, R.string.cmd_work) { openCreate(CreateMode.WORK) }.takeIf { canStart },
		CreateButton(DisplayState.TRAVEL, R.string.cmd_travel, travel).takeIf { canStart && canTravel },
		CreateButton(DisplayState.ABSENCE, R.string.state_absence) { openCreate(CreateMode.ABSENCE) },
	)
	val snooze = settings?.planSnoozeMin ?: 30
	val rows = RowActions(
		open = onOpenEntry,
		startTodo = onStartTodo,
		tick = { e -> vm.tick(e, e.activity.orEmpty().ifBlank { fallback }) },
		postpone = { e -> vm.postpone(e, snooze, e.activity.orEmpty().ifBlank { fallback }) },
		delete = { e -> vm.delete(e, e.activity.orEmpty().ifBlank { fallback }) },
		reopen = askReopen,
	)

	ScreenScaffold(
		title = stringResource(R.string.app_name),
		onBack = null,
		/* with activities running the panel's buttons move to the bar, on dark grey circles (owner, 2026-11-11) */
		actions = { if (cards.isNotEmpty()) CreateBar(buttons) },
	) { padding ->
		Box(Modifier.fillMaxSize().padding(padding)) {
			Column(Modifier.fillMaxSize()) {
				/*
				 * How the cards move, once (issue #77); Settings → Gestures shows it
				 * again. Above the list, not in it: the settings load after the first
				 * frame and an item inserted on top would land above the scrolled view.
				 */
				val hasClients by vm.hasClients.collectAsState()
				var addClient by remember { mutableStateOf(false) }
				/* the first steps (issue #98): folder and permissions are asked before; then the first client */
				if (hasClients == false && allCards.isEmpty())
					Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth()) {
						Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(stringResource(R.string.first_title), style = MaterialTheme.typography.titleLarge)
							Text(stringResource(R.string.first_text), style = MaterialTheme.typography.bodyMedium)
							Button(onClick = { addClient = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.first_client)) }
							onOpenImport?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.first_import)) } }
						}
					}
				if (addClient)
					it.infraconsulting.activitytrack.ui.common.QuickClientDialog(null, onDone = { _, _ -> addClient = false }, onDismiss = { addClient = false })
				if (hasClients == true && settings?.gestureHintSeen == false && policy.enabled(Feature.GESTURES))
					OutlinedCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth()) {
						Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
							Text(stringResource(R.string.gesture_hint_text), style = MaterialTheme.typography.bodyMedium)
							Row(Modifier.align(Alignment.End)) {
								TextButton(onClick = onOpenGestures) { Text(stringResource(R.string.gestures_title)) }
								TextButton(onClick = vm::dismissGestureHint) { Text(stringResource(R.string.gesture_hint_ok)) }
							}
						}
					}
				CompositionLocalProvider(LocalSnooze provides snooze) {
					LazyColumn(
						modifier = Modifier.weight(1f).fillMaxWidth(),
						verticalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 6.dp else 10.dp),
						contentPadding = PaddingValues(vertical = 8.dp),
					) {
						item(key = "big") {
							if (big == null)
								CreatePanel(buttons, gestures.fullSwipe, Modifier.padding(horizontal = 16.dp))
							else
								SwipeCard(
									toStart = runSwipes(big, gestures.entryToStart, run),
									toEnd = runSwipes(big, gestures.entryToEnd, run),
									fullSwipe = gestures.fullSwipe,
									shape = CardDefaults.shape,
									modifier = Modifier.padding(horizontal = 16.dp),
								) {
									RunningCard(big, now, run) { onOpenEntry(big.entry.id) }
								}
						}
						items(small, key = { "run-${it.entry.id}" }) { c ->
							SwipeCard(
								toStart = runSwipes(c, gestures.entryToStart, run),
								toEnd = runSwipes(c, gestures.entryToEnd, run),
								fullSwipe = gestures.fullSwipe,
								shape = CardDefaults.shape,
								modifier = Modifier.padding(horizontal = 16.dp),
							) {
								SmallRunningCard(c, now, onSelect = { vm.select(c.entry.id) }, onAction = { run(c.entry, it) })
							}
						}
						item(key = "list-header") {
							HomeListHeader(
								show = show, offered = offered, onShow = vm::setHomeShow,
								window = homeWindow, onWindow = vm::setHomeWindow,
								companies = companies, company = company, onCompany = vm::setCompanyFilter,
								filterEntries = settings?.homeFilterEntries == true, onFilterEntries = vm::setHomeFilterEntries,
								tag = tag, tags = allTags, onTag = { tag = it },
							)
						}
						for ((section, items) in sections) {
							item(key = "sec-${section.name}") {
								Text(
									sectionLabel(section).uppercase(),
									style = MaterialTheme.typography.labelMedium,
									color = if (section == HomeSection.LATE || section == HomeSection.INCOMPLETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
									modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
								)
							}
							items(items, key = { it.key }) { item ->
								HomeListRow(item, gestures, policy.enabled(Feature.IGNORE_TODOS), rows)
							}
						}
						if (listed.isEmpty())
							item(key = "list-empty") { EmptyText(stringResource(R.string.home_list_empty)) }
						/* the whole to-do list is a tab of its own (issue #96) */
						if (onOpenTodos != null && HomeShow.TODO in show)
							item(key = "list-all") {
								Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
									TextButton(onClick = onOpenTodos) { Text(stringResource(R.string.home_all_todos)) }
								}
							}
					}
				}
			}
		}
	}
}

/* One of the four creation buttons: the state its icon and colour come from, its name, what it does. */
private class CreateButton(val state: DisplayState, @StringRes val label: Int, val onClick: () -> Unit)

/* the dark grey disc under the bar's buttons: visible on a black background, in either theme */
private val BAR_DISC = Color(0xFF424242)

/* The four buttons at the top right, each on a dark grey circle (owner, 2026-11-11). */
@Composable
private fun CreateBar(buttons: List<CreateButton>) {
	for (b in buttons) {
		val st = displayStyle(b.state)
		val label = stringResource(b.label)
		IconButton(onClick = b.onClick, modifier = Modifier.padding(horizontal = 2.dp).size(40.dp).background(BAR_DISC, CircleShape)) {
			/* the state's colour, lightened: the dark green and blue would sink into the disc */
			Icon(painterResource(st.icon), contentDescription = label, tint = lerp(st.container, Color.White, 0.3f), modifier = Modifier.size(24.dp))
		}
	}
}

/*
 * The big card when nothing runs: the message and the four creation buttons
 * (Da fare, Lavoro, Viaggio, Assenza) in the size of the old Lavoro and
 * Viaggio ones; a swipe to the left starts like Lavoro, to the right like Viaggio.
 */
@Composable
private fun CreatePanel(buttons: List<CreateButton>, fullSwipe: Boolean, modifier: Modifier) {
	val compact = LocalCompact.current
	val toStart = createSwipe(buttons, DisplayState.WORK)
	val toEnd = createSwipe(buttons, DisplayState.TRAVEL)
	SwipeCard(
		toStart = toStart,
		toEnd = toEnd,
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
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp, Alignment.CenterHorizontally),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					val size = if (compact) 48.dp else 60.dp
					for (b in buttons) {
						val label = stringResource(b.label)
						LabeledCommand(label) { CommandIconButton(displayStyle(b.state), label, b.onClick, Modifier.size(size)) }
					}
				}
			}
		}
	}
}

@Composable
private fun createSwipe(buttons: List<CreateButton>, state: DisplayState): List<SwipeAction> =
	buttons.firstOrNull { it.state == state }?.let { b ->
		val st = displayStyle(state)
		listOf(SwipeAction(stringResource(b.label), st.icon, st.container, st.content, b.onClick))
	}.orEmpty()

/* the swipes of a running card: pause or resume, stop */
@Composable
private fun runSwipes(card: EntryCard, swipe: it.infraconsulting.activitytrack.domain.settings.EntrySwipe, run: (TimeEntryEntity, RunAction) -> Unit): List<SwipeAction> =
	runSwipeActions(card.entry, resumeKindOf(card.entry, card.segments), swipe) { run(card.entry, it) }

/*
 * A background running activity (paused, suspended, or another running one),
 * about a third of the big card: a tap brings it to the big card; the button
 * resumes or pauses it.
 */
@Composable
private fun SmallRunningCard(card: EntryCard, now: Long, onSelect: () -> Unit, onAction: (RunAction) -> Unit) {
	val e = card.entry
	val zone = ZoneId.of(e.zoneId)
	val compact = LocalCompact.current
	val button = Modifier.size(if (compact) 36.dp else 44.dp)
	val shown = card.displayed(Instant.ofEpochMilli(now))
	val style = displayStyle(shown.state)
	Card(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
		Banded(card.companyColor) {
			Box {
				/* the tags' colours in the top right corner (issue #133) */
				TagDots(card.tags, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 12.dp))
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
						/* "11:00–11:45 (0:45)": from the first start to the last end, work and travel (owner, 2026-10-19) */
						val real = card.segments.filter { !it.planned }
						val from = real.minOfOrNull { it.startUtcMs }
						val to = real.takeIf { s -> s.all { it.endUtcMs != null } }?.maxOfOrNull { it.endUtcMs!! }
						val span = from?.let { clock(it, zone) + "–" + (to?.let { t -> clock(t, zone) } ?: "…") }
						val length = hm(total(card.segments, SegmentKind.WORK, now) + total(card.segments, SegmentKind.TRAVEL, now))
						Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							StatePill(style, stateText(shown.state, shown.since, zone))
							Text(
								listOfNotNull(span, "($length)").joinToString(" "),
								style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
					/* the first of pause and resume: the step this card is waiting for */
					runActions(e, card.segments).firstOrNull { it is RunAction.Pause || (it is RunAction.Go && it.resume) }?.let { a ->
						CommandIconButton(a.style(), stringResource(a.label()), { onAction(a) }, button)
					}
				}
			}
		}
	}
}

/* The running activity in the big card: who, since when, the running time, and the buttons the state allows. */
@Composable
private fun RunningCard(card: EntryCard, now: Long, run: (TimeEntryEntity, RunAction) -> Unit, onOpen: () -> Unit) {
	val e = card.entry
	val zone = ZoneId.of(e.zoneId)
	val compact = LocalCompact.current
	Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
		Banded(card.companyColor) {
			Column(modifier = Modifier.padding(if (compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
				val last = card.segments.lastOrNull { !it.planned }
				val shown = card.displayed(Instant.ofEpochMilli(now))
				Row(verticalAlignment = Alignment.Top) {
					Column(Modifier.weight(1f)) {
						if (card.client != null && card.company != null)
							Text(card.company, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
						Text(card.title ?: stringResource(R.string.entry_no_client), style = MaterialTheme.typography.titleLarge)
						e.activity?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
					}
					Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
						/* the tags' colours, top right (issue #133) */
						TagDots(card.tags)
						/* the running segment's time, the number one looks for */
						if (e.mode != null && last?.endUtcMs == null && last != null)
							Text(hm(now - last.startUtcMs), style = MaterialTheme.typography.headlineMedium)
					}
				}
				StatePill(displayStyle(shown.state), stateText(shown.state, shown.since, zone))
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
					for (a in runActions(e, card.segments)) {
						val label = stringResource(a.label())
						/* the label under each button */
						LabeledCommand(label) {
							CommandIconButton(a.style(), label, { run(e, a) }, if (compact) Modifier.size(44.dp) else Modifier)
						}
					}
				}
			}
		}
	}
}

/* "In lavoro dalle 13:36": the state's name and since when */
@Composable
private fun stateText(state: DisplayState, since: Instant?, zone: ZoneId): String {
	val name = stringResource(displayName(state))
	return if (since != null) stringResource(R.string.since_fmt, name, clock(since.toEpochMilli(), zone)) else name
}

/* A command button with its name under it (issue #91). */
@Composable
private fun LabeledCommand(label: String, button: @Composable () -> Unit) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
		button()
		Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
	}
}

private fun total(segments: List<SegmentEntity>, kind: SegmentKind, now: Long): Long =
	segments.filter { !it.planned && it.kind == kind }.sumOf { maxOf(0L, (it.endUtcMs ?: now) - it.startUtcMs) }

/* Duration as H:MM. */
private fun hm(ms: Long): String {
	/* a start a little ahead of the clock (another device, a moved time) reads 0:00, never "-1:-9" */
	val min = maxOf(0L, ms) / 60_000
	return "%d:%02d".format(min / 60, min % 60)
}

private fun clock(ms: Long, zone: ZoneId): String {
	val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
	return formatMin(t.hour * 60 + t.minute)
}
