/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

import it.infraconsulting.activitytrack.domain.model.QuickAction
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals

class GesturesTest {
	@Test
	fun defaultsMatchTheOwnerDecisions() {
		val g = GestureSettings()
		/* one rule for every row (owner, 2026-10-27): forward to the start, back to the end */
		assertEquals(TodoSwipe.START_OR_DONE, g.todoToStart)
		assertEquals(PastSwipe.COMPLETE, g.pastToStart)
		assertEquals(PastSwipe.REOPEN, g.pastToEnd)
		assertEquals(TodoSwipe.POSTPONE_OR_IGNORE, g.todoToEnd)
		assertEquals(EntrySwipe.PAUSE_RESUME, g.entryToStart)
		assertEquals(EntrySwipe.STOP, g.entryToEnd)
	}

	@Test
	fun todoActions() {
		assertEquals(listOf(TodoAction.START), Gestures.todoActions(TodoSwipe.START, ignored = false, planned = false, done = false))
		assertEquals(listOf(TodoAction.POSTPONE, TodoAction.IGNORE), Gestures.todoActions(TodoSwipe.POSTPONE_OR_IGNORE, false, planned = true, done = false))
		/* nothing to postpone without a plan */
		assertEquals(listOf(TodoAction.IGNORE), Gestures.todoActions(TodoSwipe.POSTPONE_OR_IGNORE, false, planned = false, done = false))
		/* an ignored to-do only offers Restore, whatever the swipe */
		assertEquals(listOf(TodoAction.RESTORE), Gestures.todoActions(TodoSwipe.START, ignored = true, planned = true, done = false))
		assertEquals(emptyList(), Gestures.todoActions(TodoSwipe.NONE, ignored = true, planned = true, done = false))
		assertEquals(emptyList(), Gestures.todoActions(TodoSwipe.START, ignored = false, planned = false, done = true))
		assertEquals(listOf(TodoAction.START, TodoAction.DONE), Gestures.todoActions(TodoSwipe.START_OR_DONE, false, planned = true, done = false))
		/* a done one: Reopen on the "put off" side only */
		assertEquals(listOf(TodoAction.REOPEN), Gestures.todoActions(TodoSwipe.POSTPONE_OR_IGNORE, false, planned = true, done = true))
		assertEquals(emptyList(), Gestures.todoActions(TodoSwipe.START_OR_DONE, false, planned = true, done = true))
	}

	@Test
	fun entryActionsFollowTheState() {
		val pr = EntrySwipe.PAUSE_RESUME
		assertEquals(listOf(QuickAction.PAUSE), Gestures.entryActions(pr, ActivityStatus.RUNNING, SegmentKind.WORK))
		assertEquals(listOf(QuickAction.PAUSE), Gestures.entryActions(pr, ActivityStatus.RUNNING, SegmentKind.TRAVEL))
		assertEquals(listOf(QuickAction.WORK), Gestures.entryActions(pr, ActivityStatus.RUNNING, SegmentKind.PAUSE))
		assertEquals(listOf(QuickAction.WORK), Gestures.entryActions(pr, ActivityStatus.RUNNING, null))

		val stop = EntrySwipe.STOP
		assertEquals(listOf(QuickAction.STOP), Gestures.entryActions(stop, ActivityStatus.RUNNING, SegmentKind.WORK))
		assertEquals(listOf(QuickAction.STOP), Gestures.entryActions(stop, ActivityStatus.RUNNING, SegmentKind.PAUSE))
		assertEquals(listOf(QuickAction.STOP), Gestures.entryActions(stop, ActivityStatus.RUNNING, null))
		/* closed or to do: nothing */
		assertEquals(emptyList(), Gestures.entryActions(stop, ActivityStatus.TODO, null))
		assertEquals(emptyList(), Gestures.entryActions(pr, ActivityStatus.CLOSED, null))
	}

	@Test
	fun storedSuspendChoicesBecomeStop() {
		assertEquals(EntrySwipe.STOP, EntrySwipe.parse("STOP_OR_SUSPEND"))
		assertEquals(EntrySwipe.STOP, EntrySwipe.parse("SUSPEND"))
		assertEquals(EntrySwipe.PAUSE_RESUME, EntrySwipe.parse("PAUSE_RESUME"))
		assertEquals(null, EntrySwipe.parse("nonsense"))
		assertEquals(null, EntrySwipe.parse(null))
	}
}
