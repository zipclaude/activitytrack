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
	 * the limit of 4.1 (60 minutes, times being editable since rounding is).
	 */
	fun stop(at: Instant, quantum: Duration, zone: ZoneId, now: Instant? = null): Instant {
		val r = round(at, quantum, zone, Mode.UP)
		val cap = now?.let { FutureClose.limit(it, editableTimes = true) }
		return if (cap != null && r.isAfter(cap)) maxOf(cap, at) else r
	}

	/*
	 * switch: one instant for both segments, the nearest multiple of the
	 * smaller quantum (half way rounds up), never before [closingStart] (the
	 * start of the segment being closed).
	 */
	fun switch(at: Instant, closing: Duration, opening: Duration, zone: ZoneId, closingStart: Instant): Instant {
		val r = round(at, minOf(closing, opening), zone, Mode.NEAREST)
		return maxOf(r, closingStart)
	}

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
 * Closing in the future (4.1): stop and the end of the last segment accept
 * an instant up to [MAX_AHEAD] after now, only when times are editable;
 * every other "no future time" check stays.
 */
object FutureClose {
	val MAX_AHEAD: Duration = Duration.ofMinutes(60)

	/* The latest end accepted now. */
	fun limit(now: Instant, editableTimes: Boolean): Instant = if (editableTimes) now + MAX_AHEAD else now

	fun accepts(end: Instant, now: Instant, editableTimes: Boolean): Boolean = !end.isAfter(limit(now, editableTimes))

	/*
	 * Another activity starts at [newStart] before the [end] of an earlier
	 * one (4.1): that end moves back to the new start, not before the start
	 * of its segment ([segmentStart]); null when nothing moves.
	 */
	fun pulledBack(end: Instant, segmentStart: Instant, newStart: Instant): Instant? =
		if (end.isAfter(newStart)) maxOf(newStart, segmentStart) else null
}
