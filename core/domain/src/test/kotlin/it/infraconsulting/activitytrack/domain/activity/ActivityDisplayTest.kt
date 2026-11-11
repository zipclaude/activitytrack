/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.model.SegmentKind.ABSENCE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.PAUSE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.WORK
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/* Priority, absence labels and the corners of the displayed state the shared cases leave out. */
class ActivityDisplayTest {
	private val zone = ZoneId.of("Europe/Rome")
	private val now = Instant.parse("2026-11-11T09:00:00Z")	/* 10:00 local */

	private fun t(s: String) = Instant.parse(s)

	@Test
	fun legacyPriority() {
		val got = listOf(null, -3, 0, 1, 2, 3, 4, 5, 6, 99).map { Priority.fromLegacy(it).value }
		assertEquals(listOf(0, 0, 0, 5, 4, 3, 2, 1, 1, 1), got)
		assertEquals(Priority.EMERGENCY, Priority.of(9))
		assertEquals(Priority.NONE, Priority.of(-1))
	}

	@Test
	fun absenceKindFromTitle() {
		assertEquals(AbsenceKind.HOLIDAY, AbsenceKind.fromTitle(" ferie "))
		assertEquals(AbsenceKind.SICK, AbsenceKind.fromTitle("Sick leave"))
		assertEquals(AbsenceKind.LEAVE, AbsenceKind.fromTitle("PERMISO"))
		assertEquals(AbsenceKind.SICK, AbsenceKind.fromTitle("Krankheit"))
		assertEquals(AbsenceKind.ABSENCE, AbsenceKind.fromTitle("Dentista"))
		assertEquals(AbsenceKind.ABSENCE, AbsenceKind.fromTitle(null))
	}

	@Test
	fun planEndingExactlyNowIsNotLateYet() {
		val a = ActivitySnapshot(ActivityKind.ACTIVITY, ActivityStatus.TODO, plan = TodoPlan(LocalDate.of(2026, 11, 11), 540, 60))
		assertEquals(DisplayState.PLANNED, ActivityDisplay.display(a, now, zone).state)
		assertEquals(DisplayState.LATE, ActivityDisplay.display(a, now.plusMillis(1), zone).state)
	}

	@Test
	fun plannedSegmentsDecideOverThePlan() {
		/* the plan is over, the planned segment is not: not late */
		val a = ActivitySnapshot(
			ActivityKind.ACTIVITY, ActivityStatus.TODO, plan = TodoPlan(LocalDate.of(2026, 11, 10), 480, 60),
			segments = listOf(ActivitySegment(WORK, t("2026-11-11T08:00:00Z"), t("2026-11-11T10:00:00Z"), planned = true)),
		)
		assertEquals(Displayed(DisplayState.PLANNED, t("2026-11-10T07:00:00Z")), ActivityDisplay.display(a, now, zone))
	}

	@Test
	fun suspendedWithoutSegmentsHasNoSince() {
		val a = ActivitySnapshot(ActivityKind.ACTIVITY, ActivityStatus.RUNNING)
		assertEquals(Displayed(DisplayState.SUSPENDED, null), ActivityDisplay.display(a, now, zone))
	}

	@Test
	fun plannedSegmentsDoNotMakeAClosedActivityIncomplete() {
		val a = ActivitySnapshot(
			ActivityKind.ACTIVITY, ActivityStatus.CLOSED, closedAt = t("2026-11-11T08:00:00Z"),
			segments = listOf(ActivitySegment(WORK, t("2026-11-11T10:00:00Z"), t("2026-11-11T11:00:00Z"), planned = true)),
		)
		assertEquals(Displayed(DisplayState.DONE, t("2026-11-11T08:00:00Z")), ActivityDisplay.display(a, now, zone))
	}

	@Test
	fun runningShowsTheOpenSegmentWhateverTheOrder() {
		val a = ActivitySnapshot(
			ActivityKind.ACTIVITY, ActivityStatus.RUNNING,
			segments = listOf(
				ActivitySegment(PAUSE, t("2026-11-11T08:30:00Z"), null),
				ActivitySegment(WORK, t("2026-11-11T07:00:00Z"), t("2026-11-11T08:30:00Z")),
			),
		)
		assertEquals(Displayed(DisplayState.PAUSE, t("2026-11-11T08:30:00Z")), ActivityDisplay.display(a, now, zone))
	}

	@Test
	fun absenceWithoutSegmentsHasNoSince() {
		val a = ActivitySnapshot(ActivityKind.ABSENCE, ActivityStatus.CLOSED, closedAt = now)
		assertEquals(Displayed(DisplayState.ABSENCE, null), ActivityDisplay.display(a, now, zone))
		val b = a.copy(segments = listOf(ActivitySegment(ABSENCE, t("2026-11-13T07:30:00Z"), t("2026-11-13T16:30:00Z")), ActivitySegment(ABSENCE, t("2026-11-12T07:30:00Z"), t("2026-11-12T16:30:00Z"))))
		assertEquals(Displayed(DisplayState.ABSENCE, t("2026-11-12T07:30:00Z")), ActivityDisplay.display(b, now, zone))
	}
}
