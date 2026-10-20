/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

/* A segment as the overlap rule sees it: [start, end), end null = running. */
data class Span(val id: Long, val start: Long, val end: Long?)

/* What happens to another segment of the entry when [start, end) is saved over it. */
sealed interface SpanChange {
	val id: Long

	data class Delete(override val id: Long) : SpanChange
	data class Resize(override val id: Long, val start: Long, val end: Long?) : SpanChange
	/* the segment contains the saved one: it keeps [start, a) and a copy covers [b, end) */
	data class Split(override val id: Long, val firstEnd: Long, val secondStart: Long) : SpanChange
}

/*
 * Issue #40 (owner, 2026-10-20): a segment saved over others of the same
 * entry wins after a confirmation; the others are shortened (the previous
 * one ends where it starts, the next one starts where it ends), split when
 * they contain it, dropped when covered or left under [minLength]. The
 * running segment is never dropped nor split: only its start moves.
 * Segments of other entries are not involved (concurrent entries).
 */
object SegmentOverlap {
	fun conflicts(others: List<Span>, start: Long, end: Long): List<Span> =
		others.filter { o -> o.start < end && (o.end == null || o.end > start) }

	fun resolve(others: List<Span>, start: Long, end: Long, minLength: Long): List<SpanChange> =
		conflicts(others, start, end).map { o ->
			val oEnd = o.end
			when {
				/* running: it goes on from where the saved one ends; the part before stays, if any */
				oEnd == null ->
					if (start - o.start >= minLength) SpanChange.Split(o.id, start, end) else SpanChange.Resize(o.id, end, null)
				o.start < start && oEnd > end -> {
					val keepFirst = start - o.start >= minLength
					val keepSecond = oEnd - end >= minLength
					when {
						keepFirst && keepSecond -> SpanChange.Split(o.id, start, end)
						keepFirst -> SpanChange.Resize(o.id, o.start, start)
						keepSecond -> SpanChange.Resize(o.id, end, oEnd)
						else -> SpanChange.Delete(o.id)
					}
				}
				o.start < start -> if (start - o.start >= minLength) SpanChange.Resize(o.id, o.start, start) else SpanChange.Delete(o.id)
				oEnd > end -> if (oEnd - end >= minLength) SpanChange.Resize(o.id, end, oEnd) else SpanChange.Delete(o.id)
				else -> SpanChange.Delete(o.id)
			}
		}
}
