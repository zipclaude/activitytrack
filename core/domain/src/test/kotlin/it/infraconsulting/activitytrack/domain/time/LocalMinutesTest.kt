/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.time

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalMinutesTest {
	private val rome = ZoneId.of("Europe/Rome")

	@Test
	fun latestOccurrence() {
		val now = LocalDate.of(2026, 10, 19).atMinute(9 * 60 + 30, rome)
		assertEquals(LocalDate.of(2026, 10, 19).atMinute(8 * 60, rome), latestAt(8 * 60, now, rome))
		assertEquals(now, latestAt(9 * 60 + 30, now, rome))
		assertEquals(LocalDate.of(2026, 10, 18).atMinute(23 * 60, rome), latestAt(23 * 60, now, rome))
	}

	@Test
	fun wallClockOnDstDays() {
		/* fall back 2026-10-25: 25 h day; 04:00 local is 5 h after midnight */
		val fall = LocalDate.of(2026, 10, 25)
		assertEquals(Duration.ofHours(5), Duration.between(fall.atMinute(0, rome), fall.atMinute(240, rome)))
		assertEquals(Duration.ofHours(25), Duration.between(fall.atMinute(0, rome), fall.atMinute(1440, rome)))
		/* spring forward 2026-03-29: 04:00 local is 3 h after midnight */
		val spring = LocalDate.of(2026, 3, 29)
		assertEquals(Duration.ofHours(3), Duration.between(spring.atMinute(0, rome), spring.atMinute(240, rome)))
		/* 02:30 does not exist: resolved forward to 03:30 CEST */
		assertEquals("03:30", spring.atMinute(150, rome).atZone(rome).toLocalTime().toString())
	}
}
