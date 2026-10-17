/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.calendar

import it.infraconsulting.activitytrack.domain.export.EntryMeta
import it.infraconsulting.activitytrack.domain.report.DayTotals
import it.infraconsulting.activitytrack.domain.report.EntryDayLine
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarEventsTest {
	private val labels = EventLabels("Work", "Travel", "Pause", "Refs", "km")
	private val day = LocalDate.of(2026, 10, 17)
	private val start = Instant.parse("2026-10-17T07:00:00Z")

	private fun line(entryId: Long, work: Long, travel: Long = 0, end: Instant = start.plusSeconds((work + travel) * 60)) =
		EntryDayLine(
			entryId, day, start, end,
			Duration.ofMinutes(work), Duration.ofMinutes(travel), Duration.ZERO,
			Duration.ofMinutes(((work + 14) / 15) * 15), Duration.ofMinutes(travel), Duration.ZERO,
		)

	private fun meta(client: String, activity: String = "", refs: List<String> = emptyList(), notes: String = "", locality: String = "") =
		EntryMeta("Acme", client, "", activity, notes, locality, refs, null, 0)

	@Test
	fun titleDescriptionAndKey() {
		val days = listOf(DayTotals(day, Duration.ofMinutes(130), Duration.ofMinutes(150), listOf(line(1, 110, 20))))
		val e = CalendarEvents.plan(
			days, mapOf(1L to meta("Bank", "Patching", listOf("INC1"), "Rack 3", "Milano")),
			mapOf(1L to "u-1"), mapOf(1L to 5L), labels,
		).single()
		assertEquals("u-1/2026-10-17", e.key)
		assertEquals(5L, e.companyId)
		assertEquals("Bank – Patching", e.title)
		assertEquals("Work 2:00 · Travel 0:20\nRefs: INC1\nRack 3", e.description)
		assertEquals("Milano", e.location)
		assertEquals(start.plusSeconds(130 * 60), e.end)
	}

	@Test
	fun emptyActivityAndZeroLengthEntry() {
		val days = listOf(DayTotals(day, Duration.ZERO, Duration.ZERO, listOf(line(2, 0, end = start))))
		val e = CalendarEvents.plan(days, mapOf(2L to meta("Bank")), mapOf(2L to "u-2"), mapOf(2L to null), labels).single()
		assertEquals("Bank", e.title)
		assertEquals(start.plusSeconds(60), e.end)
		assertEquals(null, e.location)
	}

	@Test
	fun entriesWithoutUidAreLeftOut() {
		val days = listOf(DayTotals(day, Duration.ZERO, Duration.ZERO, listOf(line(3, 30))))
		assertEquals(emptyList(), CalendarEvents.plan(days, mapOf(3L to meta("Bank")), emptyMap(), emptyMap(), labels))
	}
}
