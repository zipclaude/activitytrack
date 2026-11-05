/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.todo

import it.infraconsulting.activitytrack.domain.todo.AbsenceSpan.Kind
import it.infraconsulting.activitytrack.domain.todo.AbsenceSpan.Span
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbsenceSpanTest {
	private val first = 9 * 60
	private val last = 18 * 60

	@Test
	fun aFullDayHasNoTimes() {
		assertEquals(Span(null, null), AbsenceSpan.of(Kind.FULL_DAY, null, first, last))
	}

	@Test
	fun aLateEntryCoversTheHoursBeforeIt() {
		assertEquals(Span(first, 90), AbsenceSpan.of(Kind.LATE_ENTRY, first + 90, first, last))
		/* arriving at or before the start misses nothing */
		assertNull(AbsenceSpan.of(Kind.LATE_ENTRY, first, first, last))
		assertNull(AbsenceSpan.of(Kind.LATE_ENTRY, first - 30, first, last))
	}

	@Test
	fun anEarlyExitCoversTheHoursAfterIt() {
		assertEquals(Span(16 * 60, 120), AbsenceSpan.of(Kind.EARLY_EXIT, 16 * 60, first, last))
		assertNull(AbsenceSpan.of(Kind.EARLY_EXIT, last, first, last))
		assertNull(AbsenceSpan.of(Kind.EARLY_EXIT, last + 60, first, last))
	}

	@Test
	fun kindsAreReadByName() {
		assertEquals(Kind.LATE_ENTRY, Kind.of("LATE_ENTRY"))
		assertNull(Kind.of("late"))
	}

	/* several days: a whole-day plan with a last day (issue #158) */
	@Test
	fun aPlanLastingSeveralDays() {
		val zone = ZoneId.of("Europe/Rome")
		val day = LocalDate.of(2026, 10, 19)
		val plan = TodoPlan(day, null, null, day.plusDays(4))
		assertTrue(plan.allDay)
		assertEquals(5, plan.days.let { r -> generateSequence(r.start) { it.plusDays(1) }.takeWhile { it <= r.endInclusive }.count() })
		assertTrue(plan.covers(day.plusDays(4)))
		assertEquals(false, plan.covers(day.plusDays(5)))
		/* over from the day after the last */
		assertEquals(false, plan.over(day.plusDays(4).atTime(23, 59).atZone(zone).toInstant(), zone))
		assertTrue(plan.over(day.plusDays(5).atStartOfDay(zone).toInstant(), zone))
		/* postponing shifts the last day too */
		assertEquals(day.plusDays(6), plan.postponed(2 * 1440).endDay)
		/* a last day needs a whole-day plan, not before the first day */
		assertFailsWith<IllegalArgumentException> { TodoPlan(day, 9 * 60, 60, day.plusDays(1)) }
		assertFailsWith<IllegalArgumentException> { TodoPlan(day, null, null, day.minusDays(1)) }
		assertEquals(day.plusDays(2), TodoPlan.of(day.toEpochDay(), null, null, day.plusDays(2).toEpochDay())?.endDay)
	}
}
