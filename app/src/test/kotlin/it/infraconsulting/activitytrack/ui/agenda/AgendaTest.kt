/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.agenda

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AgendaTest {
	@Test
	fun overlappingEntriesShareColumns() {
		/* 9-11 and 10-12 overlap, 11-13 reuses the first column, 12-14 the second */
		assertEquals(listOf(0, 1, 0, 1), columnsOf(listOf(540 to 660, 600 to 720, 660 to 780, 720 to 840)))
		assertEquals(listOf(0, 0), columnsOf(listOf(540 to 600, 600 to 660)))
	}

	@Test
	fun ranges() {
		val sat = LocalDate.of(2026, 10, 17)
		assertEquals(LocalDate.of(2026, 10, 12) to LocalDate.of(2026, 10, 18), AgendaViewModel.range(AgendaMode.WEEK, sat))
		assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31), AgendaViewModel.range(AgendaMode.LIST, sat))
		/* October 2026 starts on Thursday and ends on Saturday: whole weeks around it */
		assertEquals(LocalDate.of(2026, 9, 28) to LocalDate.of(2026, 11, 1), AgendaViewModel.range(AgendaMode.MONTH, sat))
	}
}
