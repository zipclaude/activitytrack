/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.time

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeGridTest {
	private val rome = ZoneId.of("Europe/Rome")
	private val day = LocalDate.of(2026, 10, 19)
	private fun at(min: Int) = day.atMinute(min, rome)

	@Test
	fun cellsAndRanges() {
		assertEquals(111, TimeGrid.cellOf(9 * 60 + 15))
		assertEquals(111, TimeGrid.cellOf(9 * 60 + 19))
		/* 09:15 dragged to the 10:35 cell: 09:15-10:40, either direction */
		assertEquals(555 until 640, TimeGrid.range(111, 127))
		assertEquals(555 until 640, TimeGrid.range(127, 111))
		assertEquals(1435 until 1440, TimeGrid.range(287, 287))
	}

	@Test
	fun window() {
		assertEquals(7, TimeGrid.windowFor(9 * 60 + 15))
		assertEquals(0, TimeGrid.windowFor(30))
		assertEquals(12, TimeGrid.windowFor(23 * 60))
		assertEquals(12, TimeGrid.shift(10, 6))
		assertEquals(0, TimeGrid.shift(2, -3))
	}

	@Test
	fun busyIsClippedAndMerged() {
		val now = at(18 * 60)
		val busy = TimeGrid.busyMinutes(
			listOf(
				day.minusDays(1).atMinute(23 * 60, rome) to at(60),	/* from yesterday */
				at(500) to at(555),
				at(555) to at(600),	/* touching: merged */
				at(17 * 60) to null,	/* running until now */
			),
			day, rome, now,
		)
		assertEquals(listOf(0 until 60, 500 until 600, 1020 until 1080), busy)
		assertEquals(true, TimeGrid.busyCell(100, busy))	/* 08:20 */
		assertEquals(false, TimeGrid.busyCell(120, busy))	/* 10:00 */
		assertEquals(true, TimeGrid.busyCell(TimeGrid.cellOf(599), busy))
	}
}
