/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CreatePartsTest {
	private val rome = ZoneId.of("Europe/Rome")

	@Test
	fun draftsAreWallClockTimesOfTheDay() {
		val s = SegDraft(SegmentKind.WORK, 9 * 60, 10 * 60 + 30).toInput(LocalDate.of(2026, 11, 12), rome)
		assertEquals(Instant.parse("2026-11-12T08:00:00Z").toEpochMilli(), s.startUtcMs)
		assertEquals(Instant.parse("2026-11-12T09:30:00Z").toEpochMilli(), s.endUtcMs)
	}

	@Test
	fun anEndPastMidnightFallsOnTheNextDay() {
		val s = SegDraft(SegmentKind.WORK, 22 * 60, 24 * 60 + 90).toInput(LocalDate.of(2026, 11, 12), rome)
		assertEquals(Instant.parse("2026-11-13T00:30:00Z").toEpochMilli(), s.endUtcMs)
	}

	/* 2026-03-29: Italy skips 02:00-03:00; "midnight + minutes" would be an hour off */
	@Test
	fun daylightSavingDayKeepsTheWallClock() {
		val s = SegDraft(SegmentKind.PAUSE, 4 * 60, 5 * 60).toInput(LocalDate.of(2026, 3, 29), rome)
		assertEquals(Instant.parse("2026-03-29T02:00:00Z").toEpochMilli(), s.startUtcMs)
		assertEquals(Instant.parse("2026-03-29T03:00:00Z").toEpochMilli(), s.endUtcMs)
	}

	@Test
	fun minutesFromIsTheInverseOfToInput() {
		val day = LocalDate.of(2026, 3, 28)
		val d = SegDraft(SegmentKind.TRAVEL, 23 * 60, 24 * 60 + 30)
		assertEquals(d.startMin to d.endMin, d.toInput(day, rome).minutesFrom(day, rome))
	}

	@Test
	fun referencesOnePerLine() {
		assertEquals(listOf("TCK-1", "https://x/y"), refLines(" TCK-1 \n\n https://x/y\n  "))
	}
}
