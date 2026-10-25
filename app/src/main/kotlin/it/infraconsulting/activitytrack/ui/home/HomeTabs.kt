/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.domain.time.atMinute
import it.infraconsulting.activitytrack.ui.common.TagFilterChip
import it.infraconsulting.activitytrack.ui.todo.CompanyFilter
import it.infraconsulting.activitytrack.ui.todo.TodosViewModel
import it.infraconsulting.activitytrack.ui.todo.moment
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/* The two Home tabs (issue #59): to-dos ahead, entries and done to-dos behind. */
enum class HomeTab {
	PLANNED, CLOSED;

	companion object {
		/* the stored setting falls back to the planned tab */
		fun of(stored: String?): HomeTab = entries.firstOrNull { it.name == stored } ?: PLANNED
	}
}

/* Dated to-dos inside the window, the others without a date (own section). */
internal data class PlannedSplit(val dated: List<TodoEntity>, val noDate: List<TodoEntity>)

/*
 * The "Pianificate" list (issue #59): the open to-dos of [company] (null =
 * all) whose moment falls within [window] hours of [now] (0 = everything);
 * past moments sort first, so the overdue ones stay on top. The to-dos
 * without a plan and without a due date go to their own bottom section
 * (owner answer 1).
 */
internal fun plannedSplit(open: List<TodoEntity>, company: Long?, window: Int, now: Instant, zone: ZoneId): PlannedSplit {
	val ofCompany = open.filter { company == null || it.companyId == company }
	val dated = ofCompany.mapNotNull { t ->
		val m = moment(t, zone)
		if (m == null || (window != 0 && m.isAfter(now.plus(Duration.ofHours(window.toLong()))))) null else t to m
	}.sortedBy { it.second }.map { it.first }
	val noDate = ofCompany.filter { moment(it, zone) == null }
		.sortedWith(compareBy({ it.priority ?: Int.MAX_VALUE }, { it.title.lowercase() }))
	return PlannedSplit(dated, noDate)
}

/*
 * Where the "Chiuse" window starts (issue #59): "today" begins at the
 * day-attribution carry-over boundary (06:00 by default), so an entry closed
 * at night belongs to the previous day as in the reports; the other windows
 * count back from [now].
 */
internal fun closedBoundary(window: Int, carryOverMin: Int, now: Instant, zone: ZoneId): Instant =
	if (window == 0) now.atZone(zone).toLocalDate().atMinute(carryOverMin, zone)
	else now.minus(Duration.ofHours(window.toLong()))

/* One row of the "Chiuse" tab: an entry that stopped being worked on, or a done to-do. */
internal sealed interface ClosedRow {
	val sortMs: Long

	data class Entry(val card: EntryCard) : ClosedRow {
		override val sortMs: Long get() = card.endUtcMs ?: card.entry.updatedUtcMs
	}

	data class Todo(val todo: TodoEntity, val client: String?) : ClosedRow {
		override val sortMs: Long get() = todo.updatedUtcMs
	}
}

/*
 * "[Pianificate n | Chiuse] [window ▾] [company] [#tag ▾]" (issue #59;
 * browser-like tabs since #72, a plain text row before): the tabs switch
 * by tapping or swiping this header horizontally (the card swipes below
 * stay untouched); the count of the list shown sits beside the active
 * label. The window and the filters act on the list shown — ahead for
 * the planned tab, behind for the closed one. The window labels come in
 * display order from the caller.
 */
@Composable
internal fun HomeTabsHeader(
	tab: HomeTab,
	showPlanned: Boolean,
	count: Int,
	windowHours: Int,
	windowLabels: List<Pair<Int, String>>,
	onWindow: (Int) -> Unit,
	todosVm: TodosViewModel,
	showTagFilter: Boolean,
	tag: String?,
	tags: List<String>,
	onTag: (String?) -> Unit,
	onTab: (HomeTab) -> Unit,
) {
	val tabs = if (showPlanned) HomeTab.entries.toList() else listOf(HomeTab.CLOSED)
	val threshold = with(LocalDensity.current) { 40.dp.toPx() }
	val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
	var drag by remember { mutableFloatStateOf(0f) }
	Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		/* browser-like tabs (issue #72; a plain text row before): the count
		 * of the list shown sits beside the active label */
		TabRow(
			selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0),
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 6.dp)
				.pointerInput(tabs) {
					detectHorizontalDragGestures(
						onHorizontalDrag = { _, amount -> drag += amount },
						onDragEnd = {
							if (abs(drag) > threshold) {
								val forward = if (rtl) drag > 0f else drag < 0f
								val i = tabs.indexOf(tab)
								if (forward && i < tabs.size - 1) onTab(tabs[i + 1])
								else if (!forward && i > 0) onTab(tabs[i - 1])
							}
							drag = 0f
						},
					)
				},
		) {
			for (t in tabs) {
				Tab(
					selected = t == tab,
					onClick = { onTab(t) },
					text = {
						Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							Text(stringResource(if (t == HomeTab.PLANNED) R.string.home_tab_planned else R.string.home_tab_closed))
							if (t == tab && count > 0)
								Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
					},
				)
			}
		}
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			WindowChip(windowHours, windowLabels, onWindow)
			CompanyFilter(todosVm, onHome = true)
			if (showTagFilter)
				TagFilterChip(tags, tag, onTag)
		}
	}
}

/* The window picker of the tab shown: "24 h ▾", "Oggi ▾", ... */
@Composable
private fun WindowChip(hours: Int, labels: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box {
		FilterChip(selected = true, onClick = { open = true }, label = { Text((labels.firstOrNull { it.first == hours }?.second ?: "") + " ▾") })
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			for ((h, label) in labels)
				DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(h) })
		}
	}
}
