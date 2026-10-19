/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

import java.time.Duration
import java.time.Instant

/* Lifecycle of a time entry. */
enum class EntryStatus {
	ACTIVE,		/* one segment is open */
	SUSPENDED,	/* no open segment, may be resumed */
	TO_COMPLETE,	/* stopped, details still missing */
	CLOSED,		/* stopped and complete */
}

/* Kind of a timed segment inside an entry. */
enum class SegmentKind {
	WORK,
	TRAVEL,
	PAUSE,
}

/* Where the work is done: company site, client site, home, elsewhere. */
enum class LocationKind {
	COMPANY,
	CLIENT,
	HOME,
	OTHER,
}

/* How a segment was produced. */
enum class SegmentSource {
	TRACKED,
	MANUAL,
	/* tracked, then edited by hand (backlog 11): the original times are kept */
	CORRECTED,
}

/* Half-open time interval [start, end). */
data class Interval(val start: Instant, val end: Instant) {
	init {
		require(!end.isBefore(start)) { "end ($end) before start ($start)" }
	}

	val duration: Duration
		get() = Duration.between(start, end)

	fun isEmpty(): Boolean = start == end

	/* Intersection with [other], or null when disjoint or touching. */
	fun intersect(other: Interval): Interval? {
		val s = maxOf(start, other.start)
		val e = minOf(end, other.end)
		return if (s.isBefore(e)) Interval(s, e) else null
	}
}

/* A segment as seen by the domain layer; [end] is null while open. */
data class Segment(
	val kind: SegmentKind,
	val start: Instant,
	val end: Instant?,
) {
	init {
		require(end == null || !end.isBefore(start)) { "end ($end) before start ($start)" }
	}

	/* Close an open segment at [now] for computation purposes. */
	fun resolve(now: Instant): Interval = Interval(start, end ?: maxOf(start, now))
}

/* Time-relevant view of an entry, independent of persistence. */
data class EntryTimeline(
	val entryId: Long,
	val segments: List<Segment>,
) {
	val firstStart: Instant?
		get() = segments.minOfOrNull { it.start }

	/* Last end, using [now] for an open segment; null for an empty timeline. */
	fun lastEnd(now: Instant): Instant? = segments.maxOfOrNull { it.resolve(now).end }
}
