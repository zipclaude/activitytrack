/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.todo

import it.infraconsulting.activitytrack.domain.reminder.Reminder
import it.infraconsulting.activitytrack.domain.reminder.ReminderConfig
import it.infraconsulting.activitytrack.domain.reminder.ReminderPlanner
import it.infraconsulting.activitytrack.domain.reminder.ReminderTodo
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoPlanTest {
	private val zone = ZoneId.of("Europe/Rome")
	private val day = LocalDate.of(2026, 10, 19)

	private fun at(d: LocalDate, hh: Int, mm: Int, ss: Int = 0): Instant = d.atTime(LocalTime.of(hh, mm, ss)).atZone(zone).toInstant()

	@Test
	fun durationGivesAFixedEnd() {
		val p = TodoPlan(day, 9 * 60, 90)
		assertEquals(at(day, 10, 30), p.end(zone, open = true, now = at(day, 15, 0)))
		assertNull(p.nextGrowth(zone, open = true, now = at(day, 9, 0)))
	}

	@Test
	fun withoutDurationOneHourGrowingHourByHourWhileOpen() {
		val p = TodoPlan(day, 9 * 60)
		assertEquals(at(day, 10, 0), p.end(zone, true, at(day, 8, 0)))
		assertEquals(at(day, 10, 0), p.end(zone, true, at(day, 10, 0)))
		assertEquals(at(day, 11, 0), p.end(zone, true, at(day, 10, 0, 1)))
		assertEquals(at(day, 12, 0), p.end(zone, true, at(day, 11, 30)))
		/* done: back to one hour */
		assertEquals(at(day, 10, 0), p.end(zone, false, at(day, 11, 30)))
		assertEquals(at(day, 12, 0), p.nextGrowth(zone, true, at(day, 11, 30)))
		assertNull(p.nextGrowth(zone, false, at(day, 11, 30)))
	}

	@Test
	fun growthStopsAtTheEndOfThePlannedDay() {
		val p = TodoPlan(day, 22 * 60 + 30)
		val midnight = day.plusDays(1).atStartOfDay(zone).toInstant()
		assertEquals(midnight, p.end(zone, true, at(day.plusDays(1), 3, 0)))
		assertNull(p.nextGrowth(zone, true, at(day.plusDays(1), 3, 0)))
		/* a late start still gets its first hour */
		assertEquals(at(day.plusDays(1), 0, 15), TodoPlan(day, 23 * 60 + 15).end(zone, true, at(day.plusDays(1), 5, 0)))
	}

	@Test
	fun postponeCrossesMidnight() {
		assertEquals(TodoPlan(day, 10 * 60, 45), TodoPlan(day, 9 * 60 + 30, 45).postponed(30))
		assertEquals(TodoPlan(day.plusDays(1), 10), TodoPlan(day, 23 * 60 + 40).postponed(30))
	}

	@Test
	fun postponeFromTheStartOrFromNowWhenLate() {
		val p = TodoPlan(day, 9 * 60, 60)
		/* answered at the reminder, before the start: start + 30 */
		assertEquals(TodoPlan(day, 9 * 60 + 30, 60), p.postponed(30, at(day, 8, 45), zone))
		assertEquals(TodoPlan(day, 9 * 60 + 30, 60), p.postponed(30, at(day, 9, 0), zone))
		/* answered late: now (next whole minute) + 30 */
		assertEquals(TodoPlan(day, 9 * 60 + 50, 60), p.postponed(30, at(day, 9, 20), zone))
		assertEquals(TodoPlan(day, 9 * 60 + 51, 60), p.postponed(30, at(day, 9, 20, 10), zone))
		assertEquals(TodoPlan(day.plusDays(1), 10), TodoPlan(day, 23 * 60).postponed(30, at(day, 23, 40), zone))
	}

	@Test
	fun dstDayKeepsTheWallClock() {
		/* 25 October 2026: clocks go back at 03:00 in Rome */
		val dst = LocalDate.of(2026, 10, 25)
		assertEquals(at(dst, 9, 0), TodoPlan(dst, 9 * 60).start(zone))
	}

	@Test
	fun ofNeedsDayAndTime() {
		assertNull(TodoPlan.of(day.toEpochDay(), null, 30))
		assertNull(TodoPlan.of(null, 600, null))
		assertEquals(TodoPlan(day, 600, 30), TodoPlan.of(day.toEpochDay(), 600, 30))
	}

	private val planner = ReminderPlanner(
		ReminderConfig(Duration.ofMinutes(30), 0, Duration.ofMinutes(15), planLead = Duration.ofMinutes(15)),
		emptyList(), emptyList(), zone,
	)

	@Test
	fun reminderLeadOncePerPlannedStart() {
		val start = at(day, 9, 0)
		val t = ReminderTodo(7, start, at(day, 10, 0), remindedFor = null)
		assertEquals(at(day, 8, 45), planner.next(emptyList(), null, at(day, 8, 0), todos = listOf(t)))
		assertEquals(listOf<Reminder>(Reminder.Planned(7, at(day, 8, 45))), planner.due(emptyList(), null, at(day, 8, 46), todos = listOf(t)))
		/* shown: not again; postponed (new start): again */
		assertTrue(planner.planned(emptyList(), null, at(day, 8, 46), todos = listOf(t.copy(remindedFor = start.toEpochMilli()))).isEmpty())
		val later = t.copy(start = at(day, 9, 30), end = at(day, 10, 30), remindedFor = start.toEpochMilli())
		assertEquals(at(day, 9, 15), planner.next(emptyList(), null, at(day, 8, 46), todos = listOf(later)))
	}

	@Test
	fun noReminderOnceTheBlockIsOver() {
		val t = ReminderTodo(7, at(day, 9, 0), at(day, 10, 0), remindedFor = null)
		/* phone off through the lead time: still shown during the block, not after */
		assertEquals(1, planner.due(emptyList(), null, at(day, 9, 40), todos = listOf(t)).size)
		assertTrue(planner.due(emptyList(), null, at(day, 10, 0), todos = listOf(t)).isEmpty())
	}
}
