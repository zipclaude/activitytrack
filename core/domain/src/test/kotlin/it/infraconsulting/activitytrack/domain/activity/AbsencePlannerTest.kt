/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AbsencePlannerTest {
	private val rome = ZoneId.of("Europe/Rome")
	/* Monday to Friday 08:30-17:30 with lunch 12:30-13:30; Friday 08:00-14:00, lunch outside */
	private val schedule = (1..4).map { DayWindow(it, 510, 1050) } + DayWindow(5, 480, 840)
	private val lunch = (1..5).map { DayWindow(it, 750, 810) }
	private val noDaysOff: (LocalDate) -> Boolean = { false }

	private fun d(s: String) = LocalDate.parse(s)
	private fun t(s: String) = Instant.parse(s)
	private fun row(a: AbsenceInterval) = "${a.day}/${a.index} ${a.start}-${a.end}"

	@Test
	fun wholeDaysFollowTheWorkingHoursAndLunch() {
		/* Thursday 2026-11-12 to Monday 2026-11-16, Monday a day off */
		val got = AbsencePlanner.plan(d("2026-11-12"), d("2026-11-16"), null, null, schedule, lunch, { it == d("2026-11-16") }, rome)
		assertEquals(
			listOf(
				"2026-11-12/0 2026-11-12T07:30:00Z-2026-11-12T11:30:00Z",
				"2026-11-12/1 2026-11-12T12:30:00Z-2026-11-12T16:30:00Z",
				"2026-11-13/0 2026-11-13T07:00:00Z-2026-11-13T11:30:00Z",
				"2026-11-13/1 2026-11-13T12:30:00Z-2026-11-13T13:00:00Z",
			),
			got.map(::row),
		)
	}

	@Test
	fun withTimesEveryDayOfTheRange() {
		val got = AbsencePlanner.plan(d("2026-11-13"), d("2026-11-15"), 540, 660, schedule, lunch, { true }, rome)
		assertEquals(listOf("2026-11-13", "2026-11-14", "2026-11-15"), got.map { it.day.toString() })
		assertEquals(t("2026-11-14T08:00:00Z"), got[1].start)
		assertEquals(t("2026-11-14T10:00:00Z"), got[1].end)
		assertEquals(listOf(0, 0, 0), got.map { it.index })
	}

	@Test
	fun lunchAtTheEdgesTrims() {
		val s = listOf(DayWindow(1, 480, 720), DayWindow(1, 840, 1080))
		val l = listOf(DayWindow(1, 690, 870))	/* 11:30-14:30 overlaps the end of the first and the start of the second */
		assertEquals(listOf(480 to 690, 870 to 1080), AbsencePlanner.workingWindows(d("2026-11-16"), s, l, noDaysOff))
		assertEquals(emptyList(), AbsencePlanner.workingWindows(d("2026-11-16"), s, listOf(DayWindow(1, 0, 1440)), noDaysOff))
		assertEquals(emptyList(), AbsencePlanner.workingWindows(d("2026-11-15"), s, l, noDaysOff))
	}

	@Test
	fun overlappingOrTouchingHoursMakeOneWindow() {
		val s = listOf(DayWindow(1, 600, 780), DayWindow(1, 480, 720), DayWindow(1, 780, 900), DayWindow(1, 1000, 1000))
		assertEquals(listOf(480 to 900), AbsencePlanner.workingWindows(d("2026-11-16"), s, emptyList(), noDaysOff))
		assertEquals(listOf(480 to 690, 870 to 900), AbsencePlanner.workingWindows(d("2026-11-16"), s, listOf(DayWindow(1, 690, 800), DayWindow(1, 780, 870)), noDaysOff))
	}

	@Test
	fun dstStartDayKeepsTheWallClock() {
		/* Sunday 2026-03-29, 01:00-05:00: 02:00-03:00 does not exist, three real hours */
		val sunday = listOf(DayWindow(7, 60, 300))
		val a = AbsencePlanner.plan(d("2026-03-29"), d("2026-03-29"), null, null, sunday, emptyList(), noDaysOff, rome).single()
		assertEquals(t("2026-03-29T00:00:00Z"), a.start)
		assertEquals(t("2026-03-29T03:00:00Z"), a.end)
		assertEquals(Duration.ofHours(3), Duration.between(a.start, a.end))
		/* a time in the gap moves forward */
		val p = AbsencePlanner.plan(d("2026-03-29"), d("2026-03-29"), 150, 240, sunday, emptyList(), noDaysOff, rome).single()
		assertEquals(t("2026-03-29T01:30:00Z"), p.start)
		assertEquals(t("2026-03-29T02:00:00Z"), p.end)
	}

	@Test
	fun dstEndDayKeepsTheWallClock() {
		/* Sunday 2026-10-25, 01:00-05:00: 02:00-03:00 twice, five real hours */
		val sunday = listOf(DayWindow(7, 60, 300))
		val a = AbsencePlanner.plan(d("2026-10-25"), d("2026-10-25"), null, null, sunday, emptyList(), noDaysOff, rome).single()
		assertEquals(t("2026-10-24T23:00:00Z"), a.start)
		assertEquals(t("2026-10-25T04:00:00Z"), a.end)
		/* the whole day: 25 hours */
		val w = AbsencePlanner.plan(d("2026-10-25"), d("2026-10-25"), 0, 1440, emptyList(), emptyList(), noDaysOff, rome).single()
		assertEquals(Duration.ofHours(25), Duration.between(w.start, w.end))
	}

	@Test
	fun badInput() {
		assertFailsWith<IllegalArgumentException> { AbsencePlanner.plan(d("2026-11-13"), d("2026-11-12"), null, null, schedule, lunch, noDaysOff, rome) }
		assertFailsWith<IllegalArgumentException> { AbsencePlanner.plan(d("2026-11-13"), d("2026-11-13"), 540, null, schedule, lunch, noDaysOff, rome) }
		assertFailsWith<IllegalArgumentException> { AbsencePlanner.plan(d("2026-11-13"), d("2026-11-13"), 600, 600, schedule, lunch, noDaysOff, rome) }
	}
}
