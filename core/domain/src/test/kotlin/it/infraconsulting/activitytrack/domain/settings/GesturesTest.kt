/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
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
		assertEquals(EntrySwipe.STOP_OR_SUSPEND, g.entryToEnd)
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
	fun pastCommandsFollowTheState() {
		assertEquals(listOf(EntryCommand.COMPLETE), Gestures.pastCommands(PastSwipe.COMPLETE, EntryStatus.TO_COMPLETE))
		assertEquals(emptyList(), Gestures.pastCommands(PastSwipe.COMPLETE, EntryStatus.CLOSED))
		assertEquals(listOf(EntryCommand.REOPEN), Gestures.pastCommands(PastSwipe.REOPEN, EntryStatus.CLOSED))
		assertEquals(listOf(EntryCommand.REOPEN), Gestures.pastCommands(PastSwipe.REOPEN, EntryStatus.TO_COMPLETE))
		assertEquals(emptyList(), Gestures.pastCommands(PastSwipe.REOPEN, EntryStatus.ACTIVE))
	}

	@Test
	fun entryCommandsFollowTheState() {
		val pr = EntrySwipe.PAUSE_RESUME
		assertEquals(listOf(EntryCommand.PAUSE), Gestures.entryCommands(pr, EntryStatus.ACTIVE, SegmentKind.WORK))
		assertEquals(listOf(EntryCommand.PAUSE), Gestures.entryCommands(pr, EntryStatus.ACTIVE, SegmentKind.TRAVEL))
		assertEquals(listOf(EntryCommand.WORK), Gestures.entryCommands(pr, EntryStatus.ACTIVE, SegmentKind.PAUSE))
		assertEquals(listOf(EntryCommand.WORK), Gestures.entryCommands(pr, EntryStatus.SUSPENDED, null))

		val ss = EntrySwipe.STOP_OR_SUSPEND
		assertEquals(listOf(EntryCommand.STOP, EntryCommand.SUSPEND), Gestures.entryCommands(ss, EntryStatus.ACTIVE, SegmentKind.WORK))
		/* no suspension from a pause or a suspension */
		assertEquals(listOf(EntryCommand.STOP), Gestures.entryCommands(ss, EntryStatus.ACTIVE, SegmentKind.PAUSE))
		assertEquals(listOf(EntryCommand.STOP), Gestures.entryCommands(ss, EntryStatus.SUSPENDED, null))
		/* closed or to complete: nothing */
		assertEquals(emptyList(), Gestures.entryCommands(ss, EntryStatus.TO_COMPLETE, null))
		assertEquals(emptyList(), Gestures.entryCommands(pr, EntryStatus.CLOSED, null))
	}
}
