/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

/* User commands on a single entry. */
enum class EntryCommand {
	WORK,		/* (re)enter WORK mode */
	TRAVEL,		/* enter TRAVEL mode */
	PAUSE,		/* enter PAUSE mode */
	SUSPEND,	/* close the open segment, keep the entry resumable */
	STOP,		/* close the open segment, ask for details */
	COMPLETE,	/* details provided */
	REOPEN,		/* edit a stopped entry back to suspended */
}

/* Current state of an entry; [mode] is non-null only when ACTIVE. */
data class EntryState(
	val status: EntryStatus,
	val mode: SegmentKind?,
) {
	init {
		require((status == EntryStatus.ACTIVE) == (mode != null)) {
			"mode must be set if and only if status is ACTIVE"
		}
	}

	companion object {
		val SUSPENDED = EntryState(EntryStatus.SUSPENDED, null)
		val TO_COMPLETE = EntryState(EntryStatus.TO_COMPLETE, null)
		val CLOSED = EntryState(EntryStatus.CLOSED, null)
		fun active(mode: SegmentKind) = EntryState(EntryStatus.ACTIVE, mode)
	}
}

/*
 * Outcome of a transition: the new state, whether the open segment must be
 * closed and which segment kind (if any) must be opened.
 */
data class Transition(
	val next: EntryState,
	val closeOpenSegment: Boolean,
	val openSegment: SegmentKind?,
)

class IllegalTransitionException(state: EntryState, cmd: EntryCommand) :
	IllegalStateException("command $cmd not allowed in state $state")

object EntryStateMachine {
	/* State for a newly created entry. */
	fun start(mode: SegmentKind): Transition {
		require(mode != SegmentKind.PAUSE) { "an entry cannot start in PAUSE" }
		return Transition(EntryState.active(mode), closeOpenSegment = false, openSegment = mode)
	}

	fun apply(state: EntryState, cmd: EntryCommand): Transition {
		val active = state.status == EntryStatus.ACTIVE
		return when (cmd) {
			EntryCommand.WORK, EntryCommand.TRAVEL, EntryCommand.PAUSE -> {
				val kind = SegmentKind.valueOf(cmd.name)
				when {
					active && state.mode == kind -> throw IllegalTransitionException(state, cmd)
					active -> Transition(EntryState.active(kind), true, kind)
					state.status == EntryStatus.SUSPENDED && kind != SegmentKind.PAUSE ->
						Transition(EntryState.active(kind), false, kind)
					else -> throw IllegalTransitionException(state, cmd)
				}
			}
			EntryCommand.SUSPEND ->
				if (active && state.mode != SegmentKind.PAUSE)
					Transition(EntryState.SUSPENDED, true, null)
				else
					throw IllegalTransitionException(state, cmd)
			EntryCommand.STOP ->
				when (state.status) {
					EntryStatus.ACTIVE -> Transition(EntryState.TO_COMPLETE, true, null)
					EntryStatus.SUSPENDED -> Transition(EntryState.TO_COMPLETE, false, null)
					else -> throw IllegalTransitionException(state, cmd)
				}
			EntryCommand.COMPLETE ->
				if (state.status == EntryStatus.TO_COMPLETE)
					Transition(EntryState.CLOSED, false, null)
				else
					throw IllegalTransitionException(state, cmd)
			EntryCommand.REOPEN ->
				if (state.status == EntryStatus.TO_COMPLETE || state.status == EntryStatus.CLOSED)
					Transition(EntryState.SUSPENDED, false, null)
				else
					throw IllegalTransitionException(state, cmd)
		}
	}
}
