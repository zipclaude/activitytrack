/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.settings.GestureSettings
import it.infraconsulting.activitytrack.domain.settings.Gestures
import it.infraconsulting.activitytrack.domain.settings.PastSwipe
import it.infraconsulting.activitytrack.domain.settings.TodoAction
import it.infraconsulting.activitytrack.ui.common.BLUE
import it.infraconsulting.activitytrack.ui.common.Banded
import it.infraconsulting.activitytrack.ui.common.GREEN
import it.infraconsulting.activitytrack.ui.common.ORANGE
import it.infraconsulting.activitytrack.ui.common.SettingLock
import it.infraconsulting.activitytrack.ui.common.SwipeAction
import it.infraconsulting.activitytrack.ui.common.SwipeCard
import it.infraconsulting.activitytrack.ui.common.TagDots
import it.infraconsulting.activitytrack.ui.common.TagFilterChip
import it.infraconsulting.activitytrack.ui.common.displayName
import it.infraconsulting.activitytrack.ui.common.displayStyle
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.prefix
import it.infraconsulting.activitytrack.ui.common.rgb
import it.infraconsulting.activitytrack.ui.common.settingLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * "[Periodo ▾] [Stato ▾] [Azienda ▾] [#tag ▾]" over the Home list (owner,
 * 2026-10-27): the state filter is a set of check boxes with three
 * shortcuts; the tag filter only while closed activities are shown.
 */
@Composable
internal fun HomeListHeader(
	show: Set<HomeShow>,
	offered: List<HomeShow>,
	onShow: (Set<HomeShow>) -> Unit,
	window: Int,
	onWindow: (Int) -> Unit,
	companies: List<CompanyEntity>,
	company: Long?,
	onCompany: (Long?) -> Unit,
	filterEntries: Boolean,
	onFilterEntries: (Boolean) -> Unit,
	tag: String?,
	tags: List<String>,
	onTag: (String?) -> Unit,
) {
	val windows = listOf(
		-1 to stringResource(R.string.day_today),
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
		PeriodChip(window, windows, onWindow, settingLock("homeWindow"))
		StateChip(show, offered, onShow, settingLock("homeShow"))
		CompanyChip(companies, company, onCompany, filterEntries, onFilterEntries)
		if (HomeShow.MISSING in show || HomeShow.DONE in show)
			TagFilterChip(tags, tag, label = stringResource(R.string.home_filter_tag), onSelect = onTag)
	}
}

/* the period, always in sight: its value, grey */
@Composable
private fun PeriodChip(window: Int, labels: List<Pair<Int, String>>, onPick: (Int) -> Unit, lock: SettingLock) {
	if (lock == SettingLock.HIDDEN)
		return
	var open by remember { mutableStateOf(false) }
	Box {
		FilterChip(
			selected = false, onClick = { open = true }, enabled = lock == SettingLock.FREE,
			label = { Text(lock.prefix() + (labels.firstOrNull { it.first == window }?.second ?: "") + " ▾") },
		)
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			for ((w, label) in labels)
				DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(w) })
		}
	}
}

private val DEFAULT_SHOW = setOf(HomeShow.TODO, HomeShow.MISSING)

private fun showLabel(s: HomeShow): Int = when (s) {
	HomeShow.TODO -> R.string.home_show_todo
	HomeShow.MISSING -> R.string.state_incomplete
	HomeShow.DONE -> R.string.home_tab_closed
}

@Composable
private fun StateChip(show: Set<HomeShow>, offered: List<HomeShow>, onShow: (Set<HomeShow>) -> Unit, lock: SettingLock) {
	if (lock == SettingLock.HIDDEN)
		return
	var open by remember { mutableStateOf(false) }
	/* coloured when it differs from the default (to-dos and incomplete ones) */
	val changed = show != DEFAULT_SHOW.intersect(offered.toSet())
	Box {
		FilterChip(
			selected = changed, onClick = { open = true }, enabled = lock == SettingLock.FREE,
			label = { Text(lock.prefix() + stringResource(R.string.home_filter_state) + " ▾", maxLines = 1) },
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

/* the company filter (M12): "All companies" or one; on Home it may hide the running activities too (issue #44) */
@Composable
private fun CompanyChip(
	companies: List<CompanyEntity>,
	selected: Long?,
	onPick: (Long?) -> Unit,
	filterEntries: Boolean,
	onFilterEntries: (Boolean) -> Unit,
) {
	val current = companies.firstOrNull { it.id == selected }
	var open by remember { mutableStateOf(false) }
	Box {
		FilterChip(
			selected = current != null, onClick = { open = true },
			label = { Text(stringResource(R.string.home_filter_company) + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) },
		)
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			DropdownMenuItem(text = { Text(stringResource(R.string.all_companies)) }, onClick = { open = false; onPick(null) })
			val lock = settingLock("homeFilterEntries")
			if (lock != SettingLock.HIDDEN)
				DropdownMenuItem(
					enabled = lock == SettingLock.FREE,
					text = { Text(stringResource(R.string.home_filter_entries)) },
					leadingIcon = { Checkbox(checked = filterEntries, onCheckedChange = null) },
					onClick = { open = false; onFilterEntries(!filterEntries) },
				)
			for (c in companies.filter { !it.archived })
				DropdownMenuItem(
					text = { Text(c.name) },
					leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(c.color?.let(::rgb) ?: Color.Transparent)) },
					onClick = { open = false; onPick(c.id) },
				)
		}
	}
}

@Composable
internal fun sectionLabel(s: HomeSection): String = stringResource(
	when (s) {
		HomeSection.LATE -> R.string.state_late
		HomeSection.INCOMPLETE -> R.string.state_incomplete
		HomeSection.TODAY -> R.string.day_today
		HomeSection.NEXT -> R.string.home_sec_next
		HomeSection.EARLIER -> R.string.home_sec_earlier
		HomeSection.NO_DATE -> R.string.home_no_date
	},
)

/* What a row's swipes do; the screen wires them to the repository. A tap opens the detail. */
internal class RowActions(
	val open: (Long) -> Unit,
	val startTodo: (Long) -> Unit,
	val tick: (TimeEntryEntity) -> Unit,
	val postpone: (TimeEntryEntity) -> Unit,
	val delete: (TimeEntryEntity) -> Unit,
	val reopen: (TimeEntryEntity) -> Unit,
)

/* the "postpone" step in minutes (AppSettings.planSnoozeMin), for the label of that swipe */
internal val LocalSnooze = compositionLocalOf { 30 }

/*
 * One row of the Home list, the same shape for every state (owner,
 * 2026-10-27): the state's icon and colour, the title, "detail · state since
 * ...", the time on the right; what is done is dimmed. The swipes follow one
 * rule (GestureSettings): toward the start the step forward, toward the end
 * put off or go back.
 */
@Composable
internal fun HomeListRow(item: HomeItem, gestures: GestureSettings, canDelete: Boolean, a: RowActions) {
	val c = item.card
	val e = c.entry
	val state = item.state
	val zone = ZoneId.of(e.zoneId)
	val style = displayStyle(state)
	val todo = state == DisplayState.TODO || state == DisplayState.PLANNED || state == DisplayState.LATE
	val title = when {
		todo -> e.activity?.takeIf { it.isNotBlank() } ?: c.title
		e.kind == ActivityKind.ABSENCE -> e.notes?.takeIf { it.isNotBlank() } ?: stringResource(R.string.state_absence)
		else -> c.title
	} ?: stringResource(R.string.entry_no_client)
	val detail = when {
		todo -> if (e.activity.isNullOrBlank()) null else c.title
		e.kind == ActivityKind.ABSENCE -> c.company
		else -> e.activity
	}
	val since = item.displayed.since?.let { sinceText(it, zone) }
	val stateText = stringResource(displayName(state)).let { if (since != null) stringResource(R.string.since_fmt, it, since) else it }
	val subtitle = listOfNotNull(detail, stateText, e.priority.takeIf { todo && it > 0 }?.let { "P$it" }).joinToString(" · ")
	val alert = state == DisplayState.LATE || state == DisplayState.INCOMPLETE
	val time = rowTime(item, zone)
	SwipeCard(
		toStart = rowSwipes(item, true, gestures, canDelete, a),
		toEnd = rowSwipes(item, false, gestures, canDelete, a),
		fullSwipe = gestures.fullSwipe,
		shape = CardDefaults.shape,
		modifier = Modifier.padding(horizontal = 16.dp),
	) {
		Card(onClick = { a.open(e.id) }, modifier = Modifier.fillMaxWidth().alpha(if (state == DisplayState.DONE) 0.75f else 1f)) {
			Banded(c.companyColor) {
				Row(
					Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					Box(Modifier.size(28.dp).clip(CircleShape).background(style.container), contentAlignment = Alignment.Center) {
						Icon(painterResource(style.icon), contentDescription = null, tint = style.content, modifier = Modifier.size(18.dp))
					}
					Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
						Text(
							title, style = MaterialTheme.typography.titleSmall,
							fontWeight = if (state == DisplayState.DONE) FontWeight.Normal else FontWeight.SemiBold,
							maxLines = 1, overflow = TextOverflow.Ellipsis,
						)
						Text(
							subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
							color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
						TagDots(c.tags)
						time?.let {
							Text(it, style = MaterialTheme.typography.bodySmall, color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
						}
					}
				}
			}
		}
	}
}

/* The buttons a swipe toward the start ([start]) or the end of the row reveals, per state. */
@Composable
private fun rowSwipes(item: HomeItem, start: Boolean, g: GestureSettings, canDelete: Boolean, a: RowActions): List<SwipeAction> {
	val e = item.card.entry
	val grey = MaterialTheme.colorScheme.outline
	val snooze = LocalSnooze.current
	return when (item.state) {
		DisplayState.TODO, DisplayState.PLANNED, DisplayState.LATE ->
			Gestures.todoActions(if (start) g.todoToStart else g.todoToEnd, ignored = false, planned = e.plan() != null, done = false)
				.filter { canDelete || it != TodoAction.IGNORE }
				.mapNotNull { act ->
					when (act) {
						TodoAction.START -> SwipeAction(stringResource(R.string.swipe_start), R.drawable.ic_cmd_play, GREEN, Color.White) { a.startTodo(e.id) }
						TodoAction.POSTPONE -> SwipeAction(stringResource(R.string.action_postpone, snooze), R.drawable.ic_clock, BLUE, Color.White) { a.postpone(e) }
						TodoAction.IGNORE -> SwipeAction(stringResource(R.string.action_delete), R.drawable.ic_ignore, grey, Color.White) { a.delete(e) }
						TodoAction.DONE -> SwipeAction(stringResource(R.string.todo_mark_done), R.drawable.ic_done, GREEN, Color.White) { a.tick(e) }
						TodoAction.REPLAN -> SwipeAction(stringResource(R.string.action_replan), R.drawable.ic_calendar, BLUE, Color.White) { a.open(e.id) }
						TodoAction.RESTORE, TodoAction.REOPEN -> null
					}
				}
		DisplayState.INCOMPLETE, DisplayState.DONE -> {
			val swipe = if (start) g.pastToStart else g.pastToEnd
			when {
				/* "complete" is the details: no command in 1.0, the detail screen opens */
				swipe == PastSwipe.COMPLETE && item.state == DisplayState.INCOMPLETE ->
					listOf(SwipeAction(stringResource(R.string.cmd_complete), R.drawable.ic_done, GREEN, Color.White) { a.open(e.id) })
				/* a task ticked off has no segment to go on from */
				swipe == PastSwipe.REOPEN && item.card.segments.any { !it.planned } ->
					listOf(SwipeAction(stringResource(R.string.cmd_reopen), R.drawable.ic_restore, ORANGE, Color.White) { a.reopen(e) })
				else -> emptyList()
			}
		}
		else -> emptyList()
	}
}

/* "10:30" today, else "lun 26/10 10:30": since when the state holds */
@Composable
private fun sinceText(at: Instant, zone: ZoneId): String {
	val t = at.atZone(zone)
	val clock = formatMin(t.hour * 60 + t.minute)
	return if (t.toLocalDate() == LocalDate.now(zone)) clock else dayLabel(t.toLocalDate()) + " " + clock
}

/* "oggi", else a short local date ("sab 17/10") */
@Composable
internal fun dayLabel(date: LocalDate): String =
	if (date == LocalDate.now()) stringResource(R.string.todo_today)
	else date.format(DateTimeFormatter.ofPattern("EEE d/MM", LocalConfiguration.current.locales[0]))

private fun clock(at: java.time.ZonedDateTime) = formatMin(at.hour * 60 + at.minute)

/*
 * The time at the row's right: a to-do's planned block or due time, a closed
 * activity's span ("08:30–09:40", the day when not today), an absence's days.
 */
@Composable
private fun rowTime(item: HomeItem, zone: ZoneId): String? {
	val c = item.card
	val e = c.entry
	val real = c.segments.filter { !it.planned }
	return when (item.state) {
		DisplayState.TODO, DisplayState.PLANNED, DisplayState.LATE -> {
			val today = LocalDate.now(zone)
			val p = e.plan()
			if (p != null) {
				val time = p.startMin?.let { s ->
					formatMin(s) + (p.durationMin?.let { _ -> "–" + clock(p.end(zone).atZone(zone)) } ?: "")
				}
				when {
					time == null -> dayLabel(p.day)
					p.day == today -> time
					else -> "${dayLabel(p.day)} $time"
				}
			} else e.dueEpochDay?.let(LocalDate::ofEpochDay)?.let { due ->
				val text = (if (due == today) "" else dayLabel(due) + " ") + (e.dueTimeMin?.let(::formatMin) ?: "")
				stringResource(R.string.todo_due_at, text.trim())
			}
		}
		DisplayState.ABSENCE -> {
			val from = real.minOfOrNull { it.startUtcMs }?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return null
			val to = Instant.ofEpochMilli(real.maxOf { it.endUtcMs ?: it.startUtcMs }).atZone(zone)
			val lastDay = to.minusNanos(1).toLocalDate()
			when {
				from.toLocalDate() != lastDay -> dayLabel(from.toLocalDate()) + "–" + dayLabel(lastDay)
				/* a whole day: no times */
				from.toLocalTime().toSecondOfDay() == 0 && to.toLocalTime().toSecondOfDay() == 0 -> dayLabel(from.toLocalDate())
				else -> dayLabel(from.toLocalDate()) + " " + clock(from) + "–" + clock(to)
			}
		}
		else -> {
			val start = real.minOfOrNull { it.startUtcMs } ?: return null
			val end = c.endUtcMs
			fun hm(ms: Long) = clock(Instant.ofEpochMilli(ms).atZone(zone))
			val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
			val span = hm(start) + "–" + (end?.let(::hm) ?: "…")
			if (day == LocalDate.now(zone)) span else dayLabel(day) + " " + span
		}
	}
}
