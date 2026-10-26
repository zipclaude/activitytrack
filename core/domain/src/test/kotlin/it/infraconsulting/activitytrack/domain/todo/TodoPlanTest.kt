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

	/* issues #71/#76: the plan starts with the due date passed */
	@Test
	fun startsAfterDueAndAlignment() {
		val dueDay = day
		/* a later day, with or without due time */
		assertTrue(startsAfterDue(TodoPlan(day.plusDays(1)), dueDay, null, zone))
		assertTrue(startsAfterDue(TodoPlan(day.plusDays(1), 9 * 60), dueDay, 18 * 60, zone))
		/* the same day: only a start after the due time */
		assertEquals(false, startsAfterDue(TodoPlan(day, 15 * 60), dueDay, null, zone))
		assertTrue(startsAfterDue(TodoPlan(day, 15 * 60), dueDay, 10 * 60, zone))
		assertEquals(false, startsAfterDue(TodoPlan(day, 9 * 60), dueDay, 10 * 60, zone))
		assertEquals(false, startsAfterDue(TodoPlan(day.minusDays(1)), dueDay, null, zone))
		assertEquals(false, startsAfterDue(null, dueDay, null, zone))
		assertEquals(false, startsAfterDue(TodoPlan(day.plusDays(1)), null, null, zone))
		/* aligning takes the plan's day; a due time still before the start goes */
		assertEquals(day.plusDays(2) to 18 * 60, alignedDue(TodoPlan(day.plusDays(2), 9 * 60), 18 * 60, zone))
		assertEquals(day to null, alignedDue(TodoPlan(day, 15 * 60), 10 * 60, zone))
		assertEquals(day.plusDays(1) to null, alignedDue(TodoPlan(day.plusDays(1)), null, zone))
	}

	@Test
	fun blockPointAndWholeDay() {
		/* a duration: a block */
		assertEquals(at(day, 10, 30), TodoPlan(day, 9 * 60, 90).end(zone))
		/* a start only: a point (issue #52, answer 5) */
		val point = TodoPlan(day, 9 * 60)
		assertEquals(at(day, 9, 0), point.end(zone))
		assertEquals(false, point.over(at(day, 9, 0), zone))
		assertEquals(true, point.over(at(day, 9, 0, 1), zone))
		/* the day only: the whole day, over from the next day (answer 4) */
		val whole = TodoPlan(day)
		assertTrue(whole.allDay)
		assertEquals(at(day, 0, 0), whole.start(zone))
		assertEquals(day.plusDays(1).atStartOfDay(zone).toInstant(), whole.end(zone))
		assertEquals(false, whole.over(at(day, 23, 59), zone))
		assertEquals(true, whole.over(at(day.plusDays(1), 0, 0), zone))
	}

	@Test
	fun postponeCrossesMidnight() {
		assertEquals(TodoPlan(day, 10 * 60, 45), TodoPlan(day, 9 * 60 + 30, 45).postponed(30))
		assertEquals(TodoPlan(day.plusDays(1), 10), TodoPlan(day, 23 * 60 + 40).postponed(30))
		/* a whole-day plan moves by days */
		assertEquals(TodoPlan(day.plusDays(1)), TodoPlan(day).postponed(30))
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
	fun dueDatePassed() {
		assertEquals(false, dueOverdue(null, null, at(day, 12, 0), zone))
		/* date only: overdue from the next day */
		assertEquals(false, dueOverdue(day, null, at(day, 23, 59), zone))
		assertEquals(true, dueOverdue(day, null, at(day.plusDays(1), 0, 0, 1), zone))
		/* date and time: overdue after the time */
		assertEquals(false, dueOverdue(day, 14 * 60, at(day, 14, 0), zone))
		assertEquals(true, dueOverdue(day, 14 * 60, at(day, 14, 1), zone))
	}

	@Test
	fun nextTwentyFourHours() {
		val now = at(day, 22, 0)
		/* tomorrow 08:00 is in, the day after at noon is out, yesterday (overdue) is in */
		assertEquals(true, withinHours(todoMoment(TodoPlan(day.plusDays(1), 8 * 60), null, null, zone), now))
		assertEquals(false, withinHours(todoMoment(TodoPlan(day.plusDays(2), 12 * 60), null, null, zone), now))
		assertEquals(true, withinHours(todoMoment(null, day.minusDays(1), null, zone), now))
		/* the plan wins over the due date; a date without time counts from its start */
		assertEquals(at(day, 9, 0), todoMoment(TodoPlan(day, 9 * 60), day.plusDays(5), 600, zone))
		assertEquals(at(day.plusDays(1), 0, 0), todoMoment(null, day.plusDays(1), null, zone))
		assertEquals(false, withinHours(null, now))
	}

	@Test
	fun dstDayKeepsTheWallClock() {
		/* 25 October 2026: clocks go back at 03:00 in Rome */
		val dst = LocalDate.of(2026, 10, 25)
		assertEquals(at(dst, 9, 0), TodoPlan(dst, 9 * 60).start(zone))
	}

	@Test
	fun ofNeedsADay() {
		/* the day alone is a plan; a duration without a start is dropped */
		assertEquals(TodoPlan(day), TodoPlan.of(day.toEpochDay(), null, 30))
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

	@Test
	fun nextWorkingDaySkipsWeekendsAndDaysOff() {
		val friday = LocalDate.of(2026, 10, 23)
		val weekdays = setOf(1, 2, 3, 4, 5)
		assertEquals(LocalDate.of(2026, 10, 26), nextWorkingDay(friday, weekdays) { false })
		assertEquals(LocalDate.of(2026, 10, 27), nextWorkingDay(friday, weekdays) { it == LocalDate.of(2026, 10, 26) })
		assertEquals(friday.plusDays(1), nextWorkingDay(friday, emptySet()) { false })
	}
}
