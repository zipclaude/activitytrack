/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.backup

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupScheduleTest {
	private val rome = ZoneId.of("Europe/Rome")
	private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 21, h, m, 0, 0, rome)

	@Test
	fun dailyAtTheHour() {
		assertEquals(Duration.ofMinutes(60), BackupSchedule.initialDelay(at(1, 0), 24, 2))
		assertEquals(Duration.ofHours(23), BackupSchedule.initialDelay(at(3, 0), 24, 2))
		/* exactly at the hour: the next day */
		assertEquals(Duration.ofHours(24), BackupSchedule.initialDelay(at(2, 0), 24, 2))
	}

	@Test
	fun everySixHoursAlignedOnTheHour() {
		/* hour 2: runs at 02, 08, 14, 20 */
		assertEquals(Duration.ofMinutes(150), BackupSchedule.initialDelay(at(11, 30), 6, 2))
		assertEquals(Duration.ofHours(6), BackupSchedule.initialDelay(at(20, 0), 6, 2))
	}

	@Test
	fun weeklyStartsAtTheNextPreferredHour() {
		assertEquals(Duration.ofHours(1), BackupSchedule.initialDelay(at(22, 0), 168, 23))
	}
}
