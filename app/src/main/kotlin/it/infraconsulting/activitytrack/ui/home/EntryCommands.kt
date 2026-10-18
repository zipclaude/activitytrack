/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

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
 * the dispatcher. [titleOf] names an entry in the dialogs.
 */
@Composable
fun rememberEntryCommands(vm: EntryCommander, titleOf: (Long) -> String): (TimeEntryEntity, EntryCommand) -> Unit {
	var stopping by remember { mutableStateOf<TimeEntryEntity?>(null) }
	val resume by vm.resume.collectAsState()

	stopping?.let { e ->
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
		if (cmd == EntryCommand.STOP && e.mode != SegmentKind.TRAVEL)
			stopping = e
		else
			vm.command(e.id, cmd)
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
