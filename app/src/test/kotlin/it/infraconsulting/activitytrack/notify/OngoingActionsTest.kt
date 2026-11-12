/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import it.infraconsulting.activitytrack.domain.activity.ActivityCommand
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityState
import it.infraconsulting.activitytrack.domain.activity.ActivityStateMachine
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.model.EntryCommand
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
	fun noSuspendButton() {
		assertEquals(listOf(EntryCommand.PAUSE, EntryCommand.TRAVEL, EntryCommand.STOP), ongoingActions(running(SegmentKind.WORK)))
		assertEquals(listOf(EntryCommand.WORK, EntryCommand.TRAVEL, EntryCommand.STOP), ongoingActions(running(SegmentKind.PAUSE)))
		assertEquals(listOf(EntryCommand.WORK, EntryCommand.TRAVEL, EntryCommand.STOP), ongoingActions(running(null)))
		for (m in listOf(SegmentKind.WORK, SegmentKind.TRAVEL, SegmentKind.PAUSE, null))
			assertTrue(EntryCommand.SUSPEND !in ongoingActions(running(m)))
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
			/* the buttons as EntryRepository.apply maps them on a running activity */
			actions.forEach {
				val cmd = when (it) {
					EntryCommand.WORK, EntryCommand.TRAVEL, EntryCommand.PAUSE -> ActivityCommand.Switch(SegmentKind.valueOf(it.name), at)
					EntryCommand.SUSPEND -> ActivityCommand.Suspend(at)
					EntryCommand.STOP -> ActivityCommand.Stop(at)
					EntryCommand.REOPEN -> ActivityCommand.Reopen(SegmentKind.WORK, at)
				}
				ActivityStateMachine.apply(state, cmd, at)
			}
		}
	}
}
