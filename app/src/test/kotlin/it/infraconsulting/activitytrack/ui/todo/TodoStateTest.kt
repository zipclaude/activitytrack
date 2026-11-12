/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.todo

import it.infraconsulting.activitytrack.domain.activity.DisplayState
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/* The one state a to-do shows (issue #89, ACTIVITY-MODEL.md 2.2). */
class TodoStateTest {
	private val now = LocalDate.of(2026, 10, 27).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant()

	private fun todo(planDay: LocalDate? = null, startMin: Int? = null, done: Boolean = false, ignoredMs: Long? = null) =
		todoRow(planEpochDay = planDay?.toEpochDay(), planStartMin = startMin, done = done, ignoredUtcMs = ignoredMs)

	@Test
	fun doneWinsOverPlanned() {
		/* the issue: done, planned in the past and in the future, both showed "planned" */
		assertEquals(DisplayState.DONE, displayOf(todo(LocalDate.of(2026, 10, 21), 19 * 60 + 45, done = true), now))
		assertEquals(DisplayState.DONE, displayOf(todo(LocalDate.of(2026, 10, 29), 14 * 60, done = true), now))
	}

	@Test
	fun openOnesFollowTheirDates() {
		assertEquals(DisplayState.LATE, displayOf(todo(LocalDate.of(2026, 10, 21), 9 * 60), now))
		assertEquals(DisplayState.PLANNED, displayOf(todo(LocalDate.of(2026, 10, 29), 9 * 60), now))
		assertEquals(DisplayState.TODO, displayOf(todo(), now))
		assertEquals(DisplayState.DELETED, displayOf(todo(LocalDate.of(2026, 10, 21), 9 * 60, ignoredMs = 1), now))
	}
}
