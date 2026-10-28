/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import it.infraconsulting.activitytrack.ui.todo.todoState
import it.infraconsulting.activitytrack.ui.todo.TodoState
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
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
 * at night belongs to the previous day as in the reports — before 06:00 the
 * working day began yesterday at 06:00; the other windows count back from
 * [now].
 */
internal fun closedBoundary(window: Int, carryOverMin: Int, now: Instant, zone: ZoneId): Instant {
	if (window != 0)
		return now.minus(Duration.ofHours(window.toLong()))
	val today = now.atZone(zone).toLocalDate()
	val start = today.atMinute(carryOverMin, zone)
	return if (start.isAfter(now)) today.minusDays(1).atMinute(carryOverMin, zone) else start
}

/* What the Home list can show (owner, 2026-10-27: one list with a state filter, AppSettings.homeShow). */
enum class HomeShow { TODO, MISSING, DONE, IGNORED }

/* The groups of the Home list, in this order. */
enum class HomeSection { OVERDUE, MISSING, TODAY, NEXT, EARLIER, NO_DATE, IGNORED }

/* One row of the Home list: a to-do or a stopped entry, drawn alike. */
internal sealed interface HomeItem {
	val key: String

	data class Todo(val todo: TodoEntity) : HomeItem {
		override val key get() = "todo-${todo.id}"
	}

	data class Entry(val card: EntryCard) : HomeItem {
		override val key get() = "entry-${card.entry.id}"
	}
}

/*
 * The Home list (owner, 2026-10-27). [window]: -1 today, 0 everything, else
 * hours; ahead for the open to-dos (the overdue always shown), behind for
 * what was done or ignored (today from the carry-over boundary, as the
 * reports). Entries with details missing are always shown: they wait for
 * the user. A tag keeps only the entries with it. Empty groups are left out.
 */
internal fun homeSections(
	open: List<TodoEntity>,
	ignored: List<TodoEntity>,
	done: List<TodoEntity>,
	stopped: List<EntryCard>,
	show: Set<HomeShow>,
	company: Long?,
	tag: String?,
	window: Int,
	carryOverMin: Int,
	now: Instant,
	zone: ZoneId,
): List<Pair<HomeSection, List<HomeItem>>> {
	val today = now.atZone(zone).toLocalDate()
	val todayFrom = closedBoundary(0, carryOverMin, now, zone)
	val ahead: Instant? = when (window) {
		-1 -> today.plusDays(1).atStartOfDay(zone).toInstant()
		0 -> null
		else -> now.plus(Duration.ofHours(window.toLong()))
	}
	val behind: Instant = when (window) {
		-1 -> todayFrom
		0 -> Instant.EPOCH
		else -> now.minus(Duration.ofHours(window.toLong()))
	}
	fun ofCompany(c: Long?) = company == null || c == company
	val todos = tag == null
	val groups = HomeSection.entries.associateWith { mutableListOf<Pair<Long, HomeItem>>() }

	if (HomeShow.TODO in show && todos)
		for (t in open.filter { ofCompany(it.companyId) }) {
			val m = moment(t, zone)
			when {
				todoState(t, now) == TodoState.OVERDUE ->
					groups.getValue(HomeSection.OVERDUE) += (m?.toEpochMilli() ?: 0L) to HomeItem.Todo(t)
				m == null -> groups.getValue(HomeSection.NO_DATE) += (t.priority?.toLong() ?: Long.MAX_VALUE) to HomeItem.Todo(t)
				ahead != null && m.isAfter(ahead) -> {}
				m.atZone(zone).toLocalDate().isAfter(today) -> groups.getValue(HomeSection.NEXT) += m.toEpochMilli() to HomeItem.Todo(t)
				else -> groups.getValue(HomeSection.TODAY) += m.toEpochMilli() to HomeItem.Todo(t)
			}
		}
	for (c in stopped.filter { ofCompany(it.entry.companyId) && (tag == null || tag in it.tags) }) {
		val end = c.endUtcMs ?: c.entry.updatedUtcMs
		val missing = c.entry.status == EntryStatus.TO_COMPLETE
		when {
			missing -> if (HomeShow.MISSING in show) groups.getValue(HomeSection.MISSING) += -end to HomeItem.Entry(c)
			HomeShow.DONE !in show || end < behind.toEpochMilli() -> {}
			/* after today's to-dos: the most recent first */
			end >= todayFrom.toEpochMilli() -> groups.getValue(HomeSection.TODAY) += (Long.MAX_VALUE - end) to HomeItem.Entry(c)
			else -> groups.getValue(HomeSection.EARLIER) += -end to HomeItem.Entry(c)
		}
	}
	if (HomeShow.DONE in show && todos)
		for (t in done.filter { ofCompany(it.companyId) }) {
			val at = t.doneUtcMs ?: t.updatedUtcMs
			when {
				at < behind.toEpochMilli() -> {}
				at >= todayFrom.toEpochMilli() -> groups.getValue(HomeSection.TODAY) += (Long.MAX_VALUE - at) to HomeItem.Todo(t)
				else -> groups.getValue(HomeSection.EARLIER) += -at to HomeItem.Todo(t)
			}
		}
	if (HomeShow.IGNORED in show && todos)
		for (t in ignored.filter { ofCompany(it.companyId) && (it.ignoredUtcMs ?: 0L) >= behind.toEpochMilli() })
			groups.getValue(HomeSection.IGNORED) += -(t.ignoredUtcMs ?: 0L) to HomeItem.Todo(t)

	return HomeSection.entries.mapNotNull { s ->
		groups.getValue(s).takeIf { it.isNotEmpty() }?.sortedBy { it.first }?.map { it.second }?.let { s to it }
	}
}
