/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.reminder

import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderPlannerTest {
	private val zone = ZoneId.of("Europe/Rome")
	private val wed = LocalDate.of(2026, 10, 14)	/* Wednesday */

	private fun at(day: LocalDate, hh: Int, mm: Int): Instant = day.atTime(LocalTime.of(hh, mm)).atZone(zone).toInstant()
	private fun ms(day: LocalDate, hh: Int, mm: Int) = at(day, hh, mm).toEpochMilli()

	private val planner = ReminderPlanner(
		ReminderConfig(Duration.ofMinutes(30), maxConfirmations = 3, pauseInterval = Duration.ofMinutes(15)),
		lunchBreaks = listOf(DayWindow(3, 12 * 60 + 30, 14 * 60)),
		schedule = listOf(DayWindow(3, 9 * 60, 13 * 60), DayWindow(3, 14 * 60, 18 * 60), DayWindow(4, 9 * 60, 17 * 60)),
		zone = zone,
	)

	private fun work(lastConfirmed: Long, unanswered: Int = 0) =
		ReminderEntry(1, EntryStatus.ACTIVE, SegmentKind.WORK, lastConfirmed, unanswered)

	private fun pause(lastConfirmed: Long) = ReminderEntry(2, EntryStatus.ACTIVE, SegmentKind.PAUSE, lastConfirmed, 0)

	/* entry reminders only (the day summary is always planned when a schedule exists) */
	private fun List<Reminder>.entries() = filterNot { it is Reminder.DaySummary }

	@Test
	fun confirmEveryIntervalUntilMax() {
		val e = work(ms(wed, 9, 0))
		assertEquals(at(wed, 9, 30), planner.planned(listOf(e), wed, at(wed, 9, 10)).filterIsInstance<Reminder.Confirm>().single().at)
		assertEquals(at(wed, 10, 30), planner.planned(listOf(e.copy(unanswered = 2)), wed, at(wed, 10, 10)).entries().single().at)
		/* max reached: no more confirmations */
		assertTrue(planner.planned(listOf(e.copy(unanswered = 3)), wed, at(wed, 11, 0)).entries().isEmpty())
	}

	@Test
	fun confirmAlsoOutsideWorkingHours() {
		val e = work(ms(wed, 22, 0))
		assertEquals(at(wed, 22, 30), planner.next(listOf(e), wed, at(wed, 22, 5)))
	}

	@Test
	fun unlimitedConfirmations() {
		val p = ReminderPlanner(
			ReminderConfig(Duration.ofMinutes(30), 0, Duration.ofMinutes(15)), emptyList(), emptyList(), zone,
		)
		assertEquals(1, p.planned(listOf(work(ms(wed, 9, 0), unanswered = 50)), null, at(wed, 9, 0)).size)
	}

	@Test
	fun dueVersusNext() {
		val now = at(wed, 9, 40)
		val e = listOf(work(ms(wed, 9, 0)))
		assertEquals(listOf<Reminder>(Reminder.Confirm(1, at(wed, 9, 30))), planner.due(e, wed, now))
		/* after the reminder is marked, the next one is an interval later */
		assertEquals(at(wed, 10, 0), planner.next(listOf(work(ms(wed, 9, 0), unanswered = 1)), wed, now))
	}

	@Test
	fun pauseMutedDuringLunch() {
		assertEquals(at(wed, 11, 15), planner.planned(listOf(pause(ms(wed, 11, 0))), wed, at(wed, 11, 1)).entries().single().at)
		/* 12:20 + 15 = 12:35 falls in the lunch break -> 14:00 */
		assertEquals(at(wed, 14, 0), planner.planned(listOf(pause(ms(wed, 12, 20))), wed, at(wed, 12, 21)).entries().single().at)
		/* fired at 14:00, lastConfirmed moved there -> 14:15, no loop at the boundary */
		assertEquals(at(wed, 14, 15), planner.planned(listOf(pause(ms(wed, 14, 0))), wed, at(wed, 14, 0)).entries().single().at)
		/* Thursday has no lunch break */
		val thu = wed.plusDays(1)
		assertEquals(at(thu, 12, 35), planner.planned(listOf(pause(ms(thu, 12, 20))), thu, at(thu, 12, 21)).entries().single().at)
	}

	@Test
	fun inactiveEntriesHaveNoReminders() {
		val suspended = ReminderEntry(3, EntryStatus.SUSPENDED, null, ms(wed, 9, 0), 0)
		val noConfirm = ReminderEntry(4, EntryStatus.ACTIVE, SegmentKind.WORK, null, 0)
		assertTrue(planner.planned(listOf(suspended, noConfirm), wed, at(wed, 10, 0)).entries().isEmpty())
	}

	@Test
	fun daySummaryAtEndOfWorkingHours() {
		/* not shown today yet: 18:00 (last interval end) */
		assertEquals(listOf<Reminder>(Reminder.DaySummary(wed, at(wed, 18, 0))), planner.planned(emptyList(), null, at(wed, 10, 0)))
		/* shown today: Thursday 17:00 */
		val thu = wed.plusDays(1)
		assertEquals(Reminder.DaySummary(thu, at(thu, 17, 0)), planner.planned(emptyList(), wed, at(wed, 19, 0)).single())
		/* today's summary missed (app started late) is still due today */
		assertEquals(Reminder.DaySummary(wed, at(wed, 18, 0)), planner.due(emptyList(), null, at(wed, 21, 0)).single())
		/* next week's Wednesday after Thursday was shown */
		assertEquals(wed.plusDays(7), (planner.planned(emptyList(), thu, at(thu, 18, 0)).single() as Reminder.DaySummary).day)
	}

	@Test
	fun noScheduleNoSummary() {
		val p = ReminderPlanner(ReminderConfig(Duration.ofMinutes(30), 0, Duration.ofMinutes(15)), emptyList(), emptyList(), zone)
		assertNull(p.next(emptyList(), null, at(wed, 10, 0)))
	}

	private val thu = wed.plusDays(1)
	private val dayOff = ReminderPlanner(
		ReminderConfig(Duration.ofMinutes(30), maxConfirmations = 3, pauseInterval = Duration.ofMinutes(15)),
		lunchBreaks = emptyList(),
		schedule = listOf(DayWindow(3, 9 * 60, 13 * 60), DayWindow(3, 14 * 60, 18 * 60), DayWindow(4, 9 * 60, 17 * 60)),
		zone = zone,
		isDayOff = { it == wed },
	)

	private fun dayStarts(p: ReminderPlanner, st: DayStartState, now: Instant) =
		p.planned(emptyList(), lastSummaryDay = null, now, st).filterIsInstance<Reminder.DayStart>()

	@Test
	fun dayStartAfterFirstIntervalPlusDelay() {
		val st = DayStartState(lastShown = null, handled = emptySet())
		assertEquals(listOf(Reminder.DayStart(wed, at(wed, 9, 15))), dayStarts(planner, st, at(wed, 8, 0)))
		/* still due at 17:59, no longer once the working hours are over */
		assertTrue(planner.due(emptyList(), null, at(wed, 17, 59), st).contains(Reminder.DayStart(wed, at(wed, 9, 15))))
		assertEquals(listOf(Reminder.DayStart(thu, at(thu, 9, 15))), dayStarts(planner, st, at(wed, 18, 0)))
	}

	@Test
	fun dayStartSkipsHandledShownAndDaysOff() {
		val next = listOf(Reminder.DayStart(thu, at(thu, 9, 15)))
		assertEquals(next, dayStarts(planner, DayStartState(null, setOf(wed)), at(wed, 8, 0)))
		assertEquals(next, dayStarts(planner, DayStartState(wed, emptySet()), at(wed, 8, 0)))
		assertEquals(next, dayStarts(dayOff, DayStartState(null, emptySet()), at(wed, 8, 0)))
	}

	@Test
	fun noDayStartWithoutState() {
		assertTrue(planner.planned(emptyList(), null, at(wed, 10, 0)).none { it is Reminder.DayStart })
	}
}
