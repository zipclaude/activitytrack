/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind

/* What a swipe on a to-do card offers (M10, owner decisions 2026-10-18). */
enum class TodoSwipe { START, START_OR_DONE, POSTPONE_OR_IGNORE, POSTPONE, IGNORE, DONE, REPLAN, NONE }

/* What a swipe on an open entry card offers. */
enum class EntrySwipe { PAUSE_RESUME, STOP_OR_SUSPEND, STOP, SUSPEND, NONE }

/* What a swipe on a stopped entry (to complete or closed) offers on the Home list (one rule for every row). */
enum class PastSwipe { COMPLETE, REOPEN, NONE }

/* One revealed button of a to-do swipe. */
enum class TodoAction { START, POSTPONE, IGNORE, RESTORE, DONE, REPLAN, REOPEN }

/*
 * Swipe gestures per card type and direction. Directions are layout
 * relative (toward the start / end edge), so right-to-left layouts keep the
 * meaning; in Italian or English "toward start" is a swipe to the left.
 * [fullSwipe]: a long swipe runs the first revealed action at once.
 */
/*
 * One rule for every row (owner, 2026-10-27): toward the start the step
 * forward (start, done, complete), toward the end put off or go back
 * (postpone, ignore, reopen). Each stays configurable.
 */
data class GestureSettings(
	val todoToStart: TodoSwipe = TodoSwipe.START_OR_DONE,
	val todoToEnd: TodoSwipe = TodoSwipe.POSTPONE_OR_IGNORE,
	val entryToStart: EntrySwipe = EntrySwipe.PAUSE_RESUME,
	val entryToEnd: EntrySwipe = EntrySwipe.STOP_OR_SUSPEND,
	val pastToStart: PastSwipe = PastSwipe.COMPLETE,
	val pastToEnd: PastSwipe = PastSwipe.REOPEN,
	val fullSwipe: Boolean = true,
)

object Gestures {
	/*
	 * The buttons a to-do swipe reveals, first = the long-swipe action; an
	 * ignored to-do only offers Restore, a done one Reopen on the "put off"
	 * side (postpone or ignore), nothing on the other.
	 */
	fun todoActions(swipe: TodoSwipe, ignored: Boolean, planned: Boolean, done: Boolean): List<TodoAction> {
		if (swipe == TodoSwipe.NONE)
			return emptyList()
		if (done)
			return if (swipe in BACK) listOf(TodoAction.REOPEN) else emptyList()
		if (ignored)
			return listOf(TodoAction.RESTORE)
		return when (swipe) {
			TodoSwipe.START -> listOf(TodoAction.START)
			TodoSwipe.START_OR_DONE -> listOf(TodoAction.START, TodoAction.DONE)
			TodoSwipe.POSTPONE_OR_IGNORE -> listOfNotNull(TodoAction.POSTPONE.takeIf { planned }, TodoAction.IGNORE)
			TodoSwipe.POSTPONE -> listOfNotNull(TodoAction.POSTPONE.takeIf { planned })
			TodoSwipe.IGNORE -> listOf(TodoAction.IGNORE)
			TodoSwipe.DONE -> listOf(TodoAction.DONE)
			TodoSwipe.REPLAN -> listOf(TodoAction.REPLAN)
			TodoSwipe.NONE -> emptyList()
		}
	}

	private val BACK = setOf(TodoSwipe.POSTPONE_OR_IGNORE, TodoSwipe.POSTPONE, TodoSwipe.IGNORE)

	/*
	 * What a swipe on a closed activity offers: reopen it. COMPLETE (an
	 * incomplete one's details) is no command of the activity model 1.0: the
	 * screen opens the details itself. TODO(activity-1.0-ui)
	 */
	fun pastCommands(swipe: PastSwipe, status: ActivityStatus): List<EntryCommand> = when (swipe) {
		PastSwipe.COMPLETE -> emptyList()
		PastSwipe.REOPEN -> listOfNotNull(EntryCommand.REOPEN.takeIf { status == ActivityStatus.CLOSED })
		PastSwipe.NONE -> emptyList()
	}

	/*
	 * The commands an entry swipe reveals, among those valid in its state:
	 * pause while working or travelling, resume (WORK) from a pause or a
	 * suspension; stop and suspend where the state machine allows them.
	 */
	fun entryCommands(swipe: EntrySwipe, status: ActivityStatus, mode: SegmentKind?): List<EntryCommand> {
		val live = status == ActivityStatus.RUNNING
		val running = live && (mode == SegmentKind.WORK || mode == SegmentKind.TRAVEL)
		val resting = live && (mode == SegmentKind.PAUSE || mode == null)
		val stop = EntryCommand.STOP.takeIf { live }
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
