/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.track

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackPeriodTest {
	private val rome = ZoneId.of("Europe/Rome")

	@Test
	fun weekRunsMondayToSunday() {
		/* 2026-11-05 is a Thursday */
		val (a, b) = TrackPeriod.WEEK.days(LocalDate.of(2026, 11, 5))
		assertEquals(LocalDate.of(2026, 11, 2), a)
		assertEquals(LocalDate.of(2026, 11, 9), b)
		assertEquals(a to b, TrackPeriod.WEEK.days(LocalDate.of(2026, 11, 8)))
	}

	@Test
	fun monthIsTheCalendarMonthEvenInFebruary() {
		assertEquals(LocalDate.of(2028, 2, 1) to LocalDate.of(2028, 3, 1), TrackPeriod.MONTH.days(LocalDate.of(2028, 2, 29)))
	}

	@Test
	fun aDayChangeWithDstIsNot24Hours() {
		/* 2026-10-25: clocks go back in Italy, the day lasts 25 hours */
		val (a, b) = TrackPeriod.DAY.bounds(LocalDate.of(2026, 10, 25), rome)
		assertEquals(25 * 3_600_000L, b - a)
		val (c, d) = TrackPeriod.DAY.bounds(LocalDate.of(2027, 3, 28), rome)
		assertEquals(23 * 3_600_000L, d - c)
	}

	@Test
	fun shiftMovesByOnePeriod() {
		val d = LocalDate.of(2026, 1, 31)
		assertEquals(LocalDate.of(2026, 2, 28), TrackPeriod.MONTH.shift(d, 1))
		assertEquals(LocalDate.of(2026, 1, 24), TrackPeriod.WEEK.shift(d, -1))
		assertEquals(LocalDate.of(2026, 2, 1), TrackPeriod.DAY.shift(d, 1))
	}
}
