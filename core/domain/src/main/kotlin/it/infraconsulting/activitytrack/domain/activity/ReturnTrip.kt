/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.time.Duration
import java.time.Instant

/*
 * A real segment as the return trip sees it ([end] null while open), with
 * its km and the points of its first and last location samples ([P] is the
 * caller's point type; null without samples).
 */
data class TripSegment<P : Any>(
	val kind: SegmentKind,
	val start: Instant,
	val end: Instant?,
	val kmEstimated: Double? = null,
	val kmDeclared: Double? = null,
	val firstSample: P? = null,
	val lastSample: P? = null,
)

/* A location sample of the return segment. */
data class ReturnSample<P : Any>(val at: Instant, val point: P)

/* The return TRAVEL segment to add (source MANUAL): [samples] none or two, start and arrival swapped. */
data class ReturnSegment<P : Any>(
	val start: Instant,
	val end: Instant,
	val kmEstimated: Double?,
	val kmDeclared: Double?,
	val samples: List<ReturnSample<P>>,
)

/* The three choices of the stop question (3.2). */
enum class ReturnChoice {
	TRAVEL_BACK,		/* "Avvia viaggio di ritorno": travel now, the activity keeps running */
	STOP,			/* "Termina l'attività" */
	STOP_WITH_RETURN,	/* "Termina l'attività ed aggiungi il viaggio di ritorno" */
}

/*
 * Return trip on stop (3.2, owner 2026-11-11): stopping an activity with a
 * TRAVEL segment followed later by a WORK one, and no TRAVEL after its last
 * WORK, offers to travel back, to stop, or to stop adding the return trip:
 * as long as the outbound one, with its km, the outbound's last and first
 * sample as its start and arrival. With saving rounded times on, the caller
 * rounds [at] as a stop (InstantRounding.stop) and the return keeps the
 * outbound's exact duration.
 */
object ReturnTrip {
	/* The question applies; planned segments are not passed (they belong to TODO activities). */
	fun <P : Any> applies(segments: List<TripSegment<P>>): Boolean {
		val sorted = segments.sortedBy { it.start }
		val lastWork = sorted.indexOfLast { it.kind == SegmentKind.WORK }
		if (lastWork < 0)
			return false
		val travelBeforeWork = sorted.subList(0, lastWork).any { it.kind == SegmentKind.TRAVEL }
		val travelAfterWork = sorted.subList(lastWork + 1, sorted.size).any { it.kind == SegmentKind.TRAVEL }
		return travelBeforeWork && !travelAfterWork
	}

	/*
	 * The choices offered; empty when the question does not apply (a plain
	 * stop). The third one adds an end that may lie in the future, so only
	 * with editable times (decided by the assistant).
	 */
	fun <P : Any> choices(segments: List<TripSegment<P>>, editableTimes: Boolean): List<ReturnChoice> = when {
		!applies(segments) -> emptyList()
		editableTimes -> ReturnChoice.entries.toList()
		else -> listOf(ReturnChoice.TRAVEL_BACK, ReturnChoice.STOP)
	}

	/*
	 * The outbound trip: the last TRAVEL segment before the first WORK
	 * segment that follows a TRAVEL one (travel, travel, work: the second).
	 */
	fun <P : Any> outbound(segments: List<TripSegment<P>>): TripSegment<P>? {
		var travel: TripSegment<P>? = null
		for (s in segments.sortedBy { it.start }) {
			when (s.kind) {
				SegmentKind.TRAVEL -> travel = s
				SegmentKind.WORK -> if (travel != null) return travel
				else -> {}
			}
		}
		return null
	}

	/* The return segment starting at [at]; null when there is no closed outbound trip. */
	fun <P : Any> returnSegment(segments: List<TripSegment<P>>, at: Instant): ReturnSegment<P>? {
		val out = outbound(segments) ?: return null
		val outEnd = out.end ?: return null
		val end = at + Duration.between(out.start, outEnd)
		val first = out.firstSample
		val last = out.lastSample
		val samples = if (first != null && last != null) listOf(ReturnSample(at, last), ReturnSample(end, first)) else emptyList()
		return ReturnSegment(at, end, out.kmEstimated, out.kmDeclared, samples)
	}

	/* "Avvia viaggio di ritorno": a switch to TRAVEL, a resume when nothing is open. */
	fun travelBack(state: ActivityState, at: Instant, now: Instant): ActivityTransition =
		ActivityStateMachine.apply(
			state,
			if (state.mode == null) ActivityCommand.Resume(SegmentKind.TRAVEL, at) else ActivityCommand.Switch(SegmentKind.TRAVEL, at),
			now,
		)

	/*
	 * "Termina l'attività ed aggiungi il viaggio di ritorno", in one
	 * transaction: the stop at [at] (closing the open segment, closed_utc_ms
	 * = [now]) and the return segment to insert.
	 */
	fun <P : Any> stopWithReturn(state: ActivityState, segments: List<TripSegment<P>>, at: Instant, now: Instant): Pair<ActivityTransition, ReturnSegment<P>> {
		require(applies(segments)) { "no return trip to add" }
		val ret = requireNotNull(returnSegment(segments, at)) { "no closed outbound trip" }
		return ActivityStateMachine.apply(state, ActivityCommand.Stop(at), now) to ret
	}
}
