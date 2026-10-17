/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui

import it.infraconsulting.activitytrack.ui.common.clockOf
import it.infraconsulting.activitytrack.ui.common.hmOf
import it.infraconsulting.activitytrack.ui.common.localRange
import it.infraconsulting.activitytrack.ui.common.parseDate
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeInputTest {
	private val rome = ZoneId.of("Europe/Rome")

	@Test
	fun sameDayAndNight() {
		val d = LocalDate.of(2026, 10, 14)
		val (s, e) = localRange(d, 8 * 60, 9 * 60 + 15, rome)
		assertEquals(75L * 60_000, e - s)
		assertEquals("08:00", clockOf(s, rome))
		val (ns, ne) = localRange(d, 22 * 60, 90, rome)
		assertEquals(210L * 60_000, ne - ns)
	}

	@Test
	fun dstFallBackNightIsRealTime() {
		/* 2026-10-25 03:00 CEST -> 02:00 CET: 01:00-04:00 lasts 4 h */
		val (s, e) = localRange(LocalDate.of(2026, 10, 25), 60, 240, rome)
		assertEquals(4L * 3_600_000, e - s)
	}

	@Test
	fun dates() {
		assertEquals(LocalDate.of(2026, 10, 14), parseDate(" 2026-10-14 "))
		assertNull(parseDate("14/10/2026"))
		assertEquals("1:05", hmOf(65L * 60_000))
	}
}
