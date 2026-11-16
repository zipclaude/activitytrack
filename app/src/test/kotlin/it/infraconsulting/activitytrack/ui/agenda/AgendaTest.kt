/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.ui.agenda

import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.ui.todo.todoRow
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

	@Test
	fun aWholeDayAbsenceGoesToTheAllDayStrip() {
		/* issue #188: 00:00-23:59 of Thursday 19 November 2026 in Rome */
		val rome = ZoneId.of("Europe/Rome")
		val t = todoRow(id = 7).copy(kind = ActivityKind.ABSENCE, status = ActivityStatus.CLOSED)
		val day = LocalDate.of(2026, 11, 19)
		val start = day.atStartOfDay(rome).toInstant().toEpochMilli()
		val whole = AgendaViewModel.absenceBlock(t, start, start + 1439 * 60_000L, rome)
		assertTrue(whole.allDay)
		assertTrue(whole.planned)
		assertNull(whole.timeMin)
		assertEquals(day, whole.day)
		/* 09:00-18:00: a block over its times */
		val hours = AgendaViewModel.absenceBlock(t, start + 540 * 60_000L, start + 1080 * 60_000L, rome)
		assertFalse(hours.allDay)
		assertEquals(540, hours.timeMin)
	}
}
