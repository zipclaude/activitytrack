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
import kotlin.test.Test
import kotlin.test.assertTrue

class OngoingActionsTest {
	/* every action offered on the notification must be legal for the state machine */
	@Test
	fun actionsAreLegalTransitions() {
		for (mode in SegmentKind.entries - SegmentKind.ABSENCE) {	/* an absence segment is never open */
			val actions = ongoingActions(mode)
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
