/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.calendar

import it.infraconsulting.activitytrack.domain.export.EntryMeta
import it.infraconsulting.activitytrack.domain.report.AbsenceDayLine
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
	fun tagsPerKindOfTime() {
		val tagged = labels.copy(tags = EventTags("#work", "#travel", "#pause", "#planned"))
		val days = listOf(DayTotals(day, Duration.ofMinutes(130), Duration.ofMinutes(150), listOf(line(1, 110, 20))))
		val e = CalendarEvents.plan(days, mapOf(1L to meta("Bank")), mapOf(1L to "u-1"), mapOf(1L to 5L), tagged).single()
		assertEquals("Work 2:00 · Travel 0:20\n#work #travel", e.description)
	}

	@Test
	fun entryTagsFollowTheKindTags() {
		val tagged = labels.copy(tags = EventTags("#work", "#travel", "#pause", "#planned"))
		val days = listOf(DayTotals(day, Duration.ofMinutes(110), Duration.ofMinutes(110), listOf(line(1, 110))))
		val m = meta("Bank").copy(tags = listOf("remoto", "urgente"))
		val e = CalendarEvents.plan(days, mapOf(1L to m), mapOf(1L to "u-1"), mapOf(1L to 5L), tagged).single()
		assertEquals("Work 2:00\n#work #remoto #urgente", e.description)
		/* without kind tags the entry tags still make the last line */
		val plain = CalendarEvents.plan(days, mapOf(1L to m), mapOf(1L to "u-1"), mapOf(1L to 5L), labels).single()
		assertEquals("Work 2:00\n#remoto #urgente", plain.description)
	}

	@Test
	fun plannedTodoIsTentativeWithItsTag() {
		val tagged = labels.copy(tags = EventTags(planned = "#planned"))
		val e = CalendarEvents.planTodos(
			listOf(TodoEvent("t-1", 5, start, start.plusSeconds(3600), "Patch", "Bank", "Rack 3", listOf("INC1"))), tagged,
		).single()
		assertEquals("plan:t-1", e.key)
		assertEquals("Bank – Patch", e.title)
		assertEquals("Rack 3\nRefs: INC1\n#planned", e.description)
		assertEquals(true, e.tentative)
		assertEquals(start.plusSeconds(3600), e.end)
	}

	@Test
	fun entriesWithoutUidAreLeftOut() {
		val days = listOf(DayTotals(day, Duration.ZERO, Duration.ZERO, listOf(line(3, 30))))
		assertEquals(emptyList(), CalendarEvents.plan(days, mapOf(3L to meta("Bank")), emptyMap(), emptyMap(), labels))
	}

	@Test
	fun absencesWholeDayIsAllDay() {
		/* issue #188: a holiday 00:00-23:59 on Thursday 19 November 2026 in Rome, a leave 09:00-11:00 the next day */
		val rome = java.time.ZoneId.of("Europe/Rome")
		val thu = java.time.LocalDate.of(2026, 11, 19)
		val fri = thu.plusDays(1)
		val s0 = thu.atStartOfDay(rome).toInstant()
		val s1 = fri.atTime(9, 0).atZone(rome).toInstant()
		val days = listOf(
			DayTotals(thu, Duration.ZERO, Duration.ZERO, emptyList(), listOf(AbsenceDayLine(4, thu, s0, s0.plusSeconds(1439 * 60), Duration.ofMinutes(1439)))),
			DayTotals(fri, Duration.ZERO, Duration.ZERO, emptyList(), listOf(AbsenceDayLine(5, fri, s1, s1.plusSeconds(7200), Duration.ofHours(2)))),
		)
		val kinds = labels.copy(absence = "Assenza", absenceKinds = mapOf("HOLIDAY" to "Ferie"))
		val metas = mapOf(4L to meta("").copy(absenceKind = "HOLIDAY"), 5L to meta("").copy(absenceKind = "LEAVE", notes = "Dentista"))
		val uids = mapOf(4L to "a-4", 5L to "a-5")
		/* without a zone the absences stay out, as before */
		assertEquals(emptyList(), CalendarEvents.plan(days, metas, uids, emptyMap(), kinds))
		val (h, l) = CalendarEvents.plan(days, metas, uids, emptyMap(), kinds, rome)
		assertEquals("a-4/2026-11-19", h.key)
		assertEquals("Ferie", h.title)
		assertEquals(true, h.allDay)
		assertEquals(java.time.Instant.parse("2026-11-19T00:00:00Z"), h.start)
		assertEquals(java.time.Instant.parse("2026-11-20T00:00:00Z"), h.end)
		assertEquals("Assenza", l.title)
		assertEquals(false, l.allDay)
		assertEquals(s1, l.start)
		assertEquals("Dentista", l.description)
	}
}
