/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.model.QuickAction
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.domain.time.atMinute
import it.infraconsulting.activitytrack.ui.common.CommandStyle
import it.infraconsulting.activitytrack.ui.common.DateField
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.PolicyField
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.TimeGridSheet
import it.infraconsulting.activitytrack.ui.common.clockOf
import it.infraconsulting.activitytrack.ui.common.dateText
import it.infraconsulting.activitytrack.ui.common.displayStyle
import it.infraconsulting.activitytrack.ui.common.fieldImportance
import it.infraconsulting.activitytrack.ui.common.fieldLabel
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.localRange
import it.infraconsulting.activitytrack.ui.common.parseDate
import it.infraconsulting.activitytrack.ui.common.parseDecimal
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* Colour and icon of a segment kind, as the state it shows (issue #57). */
fun segmentStyle(kind: SegmentKind): CommandStyle = when (kind) {
	SegmentKind.WORK -> displayStyle(DisplayState.WORK)
	SegmentKind.PAUSE -> displayStyle(DisplayState.PAUSE)
	SegmentKind.TRAVEL -> displayStyle(DisplayState.TRAVEL)
	SegmentKind.ABSENCE -> displayStyle(DisplayState.ABSENCE)
}

/* The kind's badge: filled for a real segment, a dashed ring for a planned one (1.2). */
@Composable
internal fun SegmentBadge(kind: SegmentKind, planned: Boolean) {
	val style = segmentStyle(kind)
	if (planned)
		Box(
			Modifier.size(32.dp).drawBehind {
				drawCircle(style.container, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
			},
			contentAlignment = Alignment.Center,
		) {
			Icon(painterResource(style.icon), contentDescription = null, tint = style.container, modifier = Modifier.size(18.dp))
		}
	else
		Box(Modifier.size(32.dp).background(style.container, CircleShape), contentAlignment = Alignment.Center) {
			Icon(painterResource(style.icon), contentDescription = null, tint = style.content, modifier = Modifier.size(20.dp))
		}
}

@Composable
internal fun kindLabel(k: SegmentKind) = stringResource(
	when (k) {
		SegmentKind.WORK -> R.string.cmd_work
		SegmentKind.TRAVEL -> R.string.cmd_travel
		SegmentKind.PAUSE -> R.string.cmd_pause
		SegmentKind.ABSENCE -> R.string.state_absence
	},
)

/*
 * Edit or delete a closed segment (backlog 11): date, start, end, km of a
 * travel; the new values replace the recorded ones, exactly as typed. The
 * end of the last one may lie up to 24 hours ahead with editable times
 * (4.1, checked by the repository). The running segment (issue #57) moves
 * its start only and cannot be deleted; a planned one takes any time.
 */
@Composable
internal fun SegmentDialog(s: SegmentEntity, zone: ZoneId, vm: EntryViewModel, onClose: () -> Unit) {
	if (s.endUtcMs == null) {
		RunningSegmentDialog(s, zone, vm, onClose)
		return
	}
	val error by vm.error.collectAsState()
	val startAt = Instant.ofEpochMilli(s.startUtcMs).atZone(zone)
	var date by remember { mutableStateOf(startAt.toLocalDate().toString()) }
	var start by remember { mutableStateOf(clockOf(s.startUtcMs, zone)) }
	var end by remember { mutableStateOf(s.endUtcMs?.let { clockOf(it, zone) }.orEmpty()) }
	var km by remember { mutableStateOf(s.kmDeclared?.toString().orEmpty()) }
	var note by remember { mutableStateOf(s.note.orEmpty()) }
	var deleting by remember { mutableStateOf(false) }
	var grid by remember { mutableStateOf(false) }
	EditDialog(
		title = stringResource(R.string.segment_edit),
		error = error,
		onDismiss = { vm.clearError(); onClose() },
		onDelete = { deleting = true },
		onConfirm = {
			val d = parseDate(date)
			val a = parseHhMm(start)
			val b = parseHhMm(end)
			if (d == null || a == null || b == null)
				vm.updateSegment(s.id, 1, 0, null, done = onClose)
			else {
				val (from, to) = localRange(d, a, b, zone)
				vm.updateSegment(
					s.id, from, to, if (s.kind == SegmentKind.TRAVEL) parseDecimal(km) else null,
					if (s.kind == SegmentKind.PAUSE) note else null, onClose,
				)
			}
		},
	) {
		DateField(stringResource(R.string.field_date), date, { date = it }, importance = Importance.REQUIRED)
		TimeField(stringResource(R.string.field_start), start, { start = it }, importance = Importance.REQUIRED, onOpen = { grid = true })
		TimeField(stringResource(R.string.field_end), end, { end = it }, importance = Importance.REQUIRED, onOpen = { grid = true })
		if (s.kind == SegmentKind.TRAVEL)
			PolicyField(Field.KM) { TextInput(fieldLabel(Field.KM, R.string.field_km), km, { km = it }, KeyboardType.Decimal, importance = fieldImportance(Field.KM, Importance.OPTIONAL)) }
		if (s.kind == SegmentKind.PAUSE)
			TextInput(stringResource(R.string.segment_note), note, { note = it }, importance = Importance.OPTIONAL)
		if (s.planned)
			Text(stringResource(R.string.detail_planned_hint), style = MaterialTheme.typography.bodySmall)
	}
	if (grid)
		SegmentGrid(kindLabel(s.kind), date, start, end, s.id, zone, vm, { grid = false }) { a, b -> start = a; end = b }
	if (deleting)
		ConfirmDialog(stringResource(R.string.confirm_delete_segment), { deleting = false }) {
			deleting = false
			vm.deleteSegment(s.id, onClose)
		}
}

@Composable
internal fun RunningSegmentDialog(s: SegmentEntity, zone: ZoneId, vm: EntryViewModel, onClose: () -> Unit) {
	val error by vm.error.collectAsState()
	var date by remember { mutableStateOf(Instant.ofEpochMilli(s.startUtcMs).atZone(zone).toLocalDate().toString()) }
	var start by remember { mutableStateOf(clockOf(s.startUtcMs, zone)) }
	var note by remember { mutableStateOf(s.note.orEmpty()) }
	EditDialog(
		title = stringResource(R.string.segment_edit),
		error = error,
		onDismiss = { vm.clearError(); onClose() },
		onConfirm = {
			val d = parseDate(date)
			val a = parseHhMm(start)?.takeIf { it < 1440 }
			/* a missing value is reported by the repository as an invalid segment */
			val from = if (d == null || a == null) Long.MAX_VALUE - 1 else d.atMinute(a, zone).toEpochMilli()
			vm.moveRunningStart(s.id, from) {
				if (s.kind == SegmentKind.PAUSE) vm.setSegmentNote(s.id, note, onClose) else onClose()
			}
		},
	) {
		DateField(stringResource(R.string.field_date), date, { date = it }, importance = Importance.REQUIRED)
		TimeField(stringResource(R.string.field_start), start, { start = it }, importance = Importance.REQUIRED)
		if (s.kind == SegmentKind.PAUSE)
			TextInput(stringResource(R.string.segment_note), note, { note = it }, importance = Importance.OPTIONAL)
		Text(stringResource(R.string.segment_running_hint), style = MaterialTheme.typography.bodySmall)
	}
}

/*
 * A segment typed by hand, opened by the split button with [kind] preset;
 * the kind stays editable among [kinds]. Times are stored as typed; for a
 * TODO activity the segment is a planned one ([planned]).
 */
@Composable
internal fun ManualSegmentDialog(
	kind: SegmentKind,
	kinds: List<SegmentKind>,
	planned: Boolean,
	defaultDay: LocalDate,
	zone: ZoneId,
	vm: EntryViewModel,
	onClose: () -> Unit,
) {
	val error by vm.error.collectAsState()
	var chosen by remember { mutableStateOf(kind) }
	var date by remember { mutableStateOf(defaultDay.toString()) }
	var start by remember { mutableStateOf("") }
	var end by remember { mutableStateOf("") }
	var km by remember { mutableStateOf("") }
	var grid by remember { mutableStateOf(false) }
	EditDialog(
		title = stringResource(if (kind == SegmentKind.ABSENCE) R.string.detail_add_day else R.string.detail_segment_new),
		error = error,
		onDismiss = { vm.clearError(); onClose() },
		onConfirm = {
			val d = parseDate(date)
			val s = parseHhMm(start)
			val t = parseHhMm(end)
			if (d == null || s == null || t == null) {
				/* reported by the repository as an invalid segment */
				vm.addSegment(chosen, 1, 0, null, onClose)
			} else {
				val (from, to) = localRange(d, s, t, zone)
				vm.addSegment(chosen, from, to, if (chosen == SegmentKind.TRAVEL) parseDecimal(km) else null, onClose)
			}
		},
	) {
		if (kinds.size > 1)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				for (k in kinds)
					FilterChip(
						selected = k == chosen,
						onClick = { chosen = k },
						label = { Text(kindLabel(k)) },
						leadingIcon = { Icon(painterResource(segmentStyle(k).icon), contentDescription = null, modifier = Modifier.size(18.dp)) },
					)
			}
		DateField(stringResource(R.string.field_date), date, { date = it })
		TimeField(stringResource(R.string.field_start), start, { start = it }, onOpen = { grid = true })
		TimeField(stringResource(R.string.field_end), end, { end = it }, onOpen = { grid = true })
		if (chosen == SegmentKind.TRAVEL)
			PolicyField(Field.KM) { TextInput(fieldLabel(Field.KM, R.string.field_km), km, { km = it }, KeyboardType.Decimal, importance = fieldImportance(Field.KM, null)) }
		Text(stringResource(R.string.segment_hint), style = MaterialTheme.typography.bodySmall)
		if (planned)
			Text(stringResource(R.string.detail_planned_hint), style = MaterialTheme.typography.bodySmall)
	}
	if (grid)
		SegmentGrid(kindLabel(chosen), date, start, end, null, zone, vm, { grid = false }) { a, b -> start = a; end = b }
}

/*
 * Start and end of a segment on the 5-minute grid (backlog 3): the other
 * segments of that day are hatched; "Exact time" is not offered here, the
 * fields keep any minute typed before.
 */
@Composable
private fun SegmentGrid(
	kind: String,
	date: String,
	start: String,
	end: String,
	except: Long?,
	zone: ZoneId,
	vm: EntryViewModel,
	onClose: () -> Unit,
	onPick: (String, String) -> Unit,
) {
	val day = parseDate(date) ?: LocalDate.now(zone)
	val busy by produceState(emptyList<IntRange>(), day, except) { value = vm.busyOn(day, except, zone) }
	val locale = LocalConfiguration.current.locales[0]
	val a = parseHhMm(start)
	val b = parseHhMm(end)
	TimeGridSheet(
		title = "$kind · ${dateText(day, locale)}",
		initial = if (a != null && b != null && b > a && a < 1440) a until b else null,
		range = true,
		busy = busy,
		day = day,
		onDismiss = onClose,
	) { from, to ->
		onClose()
		onPick(formatMin(from), formatMin(to))
	}
}

@Composable
internal fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
	AlertDialog(
		onDismissRequest = onDismiss,
		text = { Text(text) },
		confirmButton = {
			TextButton(onClick = onConfirm) {
				Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
	)
}
