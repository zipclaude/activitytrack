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
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
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
open class EntryCommander(private val entries: EntryRepository) : ActionViewModel() {
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

	fun invalidTime() = fail(ValidationException.Reason.INVALID_TIME)

	/* stopping: [then] gets whether the entry is under a minute and discardable */
	fun checkShort(id: Long, then: (Boolean) -> Unit) {
		var short = false
		run({ then(short) }) { short = entries.isDiscardable(id) }
	}

	fun discard(id: Long) = run { entries.discard(id) }

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
	var stopping by remember { mutableStateOf<TimeEntryEntity?>(null) }
	var short by remember { mutableStateOf<TimeEntryEntity?>(null) }
	val resume by vm.resume.collectAsState()
	fun stop(e: TimeEntryEntity) {
		if (editableTimes || e.mode != SegmentKind.TRAVEL)
			stopping = e
		else
			vm.command(e.id, EntryCommand.STOP)
	}

	/* under a minute: discard it, or stop it as usual (backlog 10, 2026-10-18) */
	short?.let { e ->
		AlertDialog(
			onDismissRequest = { short = null },
			title = { Text(stringResource(R.string.short_title, titleOf(e.id))) },
			text = { Text(stringResource(R.string.short_text)) },
			confirmButton = { TextButton(onClick = { short = null; vm.discard(e.id) }) { Text(stringResource(R.string.short_discard)) } },
			dismissButton = { TextButton(onClick = { short = null; stop(e) }) { Text(stringResource(R.string.short_keep)) } },
		)
	}

	stopping?.takeIf { editableTimes }?.let { e -> StopAtDialog(e, vm, titleOf(e.id)) { stopping = null } }
	stopping?.takeIf { !editableTimes }?.let { e ->
		AlertDialog(
			onDismissRequest = { stopping = null },
			title = { Text(stringResource(R.string.stop_title, titleOf(e.id))) },
			confirmButton = {
				TextButton(onClick = { stopping = null; vm.command(e.id, EntryCommand.STOP) }) { Text(stringResource(R.string.stop_now)) }
			},
			dismissButton = {
				TextButton(onClick = { stopping = null; vm.command(e.id, EntryCommand.TRAVEL) }) { Text(stringResource(R.string.stop_return)) }
			},
		)
	}
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
			vm.checkShort(e.id) { isShort -> if (isShort) short = e else stop(e) }
		else
			vm.command(e.id, cmd)
	}
}

/*
 * Stop with an end time (M17): empty = now; a time without date is the
 * latest one (today, or yesterday if it has not come yet). From work the
 * return travel starts at the same time.
 */
@Composable
private fun StopAtDialog(e: TimeEntryEntity, vm: EntryCommander, title: String, onClose: () -> Unit) {
	val error by vm.error.collectAsState()
	var end by remember { mutableStateOf("") }
	fun go(cmd: EntryCommand) {
		val min = parseHhMm(end)
		when {
			end.isBlank() -> { vm.command(e.id, cmd); onClose() }
			min == null || min >= 1440 -> vm.invalidTime()
			else -> vm.commandAt(e.id, cmd, latestAt(min, Instant.now(), ZoneId.systemDefault()).toEpochMilli(), onClose)
		}
	}
	AlertDialog(
		onDismissRequest = { vm.clearError(); onClose() },
		title = { Text(stringResource(R.string.stop_title, title)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				/* a new time drops the error about the previous one */
				TimeField(stringResource(R.string.field_end), end, { end = it; vm.clearError() }, clearable = true, importance = Importance.OPTIONAL)
				Text(stringResource(R.string.time_now_hint), style = MaterialTheme.typography.bodySmall)
				error?.let { Text(reasonText(it), color = MaterialTheme.colorScheme.error) }
			}
		},
		confirmButton = { TextButton(onClick = { go(EntryCommand.STOP) }) { Text(stringResource(R.string.cmd_stop)) } },
		dismissButton = if (e.mode == SegmentKind.TRAVEL) null else ({
			TextButton(onClick = { go(EntryCommand.TRAVEL) }) { Text(stringResource(R.string.stop_return)) }
		}),
	)
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
