/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.rounding

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class RoundingTest {
	private fun min(m: Long) = Duration.ofMinutes(m)
	private fun ms(m: Long, s: Long) = Duration.ofMinutes(m).plusSeconds(s)

	private val work = ReportRounding.DEFAULT.work
	private val travel = ReportRounding.DEFAULT.travel
	private val pause = ReportRounding.DEFAULT.pause

	@Test
	fun workTolerantQuarter() {
		assertEquals(Duration.ZERO, work.apply(Duration.ZERO))
		assertEquals(min(15), work.apply(Duration.ofSeconds(1)))
		assertEquals(min(15), work.apply(min(7)))
		assertEquals(min(15), work.apply(min(15)))
		assertEquals(min(15), work.apply(ms(19, 59)))
		assertEquals(min(30), work.apply(min(20)))
		assertEquals(min(30), work.apply(min(30)))
		assertEquals(min(30), work.apply(ms(34, 59)))
		assertEquals(min(45), work.apply(min(35)))
		assertEquals(min(480), work.apply(ms(484, 59)))
		assertEquals(min(495), work.apply(min(485)))
	}

	@Test
	fun toleranceIsProportionalToQuantum() {
		val hour = RoundingRule(RoundingMode.CEIL_TOLERANT, min(60))
		assertEquals(min(60), hour.apply(min(79)))
		assertEquals(min(120), hour.apply(min(80)))
		val five = RoundingRule(RoundingMode.CEIL_TOLERANT, min(5))
		assertEquals(min(10), five.apply(ms(11, 39)))
		assertEquals(min(15), five.apply(ms(11, 40)))
	}

	@Test
	fun travelCeilFive() {
		assertEquals(Duration.ZERO, travel.apply(Duration.ZERO))
		assertEquals(min(5), travel.apply(Duration.ofSeconds(1)))
		assertEquals(min(5), travel.apply(min(5)))
		assertEquals(min(10), travel.apply(ms(5, 1)))
		assertEquals(min(45), travel.apply(min(41)))
	}

	@Test
	fun pauseFloorFiveMinimumFive() {
		assertEquals(Duration.ZERO, pause.apply(Duration.ZERO))
		assertEquals(min(5), pause.apply(min(3)))
		assertEquals(min(5), pause.apply(ms(9, 59)))
		assertEquals(min(10), pause.apply(min(10)))
		assertEquals(min(85), pause.apply(min(89)))
	}
}
