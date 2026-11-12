/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/* A to-do marked done in the management system (PluginSync): the tick transition, planned segments and all */
class RemoteTickTest {
	private val todo = TimeEntryEntity(companyId = 1, clientId = null, status = ActivityStatus.TODO, externalKey = "T-1", zoneId = "UTC", createdUtcMs = 1, updatedUtcMs = 1)

	@Test
	fun aTodoIsTickedOffAndLosesItsPlannedSegments() {
		val (ticked, deletePlanned) = remoteTick(todo, 5_000)!!
		assertEquals(ActivityStatus.CLOSED, ticked.status)
		assertEquals(5_000L, ticked.closedUtcMs)
		assertNull(ticked.mode)
		assertEquals(true, deletePlanned)
	}

	@Test
	fun theRemoteNeverClosesWhatTheUserStartedClosedOrDeleted() {
		assertNull(remoteTick(todo.copy(status = ActivityStatus.RUNNING, mode = SegmentKind.WORK), 5_000))
		assertNull(remoteTick(todo.copy(status = ActivityStatus.CLOSED, closedUtcMs = 2), 5_000))
		assertNull(remoteTick(todo.copy(deletedUtcMs = 3), 5_000))
		assertNull(remoteTick(todo.copy(kind = ActivityKind.ABSENCE, status = ActivityStatus.CLOSED), 5_000))
	}
}
