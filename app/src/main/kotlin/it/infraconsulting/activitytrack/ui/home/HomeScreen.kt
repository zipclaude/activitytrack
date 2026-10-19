/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import it.infraconsulting.activitytrack.ui.todo.TodoTodaySection
import java.time.Instant
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
) {
	val title: String? get() = client ?: company

	/* latest start or end of a segment: "the most recent" entry for the big card */
	val lastChange: Long get() = segments.maxOfOrNull { maxOf(it.startUtcMs, it.endUtcMs ?: 0L) } ?: entry.updatedUtcMs
}

class HomeViewModel(
	private val entries: EntryRepository,
	private val master: MasterDataRepository,
	settings: SettingsRepository,
) : EntryCommander(entries) {
	val cards = combine(
		entries.openEntries, entries.openEntrySegments, master.clients, master.companies,
	) { list, segs, clients, companies ->
		val cl = clients.associateBy { it.id }
		val co = companies.associateBy { it.id }
		val bySeg = segs.groupBy { it.entryId }
		list.map { e ->
			EntryCard(e, e.clientId?.let { cl[it]?.name }, e.companyId?.let { co[it]?.name }, bySeg[e.id].orEmpty(), e.companyId?.let { co[it]?.color })
		}.sortedByDescending { it.lastChange }
	}.state(emptyList())

	val settings = settings.settings.state(null)

	/* the last completed entries, most recent first (owner, 2026-10-19) */
	val closed = combine(
		entries.recentClosed, entries.recentClosedSegments, master.clients, master.companies,
	) { list, segs, clients, companies ->
		val cl = clients.associateBy { it.id }
		val co = companies.associateBy { it.id }
		val bySeg = segs.groupBy { it.entryId }
		list.map { e ->
			EntryCard(e, e.clientId?.let { cl[it]?.name }, e.companyId?.let { co[it]?.name }, bySeg[e.id].orEmpty(), e.companyId?.let { co[it]?.color })
		}
	}.state(emptyList())

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
) {
	val vm = appViewModel { HomeViewModel(it.entries, it.masterData, it.settings) }
	val cards by vm.cards.collectAsState()
	val selected by vm.selected.collectAsState()
	val settings by vm.settings.collectAsState()
	val gestures = settings?.gestures ?: GestureSettings()
	val fallback = stringResource(R.string.entry_no_client)
	val run = rememberEntryCommands(vm) { id -> cards.firstOrNull { it.entry.id == id }?.title ?: fallback }

	/* elapsed times refresh every 30 s; the DB keeps exact timestamps */
	val now by produceState(System.currentTimeMillis()) {
		while (true) {
			delay(30_000)
			value = System.currentTimeMillis()
		}
	}
	val big = cards.firstOrNull { it.entry.id == selected } ?: cards.firstOrNull()
	val small = cards.filter { it !== big }
	/*
	 * the last completed entries stay in view: 2 (normal) or 3 (compact)
	 * small cards, open background entries take their places first
	 */
	val closed by vm.closed.collectAsState()
	val slots = if (LocalCompact.current) 3 else 2
	val recent = closed.take(maxOf(0, slots - small.size))

	ScreenScaffold(
		title = stringResource(R.string.app_name),
		onBack = null,
		/* with entries open the empty card is gone: start and travel move to the bar (owner, 2026-10-18) */
		actions = {
			if (cards.isNotEmpty())
				for ((cmd, label, go) in listOf(
					Triple(EntryCommand.WORK, R.string.start_title, onStart),
					Triple(EntryCommand.TRAVEL, R.string.start_travel, onStartTravel),
				)) {
					val st = commandStyle(cmd)!!
					IconButton(onClick = go) { Icon(painterResource(st.icon), contentDescription = stringResource(label), tint = st.container) }
				}
		},
	) { padding ->
		Column(modifier = Modifier.fillMaxSize().padding(padding)) {
			LazyColumn(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 6.dp else 10.dp),
				contentPadding = PaddingValues(vertical = 8.dp),
			) {
				item(key = "big") {
					if (big == null)
						EmptyEntryCard(gestures.fullSwipe, onStart, vm::startTravel, Modifier.padding(horizontal = 16.dp))
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
				items(recent, key = { "closed-${it.entry.id}" }) { c ->
					Box(Modifier.padding(horizontal = 16.dp).alpha(0.75f)) {
						SmallEntryCard(c, now, onSelect = { onOpenEntry(c.entry.id) }, onCommand = {}, onOpen = { onOpenEntry(c.entry.id) })
					}
				}
				item(key = "todos") { TodoTodaySection(onStartTodo) }
			}
		}
	}
}

/*
 * The big card when no entry is open (owner, 2026-10-18): the message and
 * the start and travel buttons, which replace the old bottom button; a
 * swipe to the left starts like the button, to the right starts a travel.
 */
@Composable
private fun EmptyEntryCard(fullSwipe: Boolean, onStart: () -> Unit, onTravel: () -> Unit, modifier: Modifier) {
	val compact = LocalCompact.current
	val start = commandStyle(EntryCommand.WORK)!!
	val travel = commandStyle(EntryCommand.TRAVEL)!!
	val startLabel = stringResource(R.string.swipe_start)
	val travelLabel = stringResource(R.string.cmd_travel)
	SwipeCard(
		toStart = listOf(SwipeAction(startLabel, start.icon, start.container, start.content, onStart)),
		toEnd = listOf(SwipeAction(travelLabel, travel.icon, travel.container, travel.content, onTravel)),
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
					CommandIconButton(start, startLabel, onStart, Modifier.size(size))
					CommandIconButton(travel, stringResource(R.string.start_travel), onTravel, Modifier.size(size))
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
private fun SmallEntryCard(card: EntryCard, now: Long, onSelect: () -> Unit, onCommand: (EntryCommand) -> Unit, onOpen: () -> Unit) {
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
					val since = card.segments.mapNotNull { it.endUtcMs ?: it.startUtcMs }.maxOrNull()
					val state = statusText(e.status, e.mode)
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						val style = stateStyle(e.status, e.mode)
						if (style != null) StatePill(style, state) else Text(state, style = MaterialTheme.typography.bodySmall)
						Text(
							listOfNotNull(since?.let { clock(it, zone) }, hm(total(card.segments, SegmentKind.WORK, now))).joinToString(" · "),
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
