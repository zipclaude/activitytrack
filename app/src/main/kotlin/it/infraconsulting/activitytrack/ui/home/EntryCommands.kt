/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import java.time.ZoneId
import java.time.Instant
import it.infraconsulting.activitytrack.ui.common.reasonText
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.domain.time.latestAt
import it.infraconsulting.activitytrack.data.repo.ValidationException
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.settings.EntrySwipe
import it.infraconsulting.activitytrack.domain.settings.Gestures
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.common.SwipeAction
import it.infraconsulting.activitytrack.ui.common.commandStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Entry commands from the app (buttons and swipes, Home and Agenda): after
 * a stop, a suspended entry is proposed for resumption.
 */
open class EntryCommander(
	private val entries: EntryRepository,
	private val todos: it.infraconsulting.activitytrack.data.repo.TodoRepository? = null,
) : ActionViewModel() {
	/* Suspended entry proposed for resumption after a stop. */
	private val _resume = MutableStateFlow<TimeEntryEntity?>(null)
	val resume = _resume.asStateFlow()

	fun command(id: Long, cmd: EntryCommand) = run {
		entries.apply(id, cmd)
		if (cmd == EntryCommand.STOP)
			_resume.value = entries.suspendedToResume(id)
	}

	/* at a time the user chose (M17 editable times); [done] closes the dialog */
	fun commandAt(id: Long, cmd: EntryCommand, atUtcMs: Long, done: () -> Unit) = run(done) {
		entries.applyAt(id, cmd, atUtcMs)
		if (cmd == EntryCommand.STOP)
			_resume.value = entries.suspendedToResume(id)
	}

	/*
	 * "Stop rapido" (issue #79): stops now with no further question, as the
	 * notification does; completing stays possible from "to complete".
	 */
	fun quickStop(id: Long, done: () -> Unit = {}) = run(done) { entries.apply(id, EntryCommand.STOP) }

	fun invalidTime() = fail(ValidationException.Reason.INVALID_TIME)

	/*
	 * The stop sheet's "Chiudi" (issue #93): the description typed there, the
	 * stop (or the return travel) at [atUtcMs] or now, then the to-do marked
	 * done if asked; an entry left to complete only for that question closes.
	 */
	fun close(e: TimeEntryEntity, cmd: EntryCommand, atUtcMs: Long?, activity: String?, todoDone: Boolean?, done: () -> Unit) = run(done) {
		if (!activity.isNullOrBlank() && e.activity.isNullOrBlank())
			entries.setActivity(e.id, activity)
		if (atUtcMs != null) entries.applyAt(e.id, cmd, atUtcMs) else entries.apply(e.id, cmd)
		if (cmd == EntryCommand.STOP) {
			e.todoId?.let { todo ->
				if (todoDone == true)
					todos?.setDone(todo, true)
				entries.closeIfComplete(e.id)
			}
			_resume.value = entries.suspendedToResume(e.id)
		}
	}

	/* the last description on [clientId], offered by the stop sheet */
	suspend fun lastActivity(clientId: Long): String? = entries.lastActivity(clientId)

	/* the open to-do an entry was started from, for the sheet's check box */
	suspend fun openTodo(e: TimeEntryEntity) = e.todoId?.let { todos?.find(it) }?.takeIf { !it.done }

	/* stopping: [then] gets whether the entry is under a minute and discardable */
	fun checkShort(id: Long, then: (Boolean) -> Unit) {
		var short = false
		run({ then(short) }) { short = entries.isDiscardable(id) }
	}

	fun discard(id: Long) = run { entries.discard(id) }

	/*
	 * A stopped entry reopened from a swipe (owner, 2026-10-27): it goes back
	 * to suspended; "Annulla" stops it again, closed if it was.
	 */
	fun reopen(e: TimeEntryEntity, title: String) = run({
		it.infraconsulting.activitytrack.ui.common.Undo.post(
			it.infraconsulting.activitytrack.ui.common.UndoEvent(R.string.undo_entry_reopened, title) {
				entries.apply(e.id, EntryCommand.STOP)
				if (e.status == it.infraconsulting.activitytrack.domain.model.EntryStatus.CLOSED)
					entries.closeIfComplete(e.id)
			},
		)
	}) { entries.apply(e.id, EntryCommand.REOPEN) }

	fun dismissResume() {
		_resume.value = null
	}

	fun resume(id: Long) = run {
		_resume.value = null
		entries.apply(id, EntryCommand.WORK)
	}
}

/*
 * Runs entry commands the way the Stop button does (owner, 2026-10-18: a
 * swipe Stop asks the same): STOP while working asks "stop now / start the
 * return travel", from TRAVEL it stops at once. Shows its dialogs; returns
 * the dispatcher. [titleOf] names an entry in the dialogs. With
 * [editableTimes] (M17) the question always comes, with an end time.
 */
@Composable
fun rememberEntryCommands(vm: EntryCommander, editableTimes: Boolean = false, titleOf: (Long) -> String): (TimeEntryEntity, EntryCommand) -> Unit {
	/* the entry being closed and whether it is under a minute (discardable) */
	var stopping by remember { mutableStateOf<Pair<TimeEntryEntity, Boolean>?>(null) }
	val resume by vm.resume.collectAsState()

	stopping?.let { (e, short) -> StopSheet(e, short, editableTimes, vm, titleOf(e.id)) { stopping = null } }
	resume?.let { e ->
		AlertDialog(
			onDismissRequest = vm::dismissResume,
			title = { Text(stringResource(R.string.resume_title, titleOf(e.id))) },
			confirmButton = { TextButton(onClick = { vm.resume(e.id) }) { Text(stringResource(R.string.action_yes)) } },
			dismissButton = { TextButton(onClick = vm::dismissResume) { Text(stringResource(R.string.action_no)) } },
		)
	}
	return { e, cmd ->
		if (cmd == EntryCommand.STOP)
			vm.checkShort(e.id) { isShort ->
				/* a travel with nothing to ask stops at once, as before */
				if (!isShort && !editableTimes && e.mode == SegmentKind.TRAVEL && !e.activity.isNullOrBlank() && e.todoId == null)
					vm.command(e.id, EntryCommand.STOP)
				else
					stopping = e to isShort
			}
		else
			vm.command(e.id, cmd)
	}
}

/*
 * One sheet to close an entry (issue #93) instead of questions in a row:
 * stop or start the return travel, the end time (M17; empty = now, a time
 * without date is the latest one), the description when missing (with the
 * last one on the client), whether the to-do it came from is done, and,
 * under a minute, "Scarta". "Stop rapido" stops now and asks nothing.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StopSheet(e: TimeEntryEntity, short: Boolean, editableTimes: Boolean, vm: EntryCommander, title: String, onClose: () -> Unit) {
	val error by vm.error.collectAsState()
	var end by remember { mutableStateOf("") }
	var travelBack by remember { mutableStateOf(false) }
	var activity by remember { mutableStateOf("") }
	var todoDone by remember { mutableStateOf(true) }
	val needsActivity = e.activity.isNullOrBlank()
	val last by androidx.compose.runtime.produceState<String?>(null, e.clientId) { value = e.clientId?.let { vm.lastActivity(it) } }
	val todo by androidx.compose.runtime.produceState<it.infraconsulting.activitytrack.data.db.TodoEntity?>(null, e.id) { value = vm.openTodo(e) }
	fun dismiss() {
		vm.clearError()
		onClose()
	}
	fun go() {
		val cmd = if (travelBack) EntryCommand.TRAVEL else EntryCommand.STOP
		val min = parseHhMm(end)
		val at = when {
			end.isBlank() -> null
			min == null || min >= 1440 -> return vm.invalidTime()
			else -> latestAt(min, Instant.now(), ZoneId.systemDefault()).toEpochMilli()
		}
		vm.close(e, cmd, at, activity.takeIf { needsActivity }, todo?.let { todoDone }, onClose)
	}
	androidx.compose.material3.ModalBottomSheet(onDismissRequest = ::dismiss) {
		Column(
			Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp).verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(stringResource(R.string.stop_title, title), style = MaterialTheme.typography.titleLarge)
			if (short) {
				/* under a minute: probably started by mistake (backlog 10) */
				androidx.compose.material3.Card(
					colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
				) {
					Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Text(stringResource(R.string.short_title, title), style = MaterialTheme.typography.titleSmall)
						Text(stringResource(R.string.short_text), style = MaterialTheme.typography.bodyMedium)
					}
				}
				OutlinedButton(onClick = { vm.discard(e.id); onClose() }, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.short_discard), color = MaterialTheme.colorScheme.error)
				}
			}
			if (e.mode != SegmentKind.TRAVEL)
				androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
					SegmentedButton(
						selected = !travelBack, onClick = { travelBack = false },
						shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(0, 2),
					) { Text(stringResource(R.string.stop_end_work)) }
					SegmentedButton(
						selected = travelBack, onClick = { travelBack = true },
						shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(1, 2),
					) { Text(stringResource(R.string.stop_return)) }
				}
			if (editableTimes) {
				/* a new time drops the error about the previous one */
				TimeField(stringResource(R.string.field_end), end, { end = it; vm.clearError() }, clearable = true, importance = Importance.OPTIONAL)
				Text(stringResource(R.string.time_now_hint), style = MaterialTheme.typography.bodySmall)
			}
			if (needsActivity && !travelBack) {
				it.infraconsulting.activitytrack.ui.common.DictationInput(stringResource(R.string.field_activity), activity, { activity = it }, Importance.REQUIRED)
				last?.takeIf { activity.isBlank() }?.let { l ->
					androidx.compose.material3.AssistChip(onClick = { activity = l }, label = { Text(stringResource(R.string.empty_activity_copy, l), maxLines = 1) })
				}
				if (activity.isBlank())
					Text(stringResource(R.string.stop_no_activity_hint), style = MaterialTheme.typography.bodySmall)
			}
			if (todo != null && !travelBack)
				Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
					androidx.compose.material3.Checkbox(checked = todoDone, onCheckedChange = { todoDone = it })
					Column(Modifier.weight(1f)) {
						Text(stringResource(R.string.stop_todo_done))
						Text(todo!!.title, style = MaterialTheme.typography.bodySmall)
					}
				}
			error?.let { Text(reasonText(it), color = MaterialTheme.colorScheme.error) }
			val stop = commandStyle(if (travelBack) EntryCommand.TRAVEL else EntryCommand.STOP)!!
			androidx.compose.material3.Button(
				onClick = ::go,
				colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = stop.container, contentColor = stop.content),
				modifier = Modifier.fillMaxWidth().height(56.dp),
			) { Text(stringResource(if (travelBack) R.string.stop_return else R.string.stop_close), style = MaterialTheme.typography.titleMedium) }
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
				TextButton(onClick = ::dismiss) { Text(stringResource(R.string.action_cancel)) }
				/* now, no end time and no further question (issue #79) */
				TextButton(onClick = { vm.clearError(); vm.quickStop(e.id, onClose) }) { Text(stringResource(R.string.stop_quick)) }
			}
		}
	}
}

/* The buttons a swipe on an open entry reveals (none for closed entries). */
@Composable
fun entrySwipeActions(e: TimeEntryEntity, swipe: EntrySwipe, onCommand: (EntryCommand) -> Unit): List<SwipeAction> =
	Gestures.entryCommands(swipe, e.status, e.mode).mapNotNull { cmd ->
		val style = commandStyle(cmd) ?: return@mapNotNull null
		val label = when (cmd) {
			EntryCommand.PAUSE -> R.string.cmd_pause
			EntryCommand.WORK -> R.string.cmd_resume
			EntryCommand.SUSPEND -> R.string.cmd_suspend
			else -> R.string.cmd_stop
		}
		SwipeAction(stringResource(label), style.icon, style.container, style.content) { onCommand(cmd) }
	}
