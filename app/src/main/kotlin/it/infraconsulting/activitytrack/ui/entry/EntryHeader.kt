/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
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
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
					itemVerticalAlignment = Alignment.Top,
				) {
					for (a in actions) {
						val label = stringResource(a.label)
						Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
							CommandIconButton(a.style, label, a.onClick, if (compact) Modifier.size(44.dp) else Modifier)
							Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
						}
					}
				}
		}
	}
}

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

/* A command of the detail dialog: label, the Home's round button style, what it does. */
internal data class DetailAction(@StringRes val label: Int, val style: CommandStyle, val onClick: () -> Unit)

/*
 * The commands the activity's state allows (ActivityStateMachine.allowed),
 * as buttons: start in work or travel (TODO), switch, pause, resume and
 * stop (RUNNING), reopen in work or travel (CLOSED, asked first), tick
 * (TODO), untick (CLOSED without real segments), delete or restore. No
 * "suspend" (8). Travel and deletion follow the policy.
 */
@Composable
internal fun detailActions(e: TimeEntryEntity, segments: List<SegmentEntity>, vm: EntryViewModel): List<DetailAction> {
	val travel = enabled(Feature.TRAVEL)
	val canDelete = enabled(Feature.DELETE_ENTRIES)
	val state = ActivityState(e.kind, e.status, e.mode, e.closedUtcMs?.let(Instant::ofEpochMilli), e.deletedUtcMs?.let(Instant::ofEpochMilli))
	val main = ArrayList<DetailAction>()
	val last = ArrayList<DetailAction>()
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
			is ActivityCommand.Start -> if (ok(cmd.kind)) main += DetailAction(kindName(cmd.kind), segmentStyle(cmd.kind)) { vm.startTodo(cmd.kind) }
			is ActivityCommand.Switch -> if (ok(cmd.kind))
				main += DetailAction(if (cmd.kind == resumeKind) R.string.cmd_resume else kindName(cmd.kind), segmentStyle(cmd.kind)) { vm.switchTo(cmd.kind) }
			is ActivityCommand.Resume -> if (ok(cmd.kind))
				main += DetailAction(if (cmd.kind == SegmentKind.WORK) R.string.cmd_resume else kindName(cmd.kind), segmentStyle(cmd.kind)) { vm.resume(cmd.kind) }
			is ActivityCommand.Suspend -> {}
			is ActivityCommand.Stop -> main += DetailAction(R.string.cmd_stop, STOP_STYLE) { vm.askStop() }
			ActivityCommand.Tick -> main += DetailAction(R.string.todo_mark_done, displayStyle(DisplayState.DONE)) { vm.tick() }
			is ActivityCommand.Reopen -> if (ok(cmd.kind))
				main += DetailAction(if (cmd.kind == SegmentKind.TRAVEL) R.string.detail_reopen_travel else R.string.detail_reopen_work, segmentStyle(cmd.kind)) { vm.askReopen(cmd.kind) }
			ActivityCommand.Delete -> if (canDelete) last += DetailAction(R.string.action_delete, displayStyle(DisplayState.DELETED)) { vm.askDelete() }
			ActivityCommand.Restore -> last += DetailAction(R.string.swipe_restore, CommandStyle(R.drawable.ic_restore, displayStyle(DisplayState.WORK).container, androidx.compose.ui.graphics.Color.White)) { vm.restore() }
		}
	if (canUntick(e, segments))
		main += DetailAction(R.string.state_todo, displayStyle(DisplayState.TODO)) { vm.untick() }
	return main + last
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
			confirmButton = { TextButton(onClick = { vm.answerTravel(true) }) { Text(stringResource(R.string.action_yes)) } },
			dismissButton = { TextButton(onClick = { vm.answerTravel(false) }) { Text(stringResource(R.string.action_no)) } },
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
			confirmButton = {},
			dismissButton = { TextButton(onClick = vm::cancelStop) { Text(stringResource(R.string.action_cancel)) } },
		)
	}

	val reopen by vm.reopenAsk.collectAsState()
	reopen?.let { kind ->
		AlertDialog(
			onDismissRequest = vm::cancelReopen,
			title = { Text(stringResource(if (kind == SegmentKind.TRAVEL) R.string.detail_reopen_travel else R.string.detail_reopen_work)) },
			text = { Text(stringResource(R.string.detail_reopen_text)) },
			confirmButton = {
				Button(
					onClick = vm::reopen,
					colors = ButtonDefaults.buttonColors(containerColor = segmentStyle(kind).container, contentColor = segmentStyle(kind).content),
				) { Text(stringResource(R.string.cmd_reopen)) }
			},
			dismissButton = { TextButton(onClick = vm::cancelReopen) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
}

/* Today's date as the default day of a segment typed by hand: the activity's first day, else its plan, else today. */
internal fun defaultSegmentDay(e: TimeEntryEntity, segments: List<SegmentEntity>, zone: ZoneId): LocalDate =
	segments.minByOrNull { it.startUtcMs }?.let { Instant.ofEpochMilli(it.startUtcMs).atZone(zone).toLocalDate() }
		?: e.planEpochDay?.let(LocalDate::ofEpochDay)
		?: LocalDate.now(zone)
