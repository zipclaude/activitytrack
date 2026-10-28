/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.collectAsState
import it.infraconsulting.activitytrack.ui.appViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.time.TimeGrid

private val LABEL_WIDTH = 44.dp
private val ROW_HEIGHT = 30.dp
private val GAP = 3.dp

/*
 * Time selection on a grid of 5-minute cells (backlog 3, mockup B chosen
 * by the owner on 2026-10-19), as a bottom sheet. [range]: touch the first
 * cell and drag to the last, or tap the first and then the last (also
 * after moving the window); otherwise one tap picks a time. [busy] minutes
 * are hatched (other activities). [onExact] offers the clock for minutes
 * off the 5-minute grid. [onPick] gets start and end minute (end exclusive;
 * for a single time end = start + 5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeGridSheet(
	title: String,
	initial: IntRange?,
	range: Boolean,
	busy: List<IntRange> = emptyList(),
	onExact: (() -> Unit)? = null,
	/* the day shown: without a selection the window opens on its working hours (issue #44) */
	day: java.time.LocalDate = java.time.LocalDate.now(),
	/*
	 * In a range, a single tap then OK picks a start only (issue #52: a
	 * planned to-do may be a point in time); onPick then gets end = start.
	 * [initialPoint]: [initial] is such a start.
	 */
	allowPoint: Boolean = false,
	initialPoint: Boolean = false,
	onDismiss: () -> Unit,
	onPick: (Int, Int) -> Unit,
) {
	var selection by remember { mutableStateOf(initial) }
	var point by remember { mutableStateOf(allowPoint && initialPoint && initial != null) }
	val schedule by appViewModel(key = "grid-schedule") { ScheduleSource(it.masterData) }.schedule.collectAsState()
	val work = schedule.filter { it.dayOfWeek == day.dayOfWeek.value }.takeIf { it.isNotEmpty() }
		?.let { s -> s.minOf { it.startMin } to s.maxOf { it.endMin } }
	var first by remember { mutableStateOf(TimeGrid.windowFor(initial?.first ?: java.time.LocalTime.now().let { it.hour * 60 })) }
	/* the schedule arrives after the first frame: move once, only when nothing is selected */
	var placed by remember { mutableStateOf(initial != null) }
	androidx.compose.runtime.LaunchedEffect(work) {
		if (!placed && work != null) {
			first = TimeGrid.windowForWork(work.first, work.second)
			placed = true
		}
	}
	/* a tap without drag waits for a second tap that closes the range */
	var pending by remember { mutableStateOf<Int?>(null) }
	val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val selColor = MaterialTheme.colorScheme.primary
	val freeColor = MaterialTheme.colorScheme.surfaceContainerHighest
	val busyColor = MaterialTheme.colorScheme.outlineVariant

	/*
	 * Where each hour row really lies (issue #104): with a large system font
	 * the rows grow past ROW_HEIGHT, and a touch computed from the nominal
	 * height drifted one row per row down the grid.
	 */
	val rowTops = remember { androidx.compose.runtime.mutableStateMapOf<Int, Pair<Float, Float>>() }
	/* the hour labels keep one line: wider with a larger font instead of wrapping */
	val density = androidx.compose.ui.platform.LocalDensity.current
	val labelWidth = LABEL_WIDTH * density.fontScale.coerceAtLeast(1f)
	val gapPx = with(density) { GAP.toPx() }

	ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, sheetGesturesEnabled = false) {
		/* scrolls when the screen is short (landscape, issue #104); the grid itself takes the drags */
		Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
			Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			val sel = selection
			Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(
					when {
						sel == null -> "–"
						range && point -> formatMin(sel.first)
						range -> formatMin(sel.first) + " – " + formatMin(sel.last + 1)
						else -> formatMin(sel.first)
					},
					style = MaterialTheme.typography.headlineMedium,
				)
				if (range && sel != null && !point)
					Text(formatDuration(sel.last + 1 - sel.first), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Text(
				stringResource(if (range && allowPoint) R.string.grid_hint_point_or_range else if (range) R.string.grid_hint_range else R.string.grid_hint_single),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(8.dp))
			Row {
				TextButton(onClick = { first = TimeGrid.shift(first, -3) }, enabled = first > 0) { Text("▲ " + stringResource(R.string.grid_earlier)) }
				Spacer(Modifier.weight(1f))
				TextButton(onClick = { first = TimeGrid.shift(first, 3) }, enabled = first < 24 - TimeGrid.WINDOW_HOURS) { Text(stringResource(R.string.grid_later) + " ▼") }
			}
			/* minute labels every quarter of an hour */
			Row(Modifier.fillMaxWidth()) {
				Spacer(Modifier.width(labelWidth))
				for (k in 0 until 12)
					Text(
						if (k % 3 == 0) "%02d".format(k * 5) else "",
						fontSize = 10.sp,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.weight(1f),
						textAlign = androidx.compose.ui.text.style.TextAlign.Center,
					)
			}
			Box(
				Modifier.fillMaxWidth().pointerInput(first, range, labelWidth) {
					val labelPx = labelWidth.toPx()
					val rowPx = (ROW_HEIGHT + GAP).toPx()
					fun cellAt(o: Offset): Int {
						/* the measured rows; the nominal height only before the first layout */
						val measured = (0 until TimeGrid.WINDOW_HOURS).mapNotNull { r -> rowTops[r]?.let { r to it } }
						val row = if (measured.size == TimeGrid.WINDOW_HOURS)
							measured.firstOrNull { (_, b) -> o.y < b.second }?.first ?: (TimeGrid.WINDOW_HOURS - 1)
						else
							(o.y / rowPx).toInt().coerceIn(0, TimeGrid.WINDOW_HOURS - 1)
						val col = ((o.x - labelPx) / ((size.width - labelPx) / 12f)).toInt().coerceIn(0, 11)
						return (first + row) * 12 + col
					}
					awaitEachGesture {
						val down = awaitFirstDown()
						/* the hour labels are left to the sheet's scroll: on a short screen (landscape) that column scrolls */
						if (down.position.x < labelPx)
							return@awaitEachGesture
						down.consume()
						val anchor = cellAt(down.position)
						var last = anchor
						fun show(c: Int) {
							selection = if (range) TimeGrid.range(pending ?: anchor, c) else TimeGrid.range(c, c)
						}
						show(anchor)
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull() ?: break
							if (!change.pressed)
								break
							change.consume()
							val c = cellAt(change.position)
							if (c != last) {
								last = c
								pending = null
								selection = if (range) TimeGrid.range(anchor, c) else TimeGrid.range(c, c)
							}
						}
						/* a plain tap: first one opens the range, the second one closes it */
						if (range && last == anchor)
							pending = if (pending == null) anchor else null
						/* a start alone while the range is still open (issue #52) */
						point = allowPoint && range && last == anchor && pending != null
					}
				},
			) {
				Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
					for (h in first until first + TimeGrid.WINDOW_HOURS)
						Row(
							Modifier.onGloballyPositioned { c ->
								/* bottom edge plus half the gap: a touch between two rows goes to the nearer one */
								val top = c.positionInParent().y
								rowTops[h - first] = top to top + c.size.height + gapPx / 2
							},
							verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP),
						) {
							Text(formatMin(h * 60), fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = Modifier.width(labelWidth - GAP))
							for (k in 0 until 12) {
								val cell = h * 12 + k
								/* any overlap: a recorded 18:36-18:40 lights the 18:35 cell too */
								val chosen = sel != null && cell * 5 <= sel.last && cell * 5 + 4 >= sel.first
								val occupied = TimeGrid.busyCell(cell, busy)
								Box(
									Modifier.weight(1f).height(ROW_HEIGHT).clip(RoundedCornerShape(4.dp))
										.background(if (chosen) selColor else freeColor)
										.then(if (occupied && !chosen) Modifier.hatched(busyColor) else Modifier),
								)
							}
						}
				}
			}
			Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				if (range) {
					Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(selColor))
					Text(stringResource(R.string.grid_this), style = MaterialTheme.typography.bodySmall)
					Spacer(Modifier.width(8.dp))
				}
				if (busy.isNotEmpty()) {
					Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(freeColor).hatched(busyColor))
					Text(stringResource(R.string.grid_others), style = MaterialTheme.typography.bodySmall)
				}
			}
			Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				onExact?.let { TextButton(onClick = it) { Text(stringResource(R.string.grid_exact)) } }
				Spacer(Modifier.weight(1f))
				TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
				Button(onClick = { sel?.let { if (point) onPick(it.first, it.first) else onPick(it.first, it.last + 1) } }, enabled = sel != null) { Text(stringResource(R.string.action_ok)) }
			}
		}
	}
}

/* Diagonal stripes over a cell: time taken by other activities. */
private fun Modifier.hatched(color: Color): Modifier = drawBehind {
	clipRect {
		val step = 6.dp.toPx()
		var x = -size.height
		while (x < size.width) {
			drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 3.dp.toPx() / 2)
			x += step
		}
	}
}

/* "1:25" */
private fun formatDuration(min: Int): String = "%d:%02d".format(min / 60, min % 60)

/* The working hours, for the grid's opening window. */
class ScheduleSource(master: it.infraconsulting.activitytrack.data.repo.MasterDataRepository) : androidx.lifecycle.ViewModel() {
	val schedule = master.schedule.stateIn(
		viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList(),
	)
}
