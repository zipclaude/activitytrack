/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import it.infraconsulting.activitytrack.domain.model.SegmentKind.PAUSE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.TRAVEL
import it.infraconsulting.activitytrack.domain.model.SegmentKind.WORK
import it.infraconsulting.activitytrack.domain.report.DayAttributionPolicy
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import it.infraconsulting.activitytrack.domain.time.atMinute
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class AntaresPlanTest {
	private val rome = ZoneId.of("Europe/Rome")
	private val day = LocalDate.of(2026, 10, 19)
	private fun at(h: Int, m: Int, d: LocalDate = day) = d.atMinute(h * 60 + m, rome)
	private fun plan(vararg s: PlanSegment) =
		AntaresPlan.chunks(s.toList(), rome, ReportRounding.DEFAULT, DayAttributionPolicy(), Duration.ofMinutes(60))

	@Test
	fun oneActivityWithShortPauseAndTravel() {
		val c = plan(
			PlanSegment(TRAVEL, at(8, 30), at(9, 0), 18.4),
			PlanSegment(WORK, at(9, 0), at(12, 30)),
			PlanSegment(PAUSE, at(12, 30), at(13, 0)),
			PlanSegment(WORK, at(13, 0), at(16, 50)),
			PlanSegment(TRAVEL, at(16, 50), at(17, 22), 18.6),
		).single()
		assertEquals(at(9, 0), c.from)
		/* 7:50 with the pause inside, rounded up with tolerance to 8:00 */
		assertEquals(at(17, 0), c.to)
		/* 30 + 32 min -> 62, rounded up to 5 -> 65 */
		assertEquals(65, c.travelMinutes)
		assertEquals(37, c.travelKm)
	}

	@Test
	fun longPauseSplitsAndTravelGoesToTheNearestBlock() {
		val c = plan(
			PlanSegment(WORK, at(9, 0), at(11, 0)),
			PlanSegment(PAUSE, at(11, 0), at(14, 0)),
			PlanSegment(TRAVEL, at(14, 0), at(14, 20), 10.0),
			PlanSegment(WORK, at(14, 20), at(16, 0)),
		)
		assertEquals(2, c.size)
		assertEquals(listOf(0, 1), c.map { it.index })
		assertEquals(at(11, 0), c[0].to)
		assertEquals(0, c[0].travelMinutes)
		assertEquals(20, c[1].travelMinutes)
		assertEquals(10, c[1].travelKm)
		/* 1:40 rounded up to the quarter */
		assertEquals(at(16, 5), c[1].to)
	}

	@Test
	fun nightWorkStaysOnTheStartDayLongerWorkSplitsAtMidnight() {
		val night = plan(PlanSegment(WORK, at(22, 0), at(4, 30, day.plusDays(1))))
		assertEquals(listOf(day), night.map { it.day })
		val long = plan(PlanSegment(WORK, at(20, 0), at(8, 0, day.plusDays(1))))
		assertEquals(listOf(day, day.plusDays(1)), long.map { it.day })
		assertEquals(at(0, 0, day.plusDays(1)), long[1].from)
	}

	@Test
	fun travelOnlyGivesNothing() {
		assertEquals(emptyList(), plan(PlanSegment(TRAVEL, at(8, 0), at(9, 0), 50.0)))
	}
}
