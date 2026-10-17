/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.calendar

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItalianHolidaysTest {
	@Test
	fun easterDates() {
		/* includes the earliest (22 March) and latest (25 April) possible dates */
		val known = mapOf(
			2024 to LocalDate.of(2024, 3, 31),
			2025 to LocalDate.of(2025, 4, 20),
			2026 to LocalDate.of(2026, 4, 5),
			2027 to LocalDate.of(2027, 3, 28),
			2038 to LocalDate.of(2038, 4, 25),
			2285 to LocalDate.of(2285, 3, 22),
		)
		for ((year, date) in known)
			assertEquals(date, ItalianHolidays.easter(year), "Easter $year")
	}

	@Test
	fun nationalHolidays() {
		assertTrue(ItalianHolidays.isHoliday(LocalDate.of(2026, 4, 6)))		/* Easter Monday */
		assertFalse(ItalianHolidays.isHoliday(LocalDate.of(2026, 4, 5).plusDays(2)))
		assertTrue(ItalianHolidays.isHoliday(LocalDate.of(2026, 1, 6)))
		assertTrue(ItalianHolidays.isHoliday(LocalDate.of(2026, 12, 26)))
		assertFalse(ItalianHolidays.isHoliday(LocalDate.of(2026, 3, 1)))
	}

	@Test
	fun stFrancisFrom2026() {
		assertFalse(ItalianHolidays.isHoliday(LocalDate.of(2025, 10, 4)))
		assertTrue(ItalianHolidays.isHoliday(LocalDate.of(2026, 10, 4)))
	}
}
