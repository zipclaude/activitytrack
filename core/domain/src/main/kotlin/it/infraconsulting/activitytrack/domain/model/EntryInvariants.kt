/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

class InvariantViolationException(entryId: Long, violations: List<String>) :
	IllegalStateException("entry $entryId: " + violations.joinToString("; "))

/*
 * Consistency rules between an entry's state and its segments (DESIGN §4),
 * checked by the repository before committing every entry transaction:
 * 1. at most one open segment;
 * 2. status ACTIVE <=> mode set <=> an open segment of kind mode exists;
 * 3. segments never overlap (an open segment extends to infinity, so it must
 *    be the last one), unless [overlaps] allows it: an entry merged by a sync
 *    to review (issue #119), the user fixes it.
 */
object EntryInvariants {
	fun violations(status: EntryStatus, mode: SegmentKind?, segments: List<Segment>, overlaps: Boolean = false): List<String> {
		val out = ArrayList<String>()
		val open = segments.filter { it.end == null }

		if (open.size > 1)
			out += "${open.size} open segments"
		if ((status == EntryStatus.ACTIVE) != (mode != null))
			out += "status $status with mode $mode"
		if (status == EntryStatus.ACTIVE && mode != null && open.singleOrNull()?.kind != mode)
			out += "ACTIVE($mode) without a matching open segment"
		if (status != EntryStatus.ACTIVE && open.isNotEmpty())
			out += "open segment while $status"
		if (!overlaps)
			firstOverlap(segments)?.let { (a, b) -> out += "overlapping segments $a and $b" }
		return out
	}

	fun require(entryId: Long, status: EntryStatus, mode: SegmentKind?, segments: List<Segment>, overlaps: Boolean = false) {
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
