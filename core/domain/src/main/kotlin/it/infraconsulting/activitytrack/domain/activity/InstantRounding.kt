/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/*
 * Saving rounded times (4.2): with `roundStoredTimes` on and times editable,
 * the action buttons store rounded instants; manual entry never rounds.
 * Multiples of the quantum are counted on the local wall clock (10:00,
 * 10:15 ... in the zone, also with a half-hour offset), with the offset of
 * the instant being rounded: across a DST change the result is the same
 * real time away as without the change, and the quanta offered (1, 5, 15,
 * 30, 60 minutes) divide the hour, so it shows as a round time as well.
 */
object InstantRounding {
	/* The setting takes effect only when times are editable. */
	fun effective(roundStoredTimes: Boolean, editableTimes: Boolean): Boolean = roundStoredTimes && editableTimes

	/* The quantum of a segment kind: its report rule's, whatever the rule's mode. */
	fun quantum(kind: SegmentKind, rounding: ReportRounding): Duration = when (kind) {
		SegmentKind.WORK -> rounding.work.quantum
		SegmentKind.TRAVEL -> rounding.travel.quantum
		SegmentKind.PAUSE -> rounding.pause.quantum
		SegmentKind.ABSENCE -> throw IllegalArgumentException("absence segments are not started by buttons")
	}

	/* start, resume, reopen: down, never before [notBefore] (the end of the activity's previous segment). */
	fun start(at: Instant, quantum: Duration, zone: ZoneId, notBefore: Instant? = null): Instant {
		val r = round(at, quantum, zone, Mode.DOWN)
		return if (notBefore != null && r.isBefore(notBefore)) notBefore else r
	}

	/*
	 * stop: up. The result may lie in the future; with [now] it is capped at
	 * the limit of 4.1 ([FutureClose.MAX_AHEAD], times being editable since
	 * rounding is): min(max(rounded, at), now + limit). An [at] beyond the
	 * limit is the caller's to refuse (FutureClose.accepts).
	 */
	fun stop(at: Instant, quantum: Duration, zone: ZoneId, now: Instant? = null): Instant {
		val r = maxOf(round(at, quantum, zone, Mode.UP), at)
		val cap = now?.let { FutureClose.limit(it, editableTimes = true) }
		return if (cap != null) minOf(r, cap) else r
	}

	/*
	 * switch: one instant for both segments, the nearest multiple of the
	 * smaller quantum (half way rounds up), which may lie ahead of [at] by
	 * half of it (issue #179, R1). When that is not after [closingStart] (the
	 * start of the segment being closed) the exact [at] instead: no segment
	 * of zero length (R3). Never before [closingStart], even when that lies
	 * ahead: the caller then drops the segment it would close where it
	 * starts ([dropsClosing]).
	 */
	fun switch(at: Instant, closing: Duration, opening: Duration, zone: ZoneId, closingStart: Instant): Instant {
		val r = round(at, minOf(closing, opening), zone, Mode.NEAREST)
		return maxOf(if (r.isAfter(closingStart)) r else at, closingStart)
	}

	/*
	 * A segment closed at [closeAt] that starts at [start], ahead of [now],
	 * and would end where it starts has not begun: it is deleted instead of
	 * kept with zero length (R3: WORK->PAUSE at 10:05 stored 10:10, then
	 * PAUSE->TRAVEL at 10:06: no pause, TRAVEL from 10:10).
	 */
	fun dropsClosing(start: Instant, closeAt: Instant, now: Instant): Boolean = !closeAt.isAfter(start) && start.isAfter(now)

	private enum class Mode { DOWN, UP, NEAREST }

	private fun round(at: Instant, quantum: Duration, zone: ZoneId, mode: Mode): Instant {
		require(!quantum.isNegative && !quantum.isZero) { "quantum must be positive" }
		val q = quantum.toNanos()
		val offset = zone.rules.getOffset(at).totalSeconds * NANOS
		val local = at.epochSecond * NANOS + at.nano + offset
		val r = Math.floorMod(local, q)
		val down = local - r
		val rounded = when (mode) {
			Mode.DOWN -> down
			Mode.UP -> if (r == 0L) local else down + q
			Mode.NEAREST -> if (2 * r >= q) down + q else down
		}
		val utc = rounded - offset
		return Instant.ofEpochSecond(Math.floorDiv(utc, NANOS), Math.floorMod(utc, NANOS))
	}

	private const val NANOS = 1_000_000_000L
}

/*
 * Closing in the future (4.1): stop, the end of the last segment and the
 * return trip of 3.2 accept an instant up to [MAX_AHEAD] after now, only
 * when times are editable; every other "no future time" check stays.
 */
object FutureClose {
	/* owner, issue #179: 24 hours (was 60 minutes) */
	val MAX_AHEAD: Duration = Duration.ofHours(24)

	/* The latest end accepted now. */
	fun limit(now: Instant, editableTimes: Boolean): Instant = if (editableTimes) now + MAX_AHEAD else now

	fun accepts(end: Instant, now: Instant, editableTimes: Boolean): Boolean = !end.isAfter(limit(now, editableTimes))

	/*
	 * An activity starts, resumes or reopens at [newStart] before the [end],
	 * ahead of [now], of a segment (of another activity or of its own last
	 * one, 4.1): what happens to that segment; null when nothing does.
	 */
	fun pulledBack(segmentStart: Instant, end: Instant, newStart: Instant, now: Instant): Effect? = when {
		!end.isAfter(now) || !end.isAfter(newStart) -> null
		/* at its start, or before it when it has not begun: cut there it would be nothing (R4) */
		newStart == segmentStart || (newStart.isBefore(segmentStart) && segmentStart.isAfter(now)) -> Effect.Removed
		/* a real overlap: the end moves back to the new start */
		!segmentStart.isAfter(newStart) -> Effect.EndAt(newStart)
		/* a segment that began (in the past) after the new start keeps its end */
		else -> null
	}

	sealed interface Effect {
		data class EndAt(val end: Instant) : Effect

		/* the segment lay wholly ahead (a declared return trip, 3.2): it goes, with a notice of its own */
		data object Removed : Effect
	}
}
