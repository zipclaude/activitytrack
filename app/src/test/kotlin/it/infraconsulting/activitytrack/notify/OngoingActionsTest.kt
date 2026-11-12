/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import it.infraconsulting.activitytrack.domain.activity.ActivityCommand
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityState
import it.infraconsulting.activitytrack.domain.activity.ActivityStateMachine
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.model.QuickAction
import java.time.Instant
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OngoingActionsTest {
	private fun running(mode: SegmentKind?) = TimeEntryEntity(
		uid = null, companyId = null, clientId = null, status = ActivityStatus.RUNNING, mode = mode, zoneId = "UTC", createdUtcMs = 0, updatedUtcMs = 0,
	)

	/* no "Sospendi" anywhere (3); from a pause WORK and TRAVEL; from WORK a pause, a travel (same activity) and stop */
	@Test
	fun actionsFollowTheMode() {
		assertEquals(listOf(QuickAction.PAUSE, QuickAction.TRAVEL, QuickAction.STOP), ongoingActions(running(SegmentKind.WORK)))
		assertEquals(listOf(QuickAction.WORK, QuickAction.TRAVEL, QuickAction.STOP), ongoingActions(running(SegmentKind.PAUSE)))
		assertEquals(listOf(QuickAction.WORK, QuickAction.TRAVEL, QuickAction.STOP), ongoingActions(running(null)))
	}

	/* every action offered on the notification must be legal for the state machine */
	@Test
	fun actionsAreLegalTransitions() {
		for (mode in SegmentKind.entries - SegmentKind.ABSENCE) {	/* an absence segment is never open */
			val actions = ongoingActions(running(mode))
			assertTrue(actions.isNotEmpty())
			assertTrue(actions.size <= 3, "notifications show at most 3 actions")
			val state = ActivityState(ActivityKind.ACTIVITY, ActivityStatus.RUNNING, mode)
			val at = Instant.EPOCH
			/* the buttons as EntryRepository.quick maps them on a running activity */
			actions.forEach {
				val cmd = when (it) {
					QuickAction.WORK, QuickAction.TRAVEL, QuickAction.PAUSE -> ActivityCommand.Switch(SegmentKind.valueOf(it.name), at)
									QuickAction.STOP -> ActivityCommand.Stop(at)
				}
				ActivityStateMachine.apply(state, cmd, at)
			}
		}
	}
}
