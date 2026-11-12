/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityDisplay
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.activity.Displayed
import it.infraconsulting.activitytrack.domain.time.atMinute
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import it.infraconsulting.activitytrack.domain.todo.todoMoment
import it.infraconsulting.activitytrack.ui.common.snapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The pure logic of the Home list (ACTIVITY-MODEL.md 2.2, 8): which
 * activities are listed, in which group, in which order. The screen only
 * draws what comes out of here.
 */

/* An activity with the display names and the rows around it that the Home needs to draw it. */
data class EntryCard(
	val entry: TimeEntryEntity,
	val client: String?,
	val company: String?,
	val segments: List<SegmentEntity>,
	/* company colour for the card band (M12) */
	val companyColor: Int? = null,
	/* the activity's tags: the tag filter (issue #59), dots on every card (issue #133) */
	val tags: Set<String> = emptySet(),
	/* the last segment end: the sort key of the closed ones (issue #59) */
	val endUtcMs: Long? = null,
) {
	val title: String? get() = client ?: company

	/* latest start or end of a segment: "the most recent" activity for the big card */
	val lastChange: Long get() = segments.maxOfOrNull { maxOf(it.startUtcMs, it.endUtcMs ?: 0L) } ?: entry.updatedUtcMs

	/* the state shown (2.2) and since when */
	fun displayed(now: Instant, zone: ZoneId = ZoneId.of(entry.zoneId)): Displayed =
		ActivityDisplay.display(entry.snapshot(segments), now, zone)
}

/* When a TODO activity next asks for attention: its plan, else its due time, else its first planned segment. */
internal fun planMoment(e: TimeEntryEntity, segments: List<SegmentEntity>, zone: ZoneId): Instant? =
	todoMoment(TodoPlan.of(e.planEpochDay, e.planStartMin, e.durationMin), e.dueEpochDay?.let(LocalDate::ofEpochDay), e.dueTimeMin, zone)
		?: segments.filter { it.planned }.minOfOrNull { it.startUtcMs }?.let(Instant::ofEpochMilli)

/* Dated to-dos inside the window, the others without a date (own section). */
internal data class PlannedSplit(val dated: List<TimeEntryEntity>, val noDate: List<TimeEntryEntity>)

/*
 * The open to-dos of [company] (null = all) whose moment falls within
 * [window] hours of [now] (0 = everything); past moments sort first, so the
 * late ones stay on top. The to-dos without a plan and without a due date go
 * to their own section (owner answer 1). Used by the to-do widget.
 */
internal fun plannedSplit(open: List<TimeEntryEntity>, company: Long?, window: Int, now: Instant, zone: ZoneId): PlannedSplit {
	val ofCompany = open.filter { company == null || it.companyId == company }
	val dated = ofCompany.mapNotNull { t ->
		val m = planMoment(t, emptyList(), zone)
		if (m == null || (window != 0 && m.isAfter(now.plus(Duration.ofHours(window.toLong()))))) null else t to m
	}.sortedBy { it.second }.map { it.first }
	val noDate = ofCompany.filter { planMoment(it, emptyList(), zone) == null }
		.sortedWith(compareBy({ -it.priority }, { it.activity.orEmpty().lowercase() }))	/* 1.0: 5 the highest, 0 none */
	return PlannedSplit(dated, noDate)
}

/*
 * Where "today" starts: at the day-attribution carry-over boundary (06:00 by
 * default), so an activity closed at night belongs to the previous day as in
 * the reports — before 06:00 the working day began yesterday at 06:00; the
 * other windows count back from [now].
 */
internal fun closedBoundary(window: Int, carryOverMin: Int, now: Instant, zone: ZoneId): Instant {
	if (window != 0)
		return now.minus(Duration.ofHours(window.toLong()))
	val today = now.atZone(zone).toLocalDate()
	val start = today.atMinute(carryOverMin, zone)
	return if (start.isAfter(now)) today.minusDays(1).atMinute(carryOverMin, zone) else start
}

/*
 * What the Home list can show (AppSettings.homeShow keeps the names): the
 * TODO activities (to do, planned, late), the closed ones left incomplete
 * ("MISSING" in the stored name), the done ones. The absences are always
 * listed. The old IGNORED is gone from the Home: the bin is on the lists.
 */
enum class HomeShow { TODO, MISSING, DONE }

/* The groups of the Home list, in this order. */
enum class HomeSection { LATE, INCOMPLETE, TODAY, NEXT, EARLIER, NO_DATE }

/* One row of the Home list: an activity and the state it shows. */
internal data class HomeItem(val card: EntryCard, val displayed: Displayed) {
	val key: String get() = "entry-${card.entry.id}"
	val state: DisplayState get() = displayed.state
}

/* When a closed activity ended: its last segment, else when it was closed. */
internal fun closedAt(c: EntryCard): Long = c.endUtcMs ?: c.entry.closedUtcMs ?: c.entry.updatedUtcMs

/*
 * The Home list (owner, 2026-10-27, adapted to 1.0). [window]: -1 today, 0
 * everything, else hours; ahead for the to-dos (the late ones always shown),
 * behind for what was closed (today from the carry-over boundary, as the
 * reports). The incomplete ones are always shown: they wait for the user. A
 * tag keeps only the activities with it. Empty groups are left out.
 */
internal fun homeSections(
	todos: List<EntryCard>,
	closed: List<EntryCard>,
	absences: List<EntryCard>,
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
	val tomorrow = today.plusDays(1).atMinute(0, zone)
	val ahead: Instant? = when (window) {
		-1 -> tomorrow
		0 -> null
		else -> now.plus(Duration.ofHours(window.toLong()))
	}
	val behind: Instant = when (window) {
		-1 -> todayFrom
		0 -> Instant.EPOCH
		else -> now.minus(Duration.ofHours(window.toLong()))
	}
	fun ofCompany(c: Long?) = company == null || c == company
	val groups = HomeSection.entries.associateWith { mutableListOf<Pair<Long, HomeItem>>() }

	if (HomeShow.TODO in show)
		for (c in todos.filter { ofCompany(it.entry.companyId) && (tag == null || tag in it.tags) }) {
			val d = c.displayed(now, zone)
			val item = HomeItem(c, d)
			val m = planMoment(c.entry, c.segments, zone)
			when {
				d.state == DisplayState.LATE -> groups.getValue(HomeSection.LATE) += (m?.toEpochMilli() ?: 0L) to item
				m == null -> groups.getValue(HomeSection.NO_DATE) += (5L - c.entry.priority) to item
				ahead != null && m.isAfter(ahead) -> {}
				m.atZone(zone).toLocalDate().isAfter(today) -> groups.getValue(HomeSection.NEXT) += m.toEpochMilli() to item
				else -> groups.getValue(HomeSection.TODAY) += m.toEpochMilli() to item
			}
		}
	for (c in closed.filter { ofCompany(it.entry.companyId) && (tag == null || tag in it.tags) }) {
		val d = c.displayed(now, zone)
		val item = HomeItem(c, d)
		val end = closedAt(c)
		when {
			d.state == DisplayState.INCOMPLETE -> if (HomeShow.MISSING in show) groups.getValue(HomeSection.INCOMPLETE) += -end to item
			d.state != DisplayState.DONE || HomeShow.DONE !in show || end < behind.toEpochMilli() -> {}
			/* after today's to-dos: the most recent first */
			end >= todayFrom.toEpochMilli() -> groups.getValue(HomeSection.TODAY) += (Long.MAX_VALUE - end) to item
			else -> groups.getValue(HomeSection.EARLIER) += -end to item
		}
	}
	/* an absence of no company counts for every one */
	if (tag == null)
		for (c in absences.filter { company == null || it.entry.companyId == null || it.entry.companyId == company }) {
			val real = c.segments.filter { !it.planned }
			val start = real.minOfOrNull { it.startUtcMs } ?: continue
			val end = real.maxOf { it.endUtcMs ?: it.startUtcMs }
			if (end <= behind.toEpochMilli() || (ahead != null && start >= ahead.toEpochMilli()))
				continue
			val item = HomeItem(c, c.displayed(now, zone))
			when {
				start >= tomorrow.toEpochMilli() -> groups.getValue(HomeSection.NEXT) += start to item
				end > todayFrom.toEpochMilli() -> groups.getValue(HomeSection.TODAY) += (Long.MIN_VALUE / 2 + start) to item
				else -> groups.getValue(HomeSection.EARLIER) += -end to item
			}
		}

	return HomeSection.entries.mapNotNull { s ->
		groups.getValue(s).takeIf { it.isNotEmpty() }?.sortedBy { it.first }?.map { it.second }?.let { s to it }
	}
}
