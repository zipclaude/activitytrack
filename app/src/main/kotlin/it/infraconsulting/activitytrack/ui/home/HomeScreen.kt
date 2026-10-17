/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
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
import it.infraconsulting.activitytrack.ui.common.commandStyle
import it.infraconsulting.activitytrack.ui.common.formatMin
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
) {
	val title: String? get() = client ?: company
}

class HomeViewModel(
	private val entries: EntryRepository,
	master: MasterDataRepository,
) : ActionViewModel() {
	val cards = combine(
		entries.openEntries, entries.openEntrySegments, master.clients, master.companies,
	) { list, segs, clients, companies ->
		val cl = clients.associateBy { it.id }
		val co = companies.associateBy { it.id }
		val bySeg = segs.groupBy { it.entryId }
		list.map { e ->
			EntryCard(e, e.clientId?.let { cl[it]?.name }, e.companyId?.let { co[it]?.name }, bySeg[e.id].orEmpty())
		}
	}.state(emptyList())

	val clients = master.clients.state(emptyList())
	val roles = master.roles.state(emptyList())
	val presets = master.presets.state(emptyList())

	/* Suspended entry proposed for resumption after a stop. */
	private val _resume = MutableStateFlow<TimeEntryEntity?>(null)
	val resume = _resume.asStateFlow()

	fun command(id: Long, cmd: EntryCommand) = run {
		entries.apply(id, cmd)
		if (cmd == EntryCommand.STOP)
			_resume.value = entries.suspendedToResume(id)
	}

	fun dismissResume() {
		_resume.value = null
	}

	fun resume(id: Long) = run {
		_resume.value = null
		entries.apply(id, EntryCommand.WORK)
	}

}

/* Open entries with their commands; the start flow is a separate screen. */
@Composable
fun HomeScreen(
	onStart: () -> Unit,
	onOpenEntry: (Long) -> Unit,
	onStartTodo: (Long) -> Unit,
) {
	val vm = appViewModel { HomeViewModel(it.entries, it.masterData) }
	val cards by vm.cards.collectAsState()
	val resume by vm.resume.collectAsState()
	var stopping by remember { mutableStateOf<EntryCard?>(null) }

	/* elapsed times refresh every 30 s; the DB keeps exact timestamps */
	val now by produceState(System.currentTimeMillis()) {
		while (true) {
			delay(30_000)
			value = System.currentTimeMillis()
		}
	}

	ScreenScaffold(
		title = stringResource(R.string.app_name),
		onBack = null,
	) { padding ->
		Column(modifier = Modifier.fillMaxSize().padding(padding)) {
			LazyColumn(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				if (cards.isEmpty())
					item { EmptyText(stringResource(R.string.home_no_open_entries)) }
				items(cards, key = { it.entry.id }) { c ->
					EntryCardView(
						card = c,
						now = now,
						onCommand = { cmd ->
							/* STOP while working asks about the return travel; from TRAVEL it stops directly */
							if (cmd == EntryCommand.STOP && c.entry.mode != SegmentKind.TRAVEL)
								stopping = c
							else
								vm.command(c.entry.id, cmd)
						},
						onOpen = { onOpenEntry(c.entry.id) },
					)
				}
				item { TodoTodaySection(onStartTodo) }
			}
			Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
				Text(stringResource(R.string.home_start))
			}
		}
	}

	stopping?.let { c ->
		AlertDialog(
			onDismissRequest = { stopping = null },
			title = { Text(stringResource(R.string.stop_title, c.title ?: "")) },
			confirmButton = {
				TextButton(onClick = { stopping = null; vm.command(c.entry.id, EntryCommand.STOP) }) {
					Text(stringResource(R.string.stop_now))
				}
			},
			dismissButton = {
				TextButton(onClick = { stopping = null; vm.command(c.entry.id, EntryCommand.TRAVEL) }) {
					Text(stringResource(R.string.stop_return))
				}
			},
		)
	}

	resume?.let { e ->
		val name = cards.firstOrNull { it.entry.id == e.id }?.title.orEmpty()
		AlertDialog(
			onDismissRequest = vm::dismissResume,
			title = { Text(stringResource(R.string.resume_title, name)) },
			confirmButton = { TextButton(onClick = { vm.resume(e.id) }) { Text(stringResource(R.string.action_yes)) } },
			dismissButton = { TextButton(onClick = vm::dismissResume) { Text(stringResource(R.string.action_no)) } },
		)
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
	Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
		Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			if (card.client != null && card.company != null)
				Text(card.company, style = MaterialTheme.typography.labelMedium)
			Text(card.title ?: stringResource(R.string.entry_no_client), style = MaterialTheme.typography.titleMedium)
			e.activity?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

			val last = card.segments.lastOrNull()
			val since = when {
				e.status == EntryStatus.ACTIVE -> last?.startUtcMs
				else -> card.segments.mapNotNull { it.endUtcMs }.maxOrNull()
			}
			val state = statusText(e.status, e.mode)
			Text(
				if (since != null) stringResource(R.string.since_fmt, state, clock(since, zone)) else state,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.primary,
			)
			Text(
				stringResource(
					R.string.totals_fmt,
					hm(total(card.segments, SegmentKind.WORK, now)),
					hm(total(card.segments, SegmentKind.TRAVEL, now)),
				),
				style = MaterialTheme.typography.bodySmall,
			)

			FlowRow(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
				itemVerticalAlignment = Alignment.CenterVertically,
			) {
				for ((cmd, label) in commands(e.status, e.mode)) {
					val style = commandStyle(cmd)
					if (style != null)
						CommandIconButton(style, stringResource(label), { onCommand(cmd) })
					else
						OutlinedButton(onClick = { onCommand(cmd) }) { Text(stringResource(label)) }
				}
				if (e.status == EntryStatus.TO_COMPLETE)
					Button(onClick = onOpen) { Text(stringResource(R.string.cmd_complete)) }
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
