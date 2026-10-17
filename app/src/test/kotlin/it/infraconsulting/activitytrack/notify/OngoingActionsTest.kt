/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import it.infraconsulting.activitytrack.domain.model.EntryState
import it.infraconsulting.activitytrack.domain.model.EntryStateMachine
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import kotlin.test.Test
import kotlin.test.assertTrue

class OngoingActionsTest {
	/* every action offered on the notification must be legal for the state machine */
	@Test
	fun actionsAreLegalTransitions() {
		for (mode in SegmentKind.entries) {
			val actions = ongoingActions(mode)
			assertTrue(actions.isNotEmpty())
			assertTrue(actions.size <= 3, "notifications show at most 3 actions")
			actions.forEach { EntryStateMachine.apply(EntryState.active(mode), it) }
		}
	}
}
