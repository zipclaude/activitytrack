/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

import it.infraconsulting.activitytrack.domain.activity.ActivityStatus

class InvariantViolationException(entryId: Long, violations: List<String>) :
	IllegalStateException("entry $entryId: " + violations.joinToString("; "))

/*
 * Consistency rules between an activity's state and its segments (DESIGN §4,
 * ACTIVITY-MODEL.md 1.1, 1.2), checked by the repository before committing
 * every activity transaction:
 * 1. at most one open segment, never a planned or an ABSENCE one;
 * 2. mode set <=> an open segment of kind mode exists, and only while RUNNING;
 * 3. planned segments only in a TODO activity;
 * 4. real segments never overlap (an open segment extends to infinity, so it
 *    must be the last one), unless [overlaps] allows it: an activity merged
 *    by a sync to review (issue #119), the user fixes it. Planned segments
 *    are checked among themselves.
 */
object EntryInvariants {
	fun violations(status: ActivityStatus, mode: SegmentKind?, segments: List<Segment>, overlaps: Boolean = false): List<String> {
		val out = ArrayList<String>()
		val real = segments.filter { !it.planned }
		val planned = segments.filter { it.planned }
		val open = segments.filter { it.end == null }

		if (open.size > 1)
			out += "${open.size} open segments"
		if (open.any { it.planned || it.kind == SegmentKind.ABSENCE })
			out += "an open planned or absence segment"
		if (mode != null && status != ActivityStatus.RUNNING)
			out += "status $status with mode $mode"
		if (mode != null && open.singleOrNull()?.kind != mode)
			out += "mode $mode without a matching open segment"
		if (mode == null && open.isNotEmpty())
			out += "open segment without mode ($status)"
		if (planned.isNotEmpty() && status != ActivityStatus.TODO)
			out += "planned segments while $status"
		if (!overlaps)
			firstOverlap(real)?.let { (a, b) -> out += "overlapping segments $a and $b" }
		firstOverlap(planned)?.let { (a, b) -> out += "overlapping planned segments $a and $b" }
		return out
	}

	fun require(entryId: Long, status: ActivityStatus, mode: SegmentKind?, segments: List<Segment>, overlaps: Boolean = false) {
		val v = violations(status, mode, segments, overlaps)
		if (v.isNotEmpty())
			throw InvariantViolationException(entryId, v)
	}

	/* First pair of overlapping segments in start order; touching segments do not overlap. */
	fun firstOverlap(segments: List<Segment>): Pair<Segment, Segment>? {
		val sorted = segments.sortedBy { it.start }
		for (i in 1 until sorted.size) {
			val prev = sorted[i - 1]
			val cur = sorted[i]
			if (prev.end == null || prev.end.isAfter(cur.start))
				return prev to cur
		}
		return null
	}
}
