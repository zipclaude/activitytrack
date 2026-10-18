/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind

/* What a swipe on a to-do card offers (M10, owner decisions 2026-10-18). */
enum class TodoSwipe { START, POSTPONE_OR_IGNORE, POSTPONE, IGNORE, DONE, REPLAN, NONE }

/* What a swipe on an open entry card offers. */
enum class EntrySwipe { PAUSE_RESUME, STOP_OR_SUSPEND, STOP, SUSPEND, NONE }

/* One revealed button of a to-do swipe. */
enum class TodoAction { START, POSTPONE, IGNORE, RESTORE, DONE, REPLAN }

/*
 * Swipe gestures per card type and direction. Directions are layout
 * relative (toward the start / end edge), so right-to-left layouts keep the
 * meaning; in Italian or English "toward start" is a swipe to the left.
 * [fullSwipe]: a long swipe runs the first revealed action at once.
 */
data class GestureSettings(
	val todoToStart: TodoSwipe = TodoSwipe.START,
	val todoToEnd: TodoSwipe = TodoSwipe.POSTPONE_OR_IGNORE,
	val entryToStart: EntrySwipe = EntrySwipe.PAUSE_RESUME,
	val entryToEnd: EntrySwipe = EntrySwipe.STOP_OR_SUSPEND,
	val fullSwipe: Boolean = true,
)

object Gestures {
	/* The buttons a to-do swipe reveals, first = the long-swipe action; an ignored to-do only offers Restore. */
	fun todoActions(swipe: TodoSwipe, ignored: Boolean, planned: Boolean, done: Boolean): List<TodoAction> {
		if (swipe == TodoSwipe.NONE)
			return emptyList()
		if (ignored)
			return listOf(TodoAction.RESTORE)
		if (done)
			return emptyList()
		return when (swipe) {
			TodoSwipe.START -> listOf(TodoAction.START)
			TodoSwipe.POSTPONE_OR_IGNORE -> listOfNotNull(TodoAction.POSTPONE.takeIf { planned }, TodoAction.IGNORE)
			TodoSwipe.POSTPONE -> listOfNotNull(TodoAction.POSTPONE.takeIf { planned })
			TodoSwipe.IGNORE -> listOf(TodoAction.IGNORE)
			TodoSwipe.DONE -> listOf(TodoAction.DONE)
			TodoSwipe.REPLAN -> listOf(TodoAction.REPLAN)
			TodoSwipe.NONE -> emptyList()
		}
	}

	/*
	 * The commands an entry swipe reveals, among those valid in its state:
	 * pause while working or travelling, resume (WORK) from a pause or a
	 * suspension; stop and suspend where the state machine allows them.
	 */
	fun entryCommands(swipe: EntrySwipe, status: EntryStatus, mode: SegmentKind?): List<EntryCommand> {
		val running = status == EntryStatus.ACTIVE && (mode == SegmentKind.WORK || mode == SegmentKind.TRAVEL)
		val resting = (status == EntryStatus.ACTIVE && mode == SegmentKind.PAUSE) || status == EntryStatus.SUSPENDED
		val stop = EntryCommand.STOP.takeIf { status == EntryStatus.ACTIVE || status == EntryStatus.SUSPENDED }
		val suspend = EntryCommand.SUSPEND.takeIf { running }
		return when (swipe) {
			EntrySwipe.PAUSE_RESUME -> listOfNotNull(
				EntryCommand.PAUSE.takeIf { running },
				EntryCommand.WORK.takeIf { resting },
			)
			EntrySwipe.STOP_OR_SUSPEND -> listOfNotNull(stop, suspend)
			EntrySwipe.STOP -> listOfNotNull(stop)
			EntrySwipe.SUSPEND -> listOfNotNull(suspend)
			EntrySwipe.NONE -> emptyList()
		}
	}
}
