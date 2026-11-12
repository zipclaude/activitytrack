/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand
import it.infraconsulting.activitytrack.domain.activity.ActivityState
import it.infraconsulting.activitytrack.domain.activity.ActivityStateMachine
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.activity.FutureClose
import it.infraconsulting.activitytrack.domain.activity.ReturnChoice
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.settings.EntrySwipe
import it.infraconsulting.activitytrack.domain.settings.Gestures
import it.infraconsulting.activitytrack.domain.time.atMinute
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.common.CommandStyle
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.STOP_STYLE
import it.infraconsulting.activitytrack.ui.common.SwipeAction
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.displayStyle
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import it.infraconsulting.activitytrack.ui.common.reasonText
import it.infraconsulting.activitytrack.ui.common.snapshot
import it.infraconsulting.activitytrack.ui.todo.TodoRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/*
 * The buttons of a running activity (ACTIVITY-MODEL.md 3): which are offered
 * comes from ActivityStateMachine.allowed, so the detail screen shows the
 * same. No "Sospendi": suspend is internal (another activity started).
 */
sealed interface RunAction {
	data object Pause : RunAction

	/* work or travel: [resume] "Riprendi" the kind open before a pause or a suspension */
	data class Go(val kind: SegmentKind, val resume: Boolean = false) : RunAction

	data object Stop : RunAction
}

/* The state machine's view of a stored activity. */
internal fun TimeEntryEntity.machineState() = ActivityState(
	kind, status, mode, closedUtcMs?.let(Instant::ofEpochMilli), deletedUtcMs?.let(Instant::ofEpochMilli),
)

/*
 * The buttons offered for [e] and its [segments], in this order: pause,
 * resume (the kind open before the pause), the other of work and travel,
 * stop. From a pause: Riprendi, Viaggio, Ferma.
 */
fun runActions(e: TimeEntryEntity, segments: List<SegmentEntity>): List<RunAction> {
	val allowed = ActivityStateMachine.allowed(e.machineState())
	fun canGo(k: SegmentKind) = allowed.any { (it is ActivityCommand.Switch && it.kind == k) || (it is ActivityCommand.Resume && it.kind == k) }
	val resting = e.mode == null || e.mode == SegmentKind.PAUSE
	val back = if (resting) resumeKindOf(e, segments) else null
	return buildList {
		if (allowed.any { it is ActivityCommand.Switch && it.kind == SegmentKind.PAUSE })
			add(RunAction.Pause)
		if (back != null && canGo(back))
			add(RunAction.Go(back, resume = true))
		for (k in listOf(SegmentKind.WORK, SegmentKind.TRAVEL))
			if (k != back && canGo(k))
				add(RunAction.Go(k))
		if (allowed.any { it is ActivityCommand.Stop })
			add(RunAction.Stop)
	}
}

/* "Riprendi": the kind of the last work or travel segment before the open pause (or before now when suspended). */
internal fun resumeKindOf(e: TimeEntryEntity, segments: List<SegmentEntity>): SegmentKind {
	val pause = segments.lastOrNull { it.endUtcMs == null && !it.planned && it.kind == SegmentKind.PAUSE }
	return ActivityStateMachine.resumeKind(e.snapshot(segments).segments, pause?.let { Instant.ofEpochMilli(it.startUtcMs) } ?: Instant.MAX)
}

internal fun RunAction.style(): CommandStyle = when (this) {
	RunAction.Pause -> displayStyle(DisplayState.PAUSE)
	is RunAction.Go -> displayStyle(if (kind == SegmentKind.TRAVEL) DisplayState.TRAVEL else DisplayState.WORK)
	RunAction.Stop -> STOP_STYLE
}

@StringRes
internal fun RunAction.label(): Int = when (this) {
	RunAction.Pause -> R.string.cmd_pause
	is RunAction.Go -> if (resume) R.string.cmd_resume else if (kind == SegmentKind.TRAVEL) R.string.cmd_travel else R.string.cmd_work
	RunAction.Stop -> R.string.cmd_stop
}

/*
 * A stop (or end time) typed as a time of day: today's, unless that lies
 * more than the 60 minutes ahead that a close may (ACTIVITY-MODEL.md 4.1),
 * then yesterday's.
 */
internal fun closeAt(minuteOfDay: Int, now: Instant, zone: ZoneId): Instant {
	val today = now.atZone(zone).toLocalDate()
	val t = today.atMinute(minuteOfDay, zone)
	return if (t.isAfter(now.plus(FutureClose.MAX_AHEAD))) today.minusDays(1).atMinute(minuteOfDay, zone) else t
}

/* The stop question for [entry]: the return-trip [choices] (3.2, empty = a plain stop), [short] under a minute. */
class StopRequest(val entry: TimeEntryEntity, val short: Boolean, val choices: List<ReturnChoice>)

/*
 * Commands on running activities from the app (buttons and swipes, Home and
 * Agenda). After a stop, a suspended activity is proposed for resumption.
 */
open class EntryCommander(
	private val entries: EntryRepository,
	@Suppress("unused") private val todos: TodoRepository? = null,
) : ActionViewModel() {
	/* Suspended activity proposed for resumption after a stop. */
	private val _resume = MutableStateFlow<TimeEntryEntity?>(null)
	val resume = _resume.asStateFlow()

	/* pause, resume, work, travel in the same activity (the travel question is asked before, 3) */
	fun perform(e: TimeEntryEntity, a: RunAction) = run {
		when (a) {
			RunAction.Pause -> entries.switchTo(e.id, SegmentKind.PAUSE)
			is RunAction.Go -> if (e.mode == null) entries.resume(e.id, a.kind) else entries.switchTo(e.id, a.kind)
			RunAction.Stop -> throw IllegalArgumentException("a stop goes through requestStop")
		}
	}

	/* "Hai completato l'attività lavorativa?": yes stops it and travels in a new activity, no switches to travel */
	fun travelFromWork(e: TimeEntryEntity, workDone: Boolean) = run { entries.travelFromWork(e.id, workDone) }

	/*
	 * Stop asked: [show] gets the question when there is one (return trip,
	 * under a minute, or an end time to type); otherwise it stops at once.
	 */
	fun requestStop(e: TimeEntryEntity, editableTimes: Boolean, show: (StopRequest) -> Unit) = run {
		val choices = entries.returnChoices(e.id)
		val short = entries.isDiscardable(e.id)
		if (choices.isEmpty() && !short && !editableTimes) {
			entries.stop(e.id)
			_resume.value = entries.suspendedToResume(e.id)
		} else
			show(StopRequest(e, short, choices))
	}

	/* a choice of the stop question, at [atUtcMs] or now; [done] closes the sheet */
	fun stopWith(e: TimeEntryEntity, choice: ReturnChoice, atUtcMs: Long?, done: () -> Unit) = run(done) {
		when (choice) {
			ReturnChoice.TRAVEL_BACK -> entries.travelBack(e.id, atUtcMs)
			ReturnChoice.STOP -> entries.stop(e.id, atUtcMs)
			ReturnChoice.STOP_WITH_RETURN -> entries.stopWithReturn(e.id, atUtcMs)
		}
		if (choice != ReturnChoice.TRAVEL_BACK)
			_resume.value = entries.suspendedToResume(e.id)
	}

	fun invalidTime() = fail(ValidationException.Reason.INVALID_TIME)

	fun discard(id: Long, done: () -> Unit = {}) = run(done) { entries.discard(id) }

	/* a closed activity back to running (the screen asked first): the gap stays a gap */
	fun reopen(e: TimeEntryEntity, kind: SegmentKind) = run { entries.reopen(e.id, kind) }

	fun dismissResume() {
		_resume.value = null
	}

	/* "Riprendi" what a stop left suspended, in the kind it was in */
	fun resume(id: Long) = run {
		_resume.value = null
		val e = entries.find(id) ?: return@run
		val segments = entries.segments(id).first()
		entries.resume(id, resumeKindOf(e, segments))
	}
}

/*
 * Runs the buttons of a running activity and shows what they ask: the travel
 * question while working, the stop question (return trip, end time, discard)
 * and the resumption proposal after a stop. [titleOf] names an activity in
 * the dialogs. Returns the dispatcher.
 */
@Composable
fun rememberRunCommands(vm: EntryCommander, editableTimes: Boolean = false, titleOf: (Long) -> String): (TimeEntryEntity, RunAction) -> Unit {
	var stopping by remember { mutableStateOf<StopRequest?>(null) }
	var travelling by remember { mutableStateOf<TimeEntryEntity?>(null) }
	val resume by vm.resume.collectAsState()

	stopping?.let { r -> StopSheet(r, editableTimes, vm, titleOf(r.entry.id)) { stopping = null } }
	travelling?.let { e ->
		AlertDialog(
			onDismissRequest = { travelling = null },
			title = { Text(stringResource(R.string.travel_done_question)) },
			confirmButton = { TextButton(onClick = { travelling = null; vm.travelFromWork(e, true) }) { Text(stringResource(R.string.action_yes)) } },
			dismissButton = { TextButton(onClick = { travelling = null; vm.travelFromWork(e, false) }) { Text(stringResource(R.string.action_no)) } },
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
	return { e, a ->
		when {
			a == RunAction.Stop -> vm.requestStop(e, editableTimes) { stopping = it }
			a is RunAction.Go && a.kind == SegmentKind.TRAVEL && e.mode == SegmentKind.WORK -> travelling = e
			else -> vm.perform(e, a)
		}
	}
}

/*
 * Reopening a closed activity always asks (3): work or travel; "Annulla"
 * leaves it closed. Returns the function that asks.
 */
@Composable
fun rememberReopen(vm: EntryCommander, titleOf: (Long) -> String): (TimeEntryEntity) -> Unit {
	var asking by remember { mutableStateOf<TimeEntryEntity?>(null) }
	asking?.let { e ->
		AlertDialog(
			onDismissRequest = { asking = null },
			title = { Text(stringResource(R.string.reopen_title, titleOf(e.id))) },
			text = { Text(stringResource(R.string.reopen_text)) },
			confirmButton = {
				Row {
					TextButton(onClick = { asking = null; vm.reopen(e, SegmentKind.WORK) }) { Text(stringResource(R.string.cmd_work)) }
					TextButton(onClick = { asking = null; vm.reopen(e, SegmentKind.TRAVEL) }) { Text(stringResource(R.string.cmd_travel)) }
				}
			},
			dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	return { asking = it }
}

/*
 * The stop question (3.2): the return-trip choices when they apply, else a
 * single Stop; with editable times an end time (empty = now, up to 60
 * minutes ahead for a stop); under a minute "Scarta".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopSheet(r: StopRequest, editableTimes: Boolean, vm: EntryCommander, title: String, onClose: () -> Unit) {
	val error by vm.error.collectAsState()
	var end by remember { mutableStateOf("") }
	fun dismiss() {
		vm.clearError()
		onClose()
	}
	fun go(choice: ReturnChoice) {
		val min = parseHhMm(end)
		val at = when {
			end.isBlank() -> null
			min == null || min >= 1440 -> return vm.invalidTime()
			else -> closeAt(min, Instant.now(), ZoneId.systemDefault()).toEpochMilli()
		}
		vm.stopWith(r.entry, choice, at, onClose)
	}
	ModalBottomSheet(onDismissRequest = ::dismiss) {
		Column(
			Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp).verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(stringResource(R.string.stop_title, title), style = MaterialTheme.typography.titleLarge)
			if (r.short) {
				/* under a minute: probably started by mistake (backlog 10) */
				Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
					Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Text(stringResource(R.string.short_title, title), style = MaterialTheme.typography.titleSmall)
						Text(stringResource(R.string.short_text), style = MaterialTheme.typography.bodyMedium)
					}
				}
				OutlinedButton(onClick = { vm.discard(r.entry.id, onClose) }, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.short_discard), color = MaterialTheme.colorScheme.error)
				}
			}
			if (editableTimes) {
				/* a new time drops the error about the previous one */
				TimeField(stringResource(R.string.field_end), end, { end = it; vm.clearError() }, clearable = true, importance = Importance.OPTIONAL)
				Text(stringResource(R.string.time_now_hint), style = MaterialTheme.typography.bodySmall)
			}
			error?.let { Text(reasonText(it), color = MaterialTheme.colorScheme.error) }
			if (r.choices.isEmpty())
				ChoiceButton(R.string.cmd_stop, STOP_STYLE) { go(ReturnChoice.STOP) }
			else
				for (c in r.choices)
					when (c) {
						ReturnChoice.TRAVEL_BACK -> ChoiceButton(R.string.stop_choice_travel_back, displayStyle(DisplayState.TRAVEL)) { go(c) }
						ReturnChoice.STOP -> ChoiceButton(R.string.stop_choice_end, STOP_STYLE) { go(c) }
						ReturnChoice.STOP_WITH_RETURN -> ChoiceButton(R.string.stop_choice_end_return, STOP_STYLE) { go(c) }
					}
			TextButton(onClick = ::dismiss) { Text(stringResource(R.string.action_cancel)) }
		}
	}
}

@Composable
private fun ChoiceButton(@StringRes label: Int, style: CommandStyle, onClick: () -> Unit) {
	Button(
		onClick = onClick,
		colors = ButtonDefaults.buttonColors(containerColor = style.container, contentColor = style.content),
		modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
	) {
		Icon(painterResource(style.icon), contentDescription = null, modifier = Modifier.size(24.dp))
		Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
	}
}

/*
 * The buttons a swipe on a running activity reveals ([swipe] for that
 * direction): pause, or resume in [back] (the kind open before), and stop,
 * where the state machine allows them. There is no suspend swipe any more.
 */
@Composable
fun runSwipeActions(e: TimeEntryEntity, back: SegmentKind, swipe: EntrySwipe, onAction: (RunAction) -> Unit): List<SwipeAction> =
	Gestures.entryCommands(swipe, e.status, e.mode).mapNotNull { cmd ->
		val a = when (cmd) {
			EntryCommand.PAUSE -> RunAction.Pause
			EntryCommand.WORK -> RunAction.Go(back, resume = true)
			EntryCommand.STOP -> RunAction.Stop
			else -> return@mapNotNull null
		}
		val style = a.style()
		SwipeAction(stringResource(a.label()), style.icon, style.container, style.content) { onAction(a) }
	}

/*
 * The 0.9 names, kept for the Agenda until it is rebuilt.
 * TODO(activity-1.0-ui): delete with the Agenda's use of EntryCommand.
 */
@Composable
fun rememberEntryCommands(vm: EntryCommander, editableTimes: Boolean = false, titleOf: (Long) -> String): (TimeEntryEntity, EntryCommand) -> Unit {
	val run = rememberRunCommands(vm, editableTimes, titleOf)
	return { e, cmd ->
		when (cmd) {
			EntryCommand.WORK -> run(e, RunAction.Go(SegmentKind.WORK, resume = true))
			EntryCommand.TRAVEL -> run(e, RunAction.Go(SegmentKind.TRAVEL))
			EntryCommand.PAUSE -> run(e, RunAction.Pause)
			EntryCommand.STOP -> run(e, RunAction.Stop)
			EntryCommand.SUSPEND, EntryCommand.REOPEN -> {}
		}
	}
}

@Composable
fun entrySwipeActions(e: TimeEntryEntity, swipe: EntrySwipe, onCommand: (EntryCommand) -> Unit): List<SwipeAction> =
	runSwipeActions(e, SegmentKind.WORK, swipe) { a ->
		onCommand(
			when (a) {
				RunAction.Pause -> EntryCommand.PAUSE
				is RunAction.Go -> if (a.kind == SegmentKind.TRAVEL) EntryCommand.TRAVEL else EntryCommand.WORK
				RunAction.Stop -> EntryCommand.STOP
			},
		)
	}
