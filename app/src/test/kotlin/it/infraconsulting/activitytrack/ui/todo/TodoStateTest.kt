/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.todo

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/* The one state a to-do shows (issue #89). */
class TodoStateTest {
	private val now = LocalDate.of(2026, 10, 27).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant()

	private fun todo(planDay: LocalDate? = null, startMin: Int? = null, done: Boolean = false, ignoredMs: Long? = null) =
		todoRow(planEpochDay = planDay?.toEpochDay(), planStartMin = startMin, done = done, ignoredUtcMs = ignoredMs)

	@Test
	fun doneWinsOverPlanned() {
		/* the issue: done, planned in the past and in the future, both showed "planned" */
		assertEquals(TodoState.DONE, todoState(todo(LocalDate.of(2026, 10, 21), 19 * 60 + 45, done = true), now))
		assertEquals(TodoState.DONE, todoState(todo(LocalDate.of(2026, 10, 29), 14 * 60, done = true), now))
	}

	@Test
	fun openOnesFollowTheirDates() {
		assertEquals(TodoState.OVERDUE, todoState(todo(LocalDate.of(2026, 10, 21), 9 * 60), now))
		assertEquals(TodoState.PLANNED, todoState(todo(LocalDate.of(2026, 10, 29), 9 * 60), now))
		assertEquals(TodoState.OPEN, todoState(todo(), now))
		assertEquals(TodoState.IGNORED, todoState(todo(LocalDate.of(2026, 10, 21), 9 * 60, ignoredMs = 1), now))
	}
}
