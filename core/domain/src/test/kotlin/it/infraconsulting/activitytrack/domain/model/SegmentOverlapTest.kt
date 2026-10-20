/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentOverlapTest {
	private val min = 60_000L
	private fun m(minute: Int) = minute * 60_000L

	@Test
	fun noConflict() {
		val others = listOf(Span(1, m(0), m(30)), Span(2, m(60), m(90)))
		assertEquals(emptyList(), SegmentOverlap.resolve(others, m(30), m(60), min))
	}

	@Test
	fun previousShortenedNextMovedCoveredDropped() {
		val others = listOf(Span(1, m(0), m(40)), Span(2, m(45), m(50)), Span(3, m(55), m(90)))
		assertEquals(
			listOf(SpanChange.Resize(1, m(0), m(30)), SpanChange.Delete(2), SpanChange.Resize(3, m(60), m(90))),
			SegmentOverlap.resolve(others, m(30), m(60), min),
		)
	}

	@Test
	fun containingSplitOrTrimmedWhenARemainderIsTooShort() {
		assertEquals(listOf(SpanChange.Split(1, m(30), m(60))), SegmentOverlap.resolve(listOf(Span(1, m(0), m(90))), m(30), m(60), min))
		assertEquals(
			listOf(SpanChange.Resize(1, m(0), m(30))),
			SegmentOverlap.resolve(listOf(Span(1, m(0), m(60) + 30_000)), m(30), m(60), min),
		)
		assertEquals(
			listOf(SpanChange.Delete(1)),
			SegmentOverlap.resolve(listOf(Span(1, m(30) - 30_000, m(60) + 30_000)), m(30), m(60), min),
		)
	}

	@Test
	fun runningSegmentMovesOrSplits() {
		assertEquals(listOf(SpanChange.Split(1, m(30), m(60))), SegmentOverlap.resolve(listOf(Span(1, m(0), null)), m(30), m(60), min))
		assertEquals(listOf(SpanChange.Resize(1, m(60), null)), SegmentOverlap.resolve(listOf(Span(1, m(20), null)), m(10), m(60), min))
	}

	@Test
	fun touchingIsNoConflict() {
		assertEquals(emptyList(), SegmentOverlap.conflicts(listOf(Span(1, m(0), m(30)), Span(2, m(60), null)), m(30), m(60)))
	}
}
