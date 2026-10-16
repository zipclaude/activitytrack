/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

import it.infraconsulting.activitytrack.domain.model.EntryCommand.COMPLETE
import it.infraconsulting.activitytrack.domain.model.EntryCommand.PAUSE
import it.infraconsulting.activitytrack.domain.model.EntryCommand.STOP
import it.infraconsulting.activitytrack.domain.model.EntryCommand.SUSPEND
import it.infraconsulting.activitytrack.domain.model.EntryCommand.TRAVEL
import it.infraconsulting.activitytrack.domain.model.EntryCommand.WORK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EntryStateMachineTest {
	@Test
	fun travelWorkPauseStopComplete() {
		var t = EntryStateMachine.start(SegmentKind.TRAVEL)
		assertEquals(EntryState.active(SegmentKind.TRAVEL), t.next)

		t = EntryStateMachine.apply(t.next, WORK)
		assertEquals(Transition(EntryState.active(SegmentKind.WORK), true, SegmentKind.WORK), t)

		t = EntryStateMachine.apply(t.next, PAUSE)
		assertEquals(SegmentKind.PAUSE, t.openSegment)

		t = EntryStateMachine.apply(t.next, WORK)
		t = EntryStateMachine.apply(t.next, STOP)
		assertEquals(Transition(EntryState.TO_COMPLETE, true, null), t)

		t = EntryStateMachine.apply(t.next, COMPLETE)
		assertEquals(EntryState.CLOSED, t.next)
	}

	@Test
	fun suspendAndResume() {
		val t = EntryStateMachine.apply(EntryState.active(SegmentKind.WORK), SUSPEND)
		assertEquals(Transition(EntryState.SUSPENDED, true, null), t)
		val r = EntryStateMachine.apply(t.next, WORK)
		assertEquals(Transition(EntryState.active(SegmentKind.WORK), false, SegmentKind.WORK), r)
		val s = EntryStateMachine.apply(t.next, STOP)
		assertEquals(Transition(EntryState.TO_COMPLETE, false, null), s)
	}

	@Test
	fun illegalTransitions() {
		assertFailsWith<IllegalArgumentException> { EntryStateMachine.start(SegmentKind.PAUSE) }
		assertFailsWith<IllegalTransitionException> {
			EntryStateMachine.apply(EntryState.active(SegmentKind.WORK), WORK)
		}
		assertFailsWith<IllegalTransitionException> {
			EntryStateMachine.apply(EntryState.active(SegmentKind.PAUSE), SUSPEND)
		}
		assertFailsWith<IllegalTransitionException> {
			EntryStateMachine.apply(EntryState.SUSPENDED, PAUSE)
		}
		assertFailsWith<IllegalTransitionException> {
			EntryStateMachine.apply(EntryState.CLOSED, TRAVEL)
		}
	}

	@Test
	fun externalRefClassification() {
		assertEquals(ExternalRefKind.URL, ExternalRefClassifier.classify("https://jira.example.com/browse/OPS-12"))
		assertEquals(ExternalRefKind.UUID, ExternalRefClassifier.classify("3f2b8c1e-9a4d-4e2f-8b7a-1c2d3e4f5a6b"))
		assertEquals(ExternalRefKind.TICKET, ExternalRefClassifier.classify("INC0012345"))
		assertEquals(ExternalRefKind.TICKET, ExternalRefClassifier.classify("OPS-42"))
		assertEquals(ExternalRefKind.TICKET, ExternalRefClassifier.classify("#1234"))
		assertEquals(ExternalRefKind.OTHER, ExternalRefClassifier.classify("PO Acme 2026/Q4"))
	}
}
