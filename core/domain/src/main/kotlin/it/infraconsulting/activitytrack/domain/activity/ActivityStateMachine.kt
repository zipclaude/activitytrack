/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.time.Instant

/*
 * The stored state of an activity the commands depend on (1.1): [mode] is
 * the kind of the open segment, null when none is open (only RUNNING may
 * have one); [closedAt] and [deletedAt] as `closed_utc_ms`, `deleted_utc_ms`.
 */
data class ActivityState(
	val kind: ActivityKind,
	val status: ActivityStatus,
	val mode: SegmentKind? = null,
	val closedAt: Instant? = null,
	val deletedAt: Instant? = null,
) {
	init {
		require(mode == null || status == ActivityStatus.RUNNING) { "an open segment ($mode) needs RUNNING, not $status" }
		require(mode != SegmentKind.ABSENCE) { "an absence segment is never open" }
		require(kind == ActivityKind.ACTIVITY || status == ActivityStatus.CLOSED) { "an absence is always CLOSED, not $status" }
	}

	val deleted: Boolean get() = deletedAt != null
}

/* The commands of section 3; [at] is when the segments change, [now] (passed apart) when the activity is closed or deleted. */
sealed interface ActivityCommand {
	/* TODO -> RUNNING: the planned segments go, [kind] opens at [at] */
	data class Start(val kind: SegmentKind, val at: Instant) : ActivityCommand

	/* RUNNING with another kind open: close it and open [kind] at [at] */
	data class Switch(val kind: SegmentKind, val at: Instant) : ActivityCommand

	/* RUNNING, none open: open [kind] at [at] */
	data class Resume(val kind: SegmentKind, val at: Instant) : ActivityCommand

	/* RUNNING, one open: close it at [at] (internal: another activity started) */
	data class Suspend(val at: Instant) : ActivityCommand

	/* RUNNING -> CLOSED: close the open one at [at] */
	data class Stop(val at: Instant) : ActivityCommand

	/* TODO -> CLOSED: a task ticked off, the planned segments go */
	data object Tick : ActivityCommand

	/* CLOSED -> RUNNING: open [kind] at [at]; the gap stays a gap */
	data class Reopen(val kind: SegmentKind, val at: Instant) : ActivityCommand

	/* any -> the same, deleted ("eliminata", restorable) */
	data object Delete : ActivityCommand

	/* deleted -> the same, not deleted */
	data object Restore : ActivityCommand
}

/* A segment to open: [kind] from [at], open. */
data class OpenSegment(val kind: SegmentKind, val at: Instant)

/*
 * What a command does: the activity's [next] state and the segments to
 * change, applied in this order in one transaction: delete the planned
 * segments ([deletePlanned]), close the open one at [closeAt], open [open].
 */
data class ActivityTransition(
	val next: ActivityState,
	val deletePlanned: Boolean = false,
	val closeAt: Instant? = null,
	val open: OpenSegment? = null,
)

class IllegalActivityCommandException(state: ActivityState, cmd: ActivityCommand) :
	IllegalStateException("command $cmd not allowed in state $state")

object ActivityStateMachine {
	/* The kinds an activity may be started, resumed or reopened in. */
	val STARTING = setOf(SegmentKind.WORK, SegmentKind.TRAVEL)

	/*
	 * The transition of [cmd] from [state]; throws when it is not allowed.
	 * A deleted activity takes only Restore; an absence only Delete and
	 * Restore (its segments are edited, it never runs).
	 */
	fun apply(state: ActivityState, cmd: ActivityCommand, now: Instant): ActivityTransition {
		fun illegal(): Nothing = throw IllegalActivityCommandException(state, cmd)
		if (state.deleted && cmd != ActivityCommand.Restore)
			illegal()
		if (state.kind == ActivityKind.ABSENCE && cmd != ActivityCommand.Delete && cmd != ActivityCommand.Restore)
			illegal()
		val running = state.status == ActivityStatus.RUNNING
		return when (cmd) {
			is ActivityCommand.Start -> {
				if (state.status != ActivityStatus.TODO || cmd.kind !in STARTING)
					illegal()
				ActivityTransition(runningIn(state, cmd.kind), deletePlanned = true, open = OpenSegment(cmd.kind, cmd.at))
			}
			is ActivityCommand.Switch -> {
				if (!running || !canSwitch(state.mode, cmd.kind))
					illegal()
				ActivityTransition(runningIn(state, cmd.kind), closeAt = cmd.at, open = OpenSegment(cmd.kind, cmd.at))
			}
			is ActivityCommand.Resume -> {
				if (!running || state.mode != null || cmd.kind !in STARTING)
					illegal()
				ActivityTransition(runningIn(state, cmd.kind), open = OpenSegment(cmd.kind, cmd.at))
			}
			is ActivityCommand.Suspend -> {
				if (!running || state.mode == null)
					illegal()
				ActivityTransition(state.copy(mode = null), closeAt = cmd.at)
			}
			is ActivityCommand.Stop -> {
				if (!running)
					illegal()
				ActivityTransition(
					state.copy(status = ActivityStatus.CLOSED, mode = null, closedAt = now),
					closeAt = cmd.at.takeIf { state.mode != null },
				)
			}
			ActivityCommand.Tick -> {
				if (state.status != ActivityStatus.TODO)
					illegal()
				ActivityTransition(state.copy(status = ActivityStatus.CLOSED, closedAt = now), deletePlanned = true)
			}
			is ActivityCommand.Reopen -> {
				if (state.status != ActivityStatus.CLOSED || cmd.kind !in STARTING)
					illegal()
				ActivityTransition(runningIn(state, cmd.kind), open = OpenSegment(cmd.kind, cmd.at))
			}
			ActivityCommand.Delete -> ActivityTransition(state.copy(deletedAt = now))
			ActivityCommand.Restore -> {
				if (!state.deleted)
					illegal()
				ActivityTransition(state.copy(deletedAt = null))
			}
		}
	}

	/*
	 * Switch from the open [from] to [to] (3): to PAUSE only from WORK or
	 * TRAVEL; from PAUSE to WORK or TRAVEL; WORK and TRAVEL to each other.
	 */
	fun canSwitch(from: SegmentKind?, to: SegmentKind): Boolean = when (from) {
		SegmentKind.WORK, SegmentKind.TRAVEL -> to != from && to != SegmentKind.ABSENCE
		SegmentKind.PAUSE -> to in STARTING
		SegmentKind.ABSENCE, null -> false
	}

	/* The commands allowed in [state], for the buttons; kinds as the user picks them. */
	fun allowed(state: ActivityState): List<ActivityCommand> {
		val probe = Instant.EPOCH
		val all = buildList {
			for (k in STARTING) add(ActivityCommand.Start(k, probe))
			for (k in listOf(SegmentKind.WORK, SegmentKind.TRAVEL, SegmentKind.PAUSE)) add(ActivityCommand.Switch(k, probe))
			for (k in STARTING) add(ActivityCommand.Resume(k, probe))
			add(ActivityCommand.Suspend(probe))
			add(ActivityCommand.Stop(probe))
			add(ActivityCommand.Tick)
			for (k in STARTING) add(ActivityCommand.Reopen(k, probe))
			add(ActivityCommand.Delete)
			add(ActivityCommand.Restore)
		}
		return all.filter { runCatching { apply(state, it, probe) }.isSuccess }
	}

	/*
	 * "Riprendi" from a pause (3): the kind open before it, i.e. of the last
	 * real WORK or TRAVEL segment that starts before the pause; WORK when
	 * there is none.
	 */
	fun resumeKind(segments: List<ActivitySegment>, pauseStart: Instant): SegmentKind =
		segments.filter { !it.planned && it.kind in STARTING && it.start.isBefore(pauseStart) }
			.maxByOrNull { it.start }?.kind ?: SegmentKind.WORK

	/* A new RUNNING activity started now in [kind] at [at] ("Lavoro · Adesso / Già iniziata", "Viaggio"). */
	fun created(kind: SegmentKind, at: Instant): ActivityTransition {
		require(kind in STARTING) { "an activity cannot start in $kind" }
		return ActivityTransition(ActivityState(ActivityKind.ACTIVITY, ActivityStatus.RUNNING, kind), open = OpenSegment(kind, at))
	}

	private fun runningIn(state: ActivityState, kind: SegmentKind) =
		state.copy(status = ActivityStatus.RUNNING, mode = kind, closedAt = null)
}

/*
 * TRAVEL asked while WORK is open (3, owner's answer): the app asks "Hai
 * completato l'attività lavorativa?" ([needsQuestion]). Yes: stop this one
 * and start a new anonymous activity in TRAVEL at the same instant. No: a
 * switch to TRAVEL in the same activity.
 */
sealed interface TravelFromWork {
	data class StopAndTravel(val stop: ActivityTransition, val travel: ActivityTransition) : TravelFromWork

	data class Switch(val switch: ActivityTransition) : TravelFromWork

	companion object {
		fun needsQuestion(state: ActivityState): Boolean =
			!state.deleted && state.status == ActivityStatus.RUNNING && state.mode == SegmentKind.WORK

		fun decide(state: ActivityState, workDone: Boolean, at: Instant, now: Instant): TravelFromWork {
			require(needsQuestion(state)) { "travel from work needs WORK open: $state" }
			return if (workDone)
				StopAndTravel(
					ActivityStateMachine.apply(state, ActivityCommand.Stop(at), now),
					ActivityStateMachine.created(SegmentKind.TRAVEL, at),
				)
			else
				Switch(ActivityStateMachine.apply(state, ActivityCommand.Switch(SegmentKind.TRAVEL, at), now))
		}
	}
}
