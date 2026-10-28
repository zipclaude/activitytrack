/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/* The one Home list (owner, 2026-10-27): groups, state filter and window. */
class HomeListTest {
	private val zone = ZoneId.systemDefault()
	private val day = LocalDate.of(2026, 10, 27)
	private val now = day.atTime(12, 0).atZone(zone).toInstant()

	private fun todo(id: Long, planDay: LocalDate? = null, start: Int? = null, doneAt: Long? = null) = TodoEntity(
		id = id, uid = "", companyId = null, title = "t$id", createdUtcMs = 0, updatedUtcMs = 0,
		planEpochDay = planDay?.toEpochDay(), planStartMin = start, done = doneAt != null, doneUtcMs = doneAt,
	)

	private fun entry(id: Long, status: EntryStatus, end: Long) = EntryCard(
		TimeEntryEntity(id = id, companyId = null, clientId = null, status = status, zoneId = zone.id, createdUtcMs = 0, updatedUtcMs = end),
		null, null, emptyList(), endUtcMs = end,
	)

	private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

	private fun keys(sections: List<Pair<HomeSection, List<HomeItem>>>) = sections.associate { (s, l) -> s to l.map { it.key } }

	@Test
	fun groupsInOrder() {
		val open = listOf(
			todo(1, day.minusDays(3), 9 * 60),	/* overdue */
			todo(2, day, 15 * 60),			/* today, later */
			todo(3, day.plusDays(1), 9 * 60),	/* tomorrow */
			todo(4),				/* no date */
		)
		val stopped = listOf(
			entry(10, EntryStatus.TO_COMPLETE, at(day.minusDays(5), 10)),
			entry(11, EntryStatus.CLOSED, at(day, 9)),
			entry(12, EntryStatus.CLOSED, at(day.minusDays(1), 17)),
		)
		val done = listOf(todo(20, doneAt = at(day, 10)))
		val all = homeSections(open, emptyList(), done, stopped, HomeShow.entries.toSet(), null, null, 168, 6 * 60, now, zone)
		assertEquals(
			listOf(HomeSection.OVERDUE, HomeSection.MISSING, HomeSection.TODAY, HomeSection.NEXT, HomeSection.EARLIER, HomeSection.NO_DATE),
			all.map { it.first },
		)
		/* today: the to-dos ahead first, then what was done, the most recent first */
		assertEquals(listOf("todo-2", "todo-20", "entry-11"), keys(all)[HomeSection.TODAY])
	}

	@Test
	fun theDefaultShowsWhatIsLeftToDo() {
		val stopped = listOf(entry(10, EntryStatus.TO_COMPLETE, at(day.minusDays(30), 10)), entry(11, EntryStatus.CLOSED, at(day, 9)))
		val shown = homeSections(listOf(todo(2, day, 15 * 60)), emptyList(), emptyList(), stopped, setOf(HomeShow.TODO, HomeShow.MISSING), null, null, -1, 6 * 60, now, zone)
		/* details missing always shown, whatever the window; the closed one is not */
		assertEquals(mapOf(HomeSection.MISSING to listOf("entry-10"), HomeSection.TODAY to listOf("todo-2")), keys(shown))
	}

	@Test
	fun todayWindowStopsAtMidnightAndATagHidesTheToDos() {
		val tomorrow = todo(3, day.plusDays(1), 9 * 60)
		assertEquals(emptyList(), homeSections(listOf(tomorrow), emptyList(), emptyList(), emptyList(), setOf(HomeShow.TODO), null, null, -1, 6 * 60, now, zone))
		assertEquals(1, homeSections(listOf(tomorrow), emptyList(), emptyList(), emptyList(), setOf(HomeShow.TODO), null, null, 24, 6 * 60, now, zone).size)
		assertEquals(emptyList(), homeSections(listOf(tomorrow), emptyList(), emptyList(), emptyList(), setOf(HomeShow.TODO), null, "remoto", 24, 6 * 60, now, zone))
	}
}
