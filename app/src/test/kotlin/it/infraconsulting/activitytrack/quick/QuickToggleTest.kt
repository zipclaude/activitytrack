/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.quick

import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.model.QuickAction
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals

/* The tile and the 1×1 widget: pause, or "Riprendi" in the kind open before the pause (ACTIVITY-MODEL.md 3) */
class QuickToggleTest {
	private fun running(mode: SegmentKind) =
		TimeEntryEntity(id = 1, uid = null, companyId = null, clientId = null, status = ActivityStatus.RUNNING, mode = mode, zoneId = "UTC", createdUtcMs = 0, updatedUtcMs = 0)

	private fun seg(kind: SegmentKind, from: Long, to: Long?) =
		SegmentEntity(entryId = 1, kind = kind, startUtcMs = from, endUtcMs = to, zoneId = "UTC")

	@Test
	fun resumesTheKindBeforeThePause() {
		val travel = listOf(seg(SegmentKind.WORK, 0, 60_000), seg(SegmentKind.TRAVEL, 60_000, 120_000), seg(SegmentKind.PAUSE, 120_000, null))
		assertEquals(QuickAction.TRAVEL, QuickToggle.action(running(SegmentKind.PAUSE), travel))
		val work = listOf(seg(SegmentKind.TRAVEL, 0, 60_000), seg(SegmentKind.WORK, 60_000, 120_000), seg(SegmentKind.PAUSE, 120_000, null))
		assertEquals(QuickAction.WORK, QuickToggle.action(running(SegmentKind.PAUSE), work))
	}

	@Test
	fun pausesWorkAndTravel() {
		assertEquals(QuickAction.PAUSE, QuickToggle.action(running(SegmentKind.TRAVEL), listOf(seg(SegmentKind.TRAVEL, 0, null))))
		assertEquals(QuickAction.PAUSE, QuickToggle.action(running(SegmentKind.WORK), listOf(seg(SegmentKind.WORK, 0, null))))
	}
}
