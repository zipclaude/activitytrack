/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

import it.infraconsulting.activitytrack.domain.model.EntryStatus.ACTIVE
import it.infraconsulting.activitytrack.domain.model.EntryStatus.SUSPENDED
import it.infraconsulting.activitytrack.domain.model.SegmentKind.PAUSE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.TRAVEL
import it.infraconsulting.activitytrack.domain.model.SegmentKind.WORK
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryInvariantsTest {
	private fun t(min: Long): Instant = Instant.parse("2026-10-14T08:00:00Z").plusSeconds(min * 60)
	private fun seg(kind: SegmentKind, from: Long, to: Long?) = Segment(kind, t(from), to?.let { t(it) })

	@Test
	fun consistentActiveEntry() {
		val segs = listOf(seg(TRAVEL, 0, 30), seg(WORK, 30, 90), seg(PAUSE, 90, null))
		assertEquals(emptyList(), EntryInvariants.violations(ACTIVE, PAUSE, segs))
	}

	@Test
	fun consistentSuspendedEntry() {
		val segs = listOf(seg(WORK, 0, 30), seg(WORK, 60, 90))
		assertEquals(emptyList(), EntryInvariants.violations(SUSPENDED, null, segs))
	}

	@Test
	fun activeModeMustMatchOpenSegment() {
		val segs = listOf(seg(WORK, 0, null))
		assertTrue(EntryInvariants.violations(ACTIVE, TRAVEL, segs).isNotEmpty())
		assertTrue(EntryInvariants.violations(ACTIVE, WORK, listOf(seg(WORK, 0, 10))).isNotEmpty())
	}

	@Test
	fun noOpenSegmentUnlessActive() {
		assertTrue(EntryInvariants.violations(SUSPENDED, null, listOf(seg(WORK, 0, null))).isNotEmpty())
	}

	@Test
	fun statusAndModeAgree() {
		assertTrue(EntryInvariants.violations(SUSPENDED, WORK, emptyList()).isNotEmpty())
	}

	@Test
	fun overlapDetection() {
		assertNull(EntryInvariants.firstOverlap(listOf(seg(WORK, 0, 30), seg(TRAVEL, 30, 40))))
		assertNotNull(EntryInvariants.firstOverlap(listOf(seg(WORK, 0, 31), seg(TRAVEL, 30, 40))))
		/* an open segment followed by anything overlaps */
		assertNotNull(EntryInvariants.firstOverlap(listOf(seg(WORK, 0, null), seg(TRAVEL, 60, 70))))
		/* a manual segment inserted before the open one is fine */
		assertNull(EntryInvariants.firstOverlap(listOf(seg(WORK, 60, null), seg(TRAVEL, 0, 60))))
	}

	@Test
	fun twoOpenSegments() {
		val segs = listOf(seg(WORK, 0, null), seg(PAUSE, 10, null))
		assertFailsWith<InvariantViolationException> { EntryInvariants.require(1, ACTIVE, PAUSE, segs) }
	}
}
