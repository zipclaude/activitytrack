/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.ui.entry

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import it.infraconsulting.activitytrack.ActivityTrackApp
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.canComplete
import it.infraconsulting.activitytrack.data.repo.canUncomplete
import it.infraconsulting.activitytrack.data.repo.canUntick
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand
import it.infraconsulting.activitytrack.domain.activity.ActivityDisplay
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivitySegment
import it.infraconsulting.activitytrack.domain.activity.ActivityState
import it.infraconsulting.activitytrack.domain.activity.ActivityStateMachine
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.activity.ReturnChoice
import it.infraconsulting.activitytrack.domain.model.QuickAction
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.ui.LocalCompact
import it.infraconsulting.activitytrack.ui.common.CommandIconButton
import it.infraconsulting.activitytrack.ui.common.CommandStyle
import it.infraconsulting.activitytrack.ui.common.DialogAction
import it.infraconsulting.activitytrack.ui.common.DialogButtonKind
import it.infraconsulting.activitytrack.ui.common.DialogButtons
import it.infraconsulting.activitytrack.ui.common.StatePill
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.clockOf
import it.infraconsulting.activitytrack.ui.common.STOP_STYLE
import it.infraconsulting.activitytrack.ui.common.displayName
import it.infraconsulting.activitytrack.ui.common.displayStyle
import it.infraconsulting.activitytrack.ui.common.enabled
import it.infraconsulting.activitytrack.ui.common.hmOf
import it.infraconsulting.activitytrack.ui.common.reasonText
import it.infraconsulting.activitytrack.ui.common.snapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/*
 * The top of the detail dialog (ACTIVITY-MODEL.md 8): the displayed state
 * as on the Home ("Lavoro dalle 9:12", its icon and colour), the time
 * worked on the right, the segments as a coloured bar (a tap scrolls to
 * them), the totals, then the commands the state allows.
 */
@Composable
internal fun DetailHeader(
	e: TimeEntryEntity,
	segments: List<SegmentEntity>,
	editableTimes: Boolean,
	onTimes: () -> Unit,
	actions: List<DetailAction>,
) {
	val zone = ZoneId.of(e.zoneId)
	val compact = LocalCompact.current
	/* the running segment's time moves on */
	val now by produceState(System.currentTimeMillis()) {
		while (true) {
			delay(30_000)
			value = System.currentTimeMillis()
		}
	}
	val real = segments.filter { !it.planned }
	fun len(s: SegmentEntity) = maxOf(0L, (s.endUtcMs ?: now) - s.startUtcMs)
	fun sum(k: SegmentKind) = real.filter { it.kind == k }.sumOf(::len)
	/* a field the company's plugin requires is empty: a closed activity is incomplete (2.4, issue #178) */
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val missingRequired by produceState(false, e) { value = app.requiredFields.current().missing(e) }
	val shown = ActivityDisplay.display(e.snapshot(segments, missingRequired), Instant.ofEpochMilli(now), zone)
	Card(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
			/* two nodes' changes left overlapping segments (issue #119): fixed below, or on the web by a manager when times are locked */
			if (e.needsReview)
				Text(stringResource(if (editableTimes) R.string.entry_needs_review else R.string.entry_needs_review_locked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
			Row(verticalAlignment = Alignment.CenterVertically) {
				Box(Modifier.weight(1f)) {
					val name = stringResource(displayName(shown.state))
					StatePill(displayStyle(shown.state), shown.since?.let { stringResource(R.string.since_fmt, name, sinceText(it, zone, now)) } ?: name)
				}
				/* work time only: the total of work (owner: "il tempo totale di lavoro"), the travel and pause are in the line below */
				val total = if (e.kind == ActivityKind.ABSENCE) sum(SegmentKind.ABSENCE) else sum(SegmentKind.WORK)
				Text(hmOf(total), style = MaterialTheme.typography.titleLarge)
			}
			/* a closed activity ending ahead (4.1): its end highlighted */
			val lastEnd = real.mapNotNull { it.endUtcMs }.maxOrNull()
			if (lastEnd != null && lastEnd > now && real.none { it.endUtcMs == null })
				Text(
					stringResource(R.string.detail_end_ahead, clockOf(lastEnd, zone)),
					color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
				)
			Timeline(real.ifEmpty { segments }, now, real.isEmpty(), onTimes)
			if (e.kind == ActivityKind.ACTIVITY && real.isNotEmpty())
				Text(
					stringResource(R.string.entry_summary_totals, hmOf(sum(SegmentKind.WORK)), hmOf(sum(SegmentKind.TRAVEL)), hmOf(sum(SegmentKind.PAUSE))),
					style = MaterialTheme.typography.bodySmall,
				)
			if (actions.isNotEmpty())
				ActionSlots(actions, compact)
		}
	}
}

/*
 * The commands in equal slots (issue #187): icon above a short label,
 * centred, two lines at most; as many to a row as fit at [MIN_SLOT] each
 * (up to five, every state's set on a phone), further ones on a second row
 * with the same slot width, centred.
 */
@Composable
private fun ActionSlots(actions: List<DetailAction>, compact: Boolean) {
	BoxWithConstraints(Modifier.fillMaxWidth()) {
		val perRow = actionsPerRow(maxWidth.value, MIN_SLOT.value)
		val slot = maxWidth / minOf(actions.size, perRow)
		Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			for (row in actionRows(actions.size, perRow))
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
					for (i in row) {
						val a = actions[i]
						Column(
							Modifier.width(slot).padding(horizontal = 2.dp),
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(2.dp),
						) {
							CommandIconButton(a.style, stringResource(a.description), a.onClick, if (compact) Modifier.size(44.dp) else Modifier.size(52.dp))
							Text(
								stringResource(a.label),
								style = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph),
								textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
							)
						}
					}
				}
		}
	}
}

/* the narrowest slot of a command: a 52 dp button and a short label */
private val MIN_SLOT = 64.dp

/* How many slots a row of [widthDp] holds: at least three, at most five (pure, unit-tested). */
internal fun actionsPerRow(widthDp: Float, minSlotDp: Float): Int = (widthDp / minSlotDp).toInt().coerceIn(3, 5)

/* The indices of [count] commands in rows of [perRow], the last row holding what is left. */
internal fun actionRows(count: Int, perRow: Int): List<IntRange> =
	(0 until count).chunked(perRow).map { it.first()..it.last() }

/* "9:12", or the day too when it is not today ("lun 26/10 9:12"). */
@Composable
private fun sinceText(at: Instant, zone: ZoneId, now: Long): String {
	val day = at.atZone(zone).toLocalDate()
	val clock = clockOf(at.toEpochMilli(), zone)
	if (day == Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
		return clock
	return day.format(DateTimeFormatter.ofPattern("EEE d/MM", LocalConfiguration.current.locales[0])) + " " + clock
}

/* The segments as a bar in their order and length; [planned] ones faded (1.2). */
@Composable
private fun Timeline(segments: List<SegmentEntity>, now: Long, planned: Boolean, onTimes: () -> Unit) {
	fun len(s: SegmentEntity) = maxOf(0L, (s.endUtcMs ?: now) - s.startUtcMs)
	/* a segment under a minute reads 0:00 in the list: no stripe for it here, unless nothing else would be drawn */
	val shown = segments.filter { len(it) >= 60_000 }.ifEmpty { segments.filter { len(it) > 0 } }
	val total = shown.sumOf(::len)
	if (total <= 0)
		return
	Row(
		Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).clickable(onClick = onTimes),
		horizontalArrangement = Arrangement.spacedBy(2.dp),
	) {
		for (s in shown.sortedBy { it.startUtcMs }) {
			val w = len(s).toFloat() / total
			if (w > 0f) {
				val c = segmentStyle(s.kind).container
				Box(Modifier.weight(w).fillMaxSize().background(if (planned) c.copy(alpha = 0.35f) else c))
			}
		}
	}
}

/*
 * A command of the detail dialog: its short label (issue #187), the Home's
 * round button style, what it does; [description] the full name read by
 * accessibility services.
 */
internal data class DetailAction(@StringRes val label: Int, val style: CommandStyle, @StringRes val description: Int = label, val onClick: () -> Unit)

/* What the detail header offers, before labels and styles (pure, unit-tested in EntryDetailTest). */
internal sealed interface DetailCommand {
	data class Start(val kind: SegmentKind) : DetailCommand
	/* [resume]: the kind the pause interrupted, labelled "Riprendi" (3) */
	data class Switch(val kind: SegmentKind, val resume: Boolean) : DetailCommand
	data class Resume(val kind: SegmentKind) : DetailCommand
	data object Stop : DetailCommand
	data object Tick : DetailCommand
	data class Reopen(val kind: SegmentKind) : DetailCommand
	data object Untick : DetailCommand
	/* the declared completion of an activity of travel only (2.4, issue #189) */
	data object Complete : DetailCommand
	data object Uncomplete : DetailCommand
	data object Delete : DetailCommand
	data object Restore : DetailCommand
}

/*
 * The commands the activity's state allows (ActivityStateMachine.allowed):
 * start in work or travel (TODO), switch, pause, resume and stop (RUNNING),
 * reopen in work or travel (CLOSED, asked first), tick (TODO), untick
 * (CLOSED without real segments), complete and uncomplete (CLOSED, issue
 * #189), then delete or restore. No "suspend" (8). Travel and deletion
 * follow the policy ([travel], [canDelete]).
 */
internal fun detailCommands(e: TimeEntryEntity, segments: List<SegmentEntity>, travel: Boolean, canDelete: Boolean): List<DetailCommand> {
	val state = ActivityState(
		e.kind, e.status, e.mode, e.closedUtcMs?.let(Instant::ofEpochMilli), e.deletedUtcMs?.let(Instant::ofEpochMilli),
		e.completedUtcMs?.let(Instant::ofEpochMilli),
	)
	val main = ArrayList<DetailCommand>()
	val last = ArrayList<DetailCommand>()
	fun ok(kind: SegmentKind) = kind != SegmentKind.TRAVEL || travel
	/* from a pause "Riprendi" is the kind open before it (3) */
	val resumeKind = if (e.mode == SegmentKind.PAUSE) {
		val open = segments.firstOrNull { it.endUtcMs == null && !it.planned }
		ActivityStateMachine.resumeKind(
			segments.map { ActivitySegment(it.kind, Instant.ofEpochMilli(it.startUtcMs), it.endUtcMs?.let(Instant::ofEpochMilli), it.planned) },
			open?.let { Instant.ofEpochMilli(it.startUtcMs) } ?: Instant.now(),
		)
	} else null
	for (cmd in ActivityStateMachine.allowed(state))
		when (cmd) {
			is ActivityCommand.Start -> if (ok(cmd.kind)) main += DetailCommand.Start(cmd.kind)
			is ActivityCommand.Switch -> if (ok(cmd.kind)) main += DetailCommand.Switch(cmd.kind, cmd.kind == resumeKind)
			is ActivityCommand.Resume -> if (ok(cmd.kind)) main += DetailCommand.Resume(cmd.kind)
			is ActivityCommand.Suspend -> {}
			is ActivityCommand.Stop -> main += DetailCommand.Stop
			ActivityCommand.Tick -> main += DetailCommand.Tick
			is ActivityCommand.Reopen -> if (ok(cmd.kind)) main += DetailCommand.Reopen(cmd.kind)
			ActivityCommand.Delete -> if (canDelete) last += DetailCommand.Delete
			ActivityCommand.Restore -> last += DetailCommand.Restore
		}
	if (canUntick(e, segments))
		main += DetailCommand.Untick
	if (canComplete(e, segments))
		main += DetailCommand.Complete
	if (canUncomplete(e))
		main += DetailCommand.Uncomplete
	return main + last
}

/* The commands as buttons, wired to the view model. */
@Composable
internal fun detailActions(e: TimeEntryEntity, segments: List<SegmentEntity>, vm: EntryViewModel): List<DetailAction> =
	detailCommands(e, segments, enabled(Feature.TRAVEL), enabled(Feature.DELETE_ENTRIES)).map { c ->
		when (c) {
			is DetailCommand.Start -> DetailAction(kindName(c.kind), segmentStyle(c.kind)) { vm.startTodo(c.kind) }
			is DetailCommand.Switch -> DetailAction(if (c.resume) R.string.cmd_resume else kindName(c.kind), segmentStyle(c.kind)) { vm.switchTo(c.kind) }
			is DetailCommand.Resume -> DetailAction(if (c.kind == SegmentKind.WORK) R.string.cmd_resume else kindName(c.kind), segmentStyle(c.kind)) { vm.resume(c.kind) }
			DetailCommand.Stop -> DetailAction(R.string.cmd_stop, STOP_STYLE) { vm.askStop() }
			DetailCommand.Tick -> DetailAction(R.string.detail_short_done, displayStyle(DisplayState.DONE), R.string.todo_mark_done) { vm.tick() }
			/* "Riapri" and "Viaggio" (owner): the icon tells the kind, the full name goes to accessibility */
			is DetailCommand.Reopen ->
				if (c.kind == SegmentKind.TRAVEL) DetailAction(R.string.cmd_travel, segmentStyle(c.kind), R.string.detail_reopen_travel) { vm.askReopen(c.kind) }
				else DetailAction(R.string.cmd_reopen, segmentStyle(c.kind), R.string.detail_reopen_work) { vm.askReopen(c.kind) }
			DetailCommand.Untick -> DetailAction(R.string.state_todo, displayStyle(DisplayState.TODO)) { vm.untick() }
			DetailCommand.Complete -> DetailAction(R.string.detail_complete, displayStyle(DisplayState.DONE)) { vm.complete() }
			DetailCommand.Uncomplete -> DetailAction(
				R.string.detail_uncomplete,
				CommandStyle(R.drawable.ic_restore, displayStyle(DisplayState.INCOMPLETE).container, displayStyle(DisplayState.INCOMPLETE).content),
				R.string.detail_uncomplete_full,
			) { vm.uncomplete() }
			DetailCommand.Delete -> DetailAction(R.string.action_delete, displayStyle(DisplayState.DELETED)) { vm.askDelete() }
			DetailCommand.Restore -> DetailAction(R.string.swipe_restore, CommandStyle(R.drawable.ic_restore, displayStyle(DisplayState.WORK).container, androidx.compose.ui.graphics.Color.White)) { vm.restore() }
		}
	}

@StringRes
private fun kindName(k: SegmentKind): Int = when (k) {
	SegmentKind.WORK -> R.string.cmd_work
	SegmentKind.TRAVEL -> R.string.cmd_travel
	SegmentKind.PAUSE -> R.string.cmd_pause
	SegmentKind.ABSENCE -> R.string.state_absence
}

/* The questions the commands ask: travel from work, stop (end time and return trip), reopen. */
@Composable
internal fun CommandDialogs(vm: EntryViewModel, zone: ZoneId) {
	val travelAsk by vm.travelAsk.collectAsState()
	if (travelAsk)
		AlertDialog(
			onDismissRequest = vm::cancelTravel,
			title = { Text(stringResource(R.string.travel_done_question)) },
			text = { Text(stringResource(R.string.detail_travel_hint)) },
			confirmButton = {
				DialogButtons(
					listOf(
						DialogAction(stringResource(R.string.action_no)) { vm.answerTravel(false) },
						DialogAction(stringResource(R.string.action_yes), DialogButtonKind.PRIMARY) { vm.answerTravel(true) },
					),
				)
			},
		)

	val stop by vm.stopPrompt.collectAsState()
	stop?.let { p ->
		val editable by vm.editableTimes.collectAsState()
		val error by vm.error.collectAsState()
		var end by remember { mutableStateOf("") }
		val options = p.choices.ifEmpty { listOf(ReturnChoice.STOP) }
		AlertDialog(
			onDismissRequest = vm::cancelStop,
			title = { Text(stringResource(R.string.stop_choice_end)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					if (editable) {
						TimeField(stringResource(R.string.detail_stop_at), end, { end = it }, clearable = true)
						Text(stringResource(R.string.detail_stop_ahead_hint), style = MaterialTheme.typography.bodySmall)
					}
					if (p.choices.isNotEmpty())
						Text(stringResource(R.string.detail_return_question))
					error?.let { Text(reasonText(it), color = MaterialTheme.colorScheme.error) }
					for ((i, c) in options.withIndex()) {
						val label = stringResource(
							when (c) {
								ReturnChoice.TRAVEL_BACK -> R.string.stop_return
								ReturnChoice.STOP -> R.string.stop_choice_end
								ReturnChoice.STOP_WITH_RETURN -> R.string.stop_choice_end_return
							},
						)
						if (i == options.lastIndex)
							Button(onClick = { vm.stop(c, end, zone) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
						else
							OutlinedButton(onClick = { vm.stop(c, end, zone) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
					}
				}
			},
			/* the choices are buttons already; Annulla is one too, full width under them (issue #186) */
			confirmButton = { DialogButtons(listOf(DialogAction(stringResource(R.string.action_cancel), onClick = vm::cancelStop))) },
		)
	}

	val reopen by vm.reopenAsk.collectAsState()
	reopen?.let { kind ->
		AlertDialog(
			onDismissRequest = vm::cancelReopen,
			title = { Text(stringResource(if (kind == SegmentKind.TRAVEL) R.string.detail_reopen_travel else R.string.detail_reopen_work)) },
			text = { Text(stringResource(R.string.detail_reopen_text)) },
			confirmButton = {
				DialogButtons(
					listOf(
						DialogAction(stringResource(R.string.action_cancel), onClick = vm::cancelReopen),
						DialogAction(
							stringResource(R.string.cmd_reopen), DialogButtonKind.PRIMARY,
							container = segmentStyle(kind).container, content = segmentStyle(kind).content, onClick = vm::reopen,
						),
					),
				)
			},
		)
	}
}

/* Today's date as the default day of a segment typed by hand: the activity's first day, else its plan, else today. */
internal fun defaultSegmentDay(e: TimeEntryEntity, segments: List<SegmentEntity>, zone: ZoneId): LocalDate =
	segments.minByOrNull { it.startUtcMs }?.let { Instant.ofEpochMilli(it.startUtcMs).atZone(zone).toLocalDate() }
		?: e.planEpochDay?.let(LocalDate::ofEpochDay)
		?: LocalDate.now(zone)
