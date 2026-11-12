/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind.ABSENCE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.PAUSE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.TRAVEL
import it.infraconsulting.activitytrack.domain.model.SegmentKind.WORK
import kotlin.test.Test
import kotlin.test.assertEquals

/* The segments typed by hand (detail dialog and EntryRepository.addManualSegment) */
class ManualSegmentKindsTest {
	private fun e(status: ActivityStatus, kind: ActivityKind = ActivityKind.ACTIVITY) = TimeEntryEntity(
		companyId = 1, clientId = 1, status = status, kind = kind, zoneId = "UTC", createdUtcMs = 0, updatedUtcMs = 0,
		absenceKind = AbsenceKind.HOLIDAY.takeIf { kind == ActivityKind.ABSENCE },
	)

	@Test
	fun editableTimes() {
		assertEquals(listOf(TRAVEL, PAUSE), manualSegmentKinds(e(ActivityStatus.RUNNING), true))
		assertEquals(listOf(WORK, TRAVEL, PAUSE), manualSegmentKinds(e(ActivityStatus.CLOSED), true))
		assertEquals(listOf(ABSENCE), manualSegmentKinds(e(ActivityStatus.CLOSED, ActivityKind.ABSENCE), true))
	}

	@Test
	fun lockedTimesAllowNoRealSegmentButDeclaredOnes() {
		assertEquals(emptyList(), manualSegmentKinds(e(ActivityStatus.RUNNING), false))
		assertEquals(emptyList(), manualSegmentKinds(e(ActivityStatus.CLOSED), false))
		/* an absence's days and a to-do's plan are declared, not recorded */
		assertEquals(listOf(ABSENCE), manualSegmentKinds(e(ActivityStatus.CLOSED, ActivityKind.ABSENCE), false))
		assertEquals(listOf(WORK, TRAVEL, PAUSE), manualSegmentKinds(e(ActivityStatus.TODO), false))
	}
}
