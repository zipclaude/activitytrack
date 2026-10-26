/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import it.infraconsulting.activitytrack.data.db.TodoEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* The Home tabs pure logic (issue #59): planned split and closed boundary. */
class HomeTabsTest {
	private val zone = ZoneId.of("Europe/Rome")

	/* a to-do planned on [day] at [startMin], without duration */
	private fun planned(id: Long, day: LocalDate, startMin: Int?) = TodoEntity(
		id = id, uid = "", companyId = null, title = "t$id", notes = null, refs = "", createdUtcMs = 0, updatedUtcMs = 0,
		planEpochDay = day.toEpochDay(), planStartMin = startMin,
	)

	private fun due(id: Long, day: LocalDate, timeMin: Int? = null) = TodoEntity(
		id = id, uid = "", companyId = null, title = "t$id", notes = null, refs = "", createdUtcMs = 0, updatedUtcMs = 0,
		dueEpochDay = day.toEpochDay(), dueTimeMin = timeMin,
	)

	private fun undated(id: Long, priority: Int? = null) = TodoEntity(
		id = id, uid = "", companyId = null, title = "t$id", notes = null, refs = "", createdUtcMs = 0, updatedUtcMs = 0,
		priority = priority,
	)

	@Test
	fun tabOfStoredSetting() {
		assertEquals(HomeTab.PLANNED, HomeTab.of(null))
		assertEquals(HomeTab.PLANNED, HomeTab.of("PLANNED"))
		assertEquals(HomeTab.CLOSED, HomeTab.of("CLOSED"))
		/* an unknown stored value falls back to the planned tab */
		assertEquals(HomeTab.PLANNED, HomeTab.of("YESTERDAY"))
	}

	@Test
	fun plannedWindowKeepsOverdueAndSortsByMoment() {
		val now = LocalDate.of(2026, 10, 23).atTime(12, 0).atZone(zone).toInstant()
		val overdue = planned(1, LocalDate.of(2026, 10, 20), 10 * 60)
		val today = due(2, LocalDate.of(2026, 10, 23), 18 * 60)
		val tomorrow = planned(3, LocalDate.of(2026, 10, 24), 9 * 60)
		val nextWeek = planned(4, LocalDate.of(2026, 10, 29), 9 * 60) /* beyond 24 h + the whole planned day */
		val split = plannedSplit(listOf(nextWeek, today, overdue, tomorrow), null, 24, now, zone)
		/* the overdue one comes first (past moment), then by moment */
		assertEquals(listOf(1L, 2L, 3L), split.dated.map { it.id })
		/* window 0 = everything ahead */
		assertEquals(1, plannedSplit(listOf(nextWeek), null, 0, now, zone).dated.size)
	}

	@Test
	fun plannedWindowCutsTheFarFuture() {
		val now = LocalDate.of(2026, 10, 23).atTime(12, 0).atZone(zone).toInstant()
		val inThreeDays = planned(9, LocalDate.of(2026, 10, 26), 9 * 60)
		/* 72 h from now: the 26th at 9:00 is 69 h away */
		assertEquals(1, plannedSplit(listOf(inThreeDays), null, 72, now, zone).dated.size)
		/* 24 h cut it */
		assertTrue(plannedSplit(listOf(inThreeDays), null, 24, now, zone).dated.isEmpty())
	}

	@Test
	fun noDateToDosGoToTheirOwnSection() {
		val now = Instant.now()
		val a = undated(1, 2)
		val b = undated(2, 1)
		val c = undated(3)
		val dated = planned(4, LocalDate.now(zone), 9 * 60)
		val split = plannedSplit(listOf(a, b, c, dated), null, 24, now, zone)
		assertEquals(listOf(4L), split.dated.map { it.id })
		/* priority first (1 = highest), then title; the dated one never lands here */
		assertEquals(listOf(2L, 1L, 3L), split.noDate.map { it.id })
	}

	@Test
	fun plannedCompanyFilter() {
		val now = Instant.now()
		val mine = planned(1, LocalDate.now(zone), 9 * 60).copy(companyId = 7)
		val other = planned(2, LocalDate.now(zone), 9 * 60).copy(companyId = 9)
		val split = plannedSplit(listOf(mine, other), 7, 24, now, zone)
		assertEquals(listOf(1L), split.dated.map { it.id })
	}

	@Test
	fun closedTodayStartsAtTheCarryOverBoundary() {
		/* 2026-10-25 02:00: still yesterday's working day */
		val night = LocalDate.of(2026, 10, 25).atTime(2, 0).atZone(zone).toInstant()
		val b = closedBoundary(0, 6 * 60, night, zone)
		assertEquals(LocalDate.of(2026, 10, 24).atTime(6, 0).atZone(zone).toInstant(), b)
		/* after 06:00 the working day is today's */
		val morning = LocalDate.of(2026, 10, 25).atTime(9, 0).atZone(zone).toInstant()
		assertEquals(LocalDate.of(2026, 10, 25).atTime(6, 0).atZone(zone).toInstant(), closedBoundary(0, 6 * 60, morning, zone))
		/* the 24 h window counts back from now */
		val h = closedBoundary(24, 6 * 60, night, zone)
		assertEquals(night.minusSeconds(24 * 3600), h)
	}

	@Test
	fun closedRowSortKeys() {
		/* an entry without segments falls back to its update time */
		val e = ClosedRow.Entry(
			EntryCard(
				it.infraconsulting.activitytrack.data.db.TimeEntryEntity(
					id = 5, companyId = null, clientId = null,
					status = it.infraconsulting.activitytrack.domain.model.EntryStatus.CLOSED,
					zoneId = "Europe/Rome", createdUtcMs = 0, updatedUtcMs = 200,
				),
				"", "", emptyList(),
			),
		)
		assertEquals(200L, e.sortMs)
		val t = ClosedRow.Todo(undated(1).copy(updatedUtcMs = 300), null)
		assertEquals(300L, t.sortMs)
		assertNull(e.card.endUtcMs)
	}
}
