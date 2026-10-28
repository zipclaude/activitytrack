/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.settings.GestureSettings
import it.infraconsulting.activitytrack.domain.settings.Gestures
import it.infraconsulting.activitytrack.ui.common.Banded
import it.infraconsulting.activitytrack.ui.common.GREEN
import it.infraconsulting.activitytrack.ui.common.ORANGE
import it.infraconsulting.activitytrack.ui.common.SwipeAction
import it.infraconsulting.activitytrack.ui.common.SwipeCard
import it.infraconsulting.activitytrack.ui.common.TagFilterChip
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.todo.CompanyFilter
import it.infraconsulting.activitytrack.ui.todo.TodoState
import it.infraconsulting.activitytrack.ui.todo.TodosViewModel
import it.infraconsulting.activitytrack.ui.todo.cardTime
import it.infraconsulting.activitytrack.ui.todo.dayText
import it.infraconsulting.activitytrack.ui.todo.todoState
import it.infraconsulting.activitytrack.ui.todo.todoSwipeActions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * "[Stato ▾] [Oggi ▾] [company] [#tag ▾]" over the Home list (owner,
 * 2026-10-27): the state filter is a set of check boxes with three
 * shortcuts; the tag filter only while stopped entries are shown.
 */
@Composable
internal fun HomeListHeader(
	show: Set<HomeShow>,
	offered: List<HomeShow>,
	onShow: (Set<HomeShow>) -> Unit,
	window: Int,
	onWindow: (Int) -> Unit,
	todosVm: TodosViewModel,
	tag: String?,
	tags: List<String>,
	onTag: (String?) -> Unit,
) {
	val windows = listOf(
		-1 to stringResource(R.string.home_window_today),
		24 to stringResource(R.string.todo_window_24h),
		72 to stringResource(R.string.todo_window_3d),
		168 to stringResource(R.string.todo_window_7d),
		720 to stringResource(R.string.todo_window_30d),
		0 to stringResource(R.string.todo_window_all),
	)
	/* one row (owner, 2026-10-28): the period shows its value, the others their name, coloured while set */
	Row(
		Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 6.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		PeriodChip(window, windows, onWindow)
		StateChip(show, offered, onShow)
		CompanyFilter(todosVm, onHome = true, label = stringResource(R.string.home_filter_company))
		if (HomeShow.MISSING in show || HomeShow.DONE in show)
			TagFilterChip(tags, tag, label = stringResource(R.string.home_filter_tag), onSelect = onTag)
	}
}

/* the period, always in sight: its value, grey */
@Composable
private fun PeriodChip(window: Int, labels: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box {
		FilterChip(selected = false, onClick = { open = true }, label = { Text((labels.firstOrNull { it.first == window }?.second ?: "") + " ▾") })
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			for ((w, label) in labels)
				DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(w) })
		}
	}
}

private val DEFAULT_SHOW = setOf(HomeShow.TODO, HomeShow.MISSING)

private fun showLabel(s: HomeShow): Int = when (s) {
	HomeShow.TODO -> R.string.home_show_todo
	HomeShow.MISSING -> R.string.status_to_complete
	HomeShow.DONE -> R.string.home_tab_closed
	HomeShow.IGNORED -> R.string.home_show_ignored
}

@Composable
private fun StateChip(show: Set<HomeShow>, offered: List<HomeShow>, onShow: (Set<HomeShow>) -> Unit) {
	var open by remember { mutableStateOf(false) }
	/* coloured when it differs from the default (to-dos and details missing) */
	val changed = show != DEFAULT_SHOW.intersect(offered.toSet())
	Box {
		FilterChip(
			selected = changed, onClick = { open = true },
			label = { Text(stringResource(R.string.home_filter_state) + " ▾", maxLines = 1) },
		)
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			for (s in offered)
				DropdownMenuItem(
					text = { Text(stringResource(showLabel(s))) },
					leadingIcon = { Checkbox(checked = s in show, onCheckedChange = null) },
					onClick = { onShow(if (s in show) show - s else show + s) },
				)
			DropdownMenuItem(text = { Text(stringResource(R.string.home_show_only_todo)) }, onClick = { open = false; onShow(setOf(HomeShow.TODO)) })
			DropdownMenuItem(text = { Text(stringResource(R.string.home_show_only_done)) }, onClick = { open = false; onShow(setOf(HomeShow.DONE)) })
			DropdownMenuItem(text = { Text(stringResource(R.string.home_show_all)) }, onClick = { open = false; onShow(offered.toSet()) })
		}
	}
}

@Composable
internal fun sectionLabel(s: HomeSection): String = stringResource(
	when (s) {
		HomeSection.OVERDUE -> R.string.home_sec_overdue
		HomeSection.MISSING -> R.string.status_to_complete
		HomeSection.TODAY -> R.string.home_window_today
		HomeSection.NEXT -> R.string.home_sec_next
		HomeSection.EARLIER -> R.string.home_sec_earlier
		HomeSection.NO_DATE -> R.string.home_no_date
		HomeSection.IGNORED -> R.string.home_show_ignored
	},
)

/*
 * One row of the Home list, the same shape for to-dos and stopped entries
 * (owner, 2026-10-27): a state mark, the title, "client · kind · state",
 * the time on the right; what is over is dimmed. The swipes follow one rule
 * (GestureSettings): toward the start the step forward, toward the end put
 * off or go back.
 */
@Composable
internal fun HomeListRow(
	item: HomeItem,
	clientOf: (Long?) -> String?,
	band: Int?,
	gestures: GestureSettings,
	todosVm: TodosViewModel,
	onStartTodo: (Long) -> Unit,
	onReplan: (TodoEntity) -> Unit,
	onTodo: (TodoEntity) -> Unit,
	onEntry: (Long) -> Unit,
	onReopen: (EntryCard) -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	when (item) {
		is HomeItem.Todo -> {
			val t = item.todo
			val state = todoState(t)
			val (mark, markBg, markFg) = when (state) {
				TodoState.OVERDUE -> Triple("!", cs.errorContainer, cs.onErrorContainer)
				TodoState.PLANNED -> Triple(PLANNED_MARK, cs.secondaryContainer, cs.onSecondaryContainer)
				TodoState.DONE -> Triple("✓", cs.surfaceVariant, cs.onSurfaceVariant)
				TodoState.IGNORED -> Triple("–", cs.surfaceVariant, cs.onSurfaceVariant)
				TodoState.OPEN -> Triple("○", Color.Transparent, cs.onSurfaceVariant)
			}
			val stateText = stringResource(
				when (state) {
					TodoState.OVERDUE -> R.string.todo_overdue
					TodoState.PLANNED -> R.string.todo_planned_chip
					TodoState.DONE -> R.string.todo_done_chip
					TodoState.IGNORED -> R.string.todo_ignored
					TodoState.OPEN -> R.string.home_state_open
				},
			)
			val subtitle = listOfNotNull(clientOf(t.clientId), stringResource(R.string.todo_title), stateText, t.priority?.let { "P$it" }.takeIf { state != TodoState.DONE })
				.joinToString(" · ")
			SwipeCard(
				toStart = todoSwipeActions(t, gestures.todoToStart, todosVm, todosVm.settings.value?.planSnoozeMin ?: 30, { onStartTodo(it) }, onReplan),
				toEnd = todoSwipeActions(t, gestures.todoToEnd, todosVm, todosVm.settings.value?.planSnoozeMin ?: 30, { onStartTodo(it) }, onReplan),
				fullSwipe = gestures.fullSwipe,
				shape = CardDefaults.shape,
				modifier = Modifier.padding(horizontal = 16.dp),
			) {
				RowCard(
					mark, markBg, markFg, t.title, subtitle, cardTime(t),
					band = band, dim = state == TodoState.DONE || state == TodoState.IGNORED,
					strike = state == TodoState.IGNORED, alert = state == TodoState.OVERDUE,
				) { onTodo(t) }
			}
		}
		is HomeItem.Entry -> {
			val c = item.card
			val e = c.entry
			val missing = e.status == EntryStatus.TO_COMPLETE
			val subtitle = listOfNotNull(
				e.activity, stringResource(R.string.home_kind_entry),
				stringResource(if (missing) R.string.status_to_complete else R.string.status_closed),
			).joinToString(" · ")
			val complete = stringResource(R.string.cmd_complete)
			val reopen = stringResource(R.string.cmd_reopen)
			fun actions(swipe: it.infraconsulting.activitytrack.domain.settings.PastSwipe) = Gestures.pastCommands(swipe, e.status).map { cmd ->
				if (cmd == EntryCommand.COMPLETE)
					SwipeAction(complete, R.drawable.ic_done, GREEN, Color.White) { onEntry(e.id) }
				else
					SwipeAction(reopen, R.drawable.ic_restore, ORANGE, Color.White) { onReopen(c) }
			}
			SwipeCard(
				toStart = actions(gestures.pastToStart),
				toEnd = actions(gestures.pastToEnd),
				fullSwipe = gestures.fullSwipe,
				shape = CardDefaults.shape,
				modifier = Modifier.padding(horizontal = 16.dp),
			) {
				RowCard(
					if (missing) "✎" else "■",
					if (missing) cs.errorContainer else cs.surfaceVariant,
					if (missing) cs.onErrorContainer else cs.onSurfaceVariant,
					c.title ?: stringResource(R.string.entry_no_client), subtitle, entrySpan(c),
					band = c.companyColor, dim = !missing, strike = false, alert = missing,
				) { onEntry(e.id) }
			}
		}
	}
}

private const val PLANNED_MARK = "◷"

/* "08:30–09:40", with the day when not today: the span of a stopped entry. */
@Composable
private fun entrySpan(c: EntryCard): String? {
	val start = c.segments.minOfOrNull { it.startUtcMs } ?: return null
	val end = c.endUtcMs ?: return null
	val zone = ZoneId.of(c.entry.zoneId)
	fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { formatMin(it.hour * 60 + it.minute) }
	val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
	val span = hm(start) + "–" + hm(end)
	return if (day == LocalDate.now()) span else dayText(day.toEpochDay()) + " " + span
}

@Composable
private fun RowCard(
	mark: String,
	markBg: Color,
	markFg: Color,
	title: String,
	subtitle: String,
	time: String?,
	band: Int?,
	dim: Boolean,
	strike: Boolean,
	alert: Boolean,
	onClick: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	Card(onClick = onClick, modifier = Modifier.fillMaxWidth().alpha(if (dim) 0.75f else 1f)) {
		Banded(band) {
			Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				Box(
					Modifier.size(28.dp).clip(CircleShape).background(markBg)
						.then(if (markBg == Color.Transparent) Modifier.background(Color.Transparent) else Modifier),
					contentAlignment = Alignment.Center,
				) {
					/* the planned mark is the app's clock; the others read as text */
					if (mark == PLANNED_MARK)
						androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_clock), contentDescription = null, tint = markFg, modifier = Modifier.size(18.dp))
					else
						Text(mark, color = markFg, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
				}
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						title, style = MaterialTheme.typography.titleSmall, fontWeight = if (dim) FontWeight.Normal else FontWeight.SemiBold,
						maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (strike) TextDecoration.LineThrough else null,
					)
					Text(
						subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
						color = if (alert) cs.error else cs.onSurfaceVariant,
					)
				}
				time?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (alert) cs.error else cs.onSurface) }
			}
		}
	}
}
