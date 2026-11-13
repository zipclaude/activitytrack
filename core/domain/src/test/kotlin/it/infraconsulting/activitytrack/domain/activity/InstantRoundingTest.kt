/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstantRoundingTest {
	private val rome = ZoneId.of("Europe/Rome")
	private val q5 = Duration.ofMinutes(5)
	private val q15 = Duration.ofMinutes(15)
	private val q60 = Duration.ofMinutes(60)

	private fun t(s: String) = Instant.parse(s)

	@Test
	fun quantaFromTheReportRules() {
		val r = ReportRounding.DEFAULT
		assertEquals(q15, InstantRounding.quantum(SegmentKind.WORK, r))
		assertEquals(q5, InstantRounding.quantum(SegmentKind.TRAVEL, r))
		assertEquals(q5, InstantRounding.quantum(SegmentKind.PAUSE, r))
		assertFailsWith<IllegalArgumentException> { InstantRounding.quantum(SegmentKind.ABSENCE, r) }
		assertTrue(InstantRounding.effective(roundStoredTimes = true, editableTimes = true))
		assertFalse(InstantRounding.effective(roundStoredTimes = true, editableTimes = false))
	}

	@Test
	fun startRoundsDownNotBeforeThePreviousEnd() {
		assertEquals(t("2026-11-11T09:00:00Z"), InstantRounding.start(t("2026-11-11T09:07:30.250Z"), q15, rome))
		assertEquals(t("2026-11-11T09:15:00Z"), InstantRounding.start(t("2026-11-11T09:15:00Z"), q15, rome))
		assertEquals(t("2026-11-11T09:05:00Z"), InstantRounding.start(t("2026-11-11T09:07:00Z"), q15, rome, notBefore = t("2026-11-11T09:05:00Z")))
	}

	@Test
	fun stopRoundsUpCappedADayAhead() {
		assertEquals(t("2026-11-11T09:15:00Z"), InstantRounding.stop(t("2026-11-11T09:00:00.001Z"), q15, rome))
		assertEquals(t("2026-11-11T09:15:00Z"), InstantRounding.stop(t("2026-11-11T09:15:00Z"), q15, rome))
		assertEquals(t("2026-11-11T10:00:00Z"), InstantRounding.stop(t("2026-11-11T09:01:00Z"), q60, rome, now = t("2026-11-11T09:01:00Z")))
		/* an end near the 24-hour limit: min(max(rounded, at), now + 24 h) */
		val now = t("2026-11-11T08:58:00Z")
		assertEquals(t("2026-11-12T08:58:00Z"), InstantRounding.stop(t("2026-11-12T08:50:00Z"), q15, rome, now = now))
		assertEquals(t("2026-11-12T08:45:00Z"), InstantRounding.stop(t("2026-11-12T08:31:00Z"), q15, rome, now = now))
		/* an asked instant beyond the limit is capped here and refused by the callers (FutureClose.accepts) */
		assertEquals(t("2026-11-12T08:58:00Z"), InstantRounding.stop(t("2026-11-12T08:59:00Z"), q15, rome, now = now))
		assertFalse(FutureClose.accepts(t("2026-11-12T08:59:00Z"), now, editableTimes = true))
	}

	@Test
	fun switchTakesTheNearestMultipleOfTheSmallerQuantum() {
		val start = t("2026-11-11T08:00:00Z")
		assertEquals(t("2026-11-11T09:05:00Z"), InstantRounding.switch(t("2026-11-11T09:07:29Z"), q15, q5, rome, start))
		assertEquals(t("2026-11-11T09:10:00Z"), InstantRounding.switch(t("2026-11-11T09:07:30Z"), q5, q15, rome, start))
		assertEquals(t("2026-11-11T09:15:00Z"), InstantRounding.switch(t("2026-11-11T09:07:30Z"), q15, q60, rome, start))
		/* never before the start of the segment being closed: the exact instant when the rounded one is not after it (R3) */
		assertEquals(t("2026-11-11T09:07:00Z"), InstantRounding.switch(t("2026-11-11T09:07:00Z"), q15, q5, rome, t("2026-11-11T09:06:00Z")))
		assertEquals(t("2026-11-11T09:07:00Z"), InstantRounding.switch(t("2026-11-11T09:07:00Z"), q15, q5, rome, t("2026-11-11T09:05:00Z")))
		/* R1: ahead of the clock by at most half the smaller quantum */
		assertEquals(t("2026-11-11T09:10:00Z"), InstantRounding.switch(t("2026-11-11T09:07:30Z"), q15, q5, rome, start))
	}

	/* the owner's example (issue #179): WORK->PAUSE at 10:07:30 stored 10:10, then PAUSE->TRAVEL at 10:08 */
	@Test
	fun switchOutOfASegmentAheadDropsIt() {
		val pauseStart = InstantRounding.switch(t("2026-11-11T09:07:30Z"), q15, q5, rome, t("2026-11-11T08:00:00Z"))
		assertEquals(t("2026-11-11T09:10:00Z"), pauseStart)
		val now = t("2026-11-11T09:08:00Z")
		/* 10:08 rounds to 10:10 = the pause's start: the exact 10:08, raised to that start */
		val at = InstantRounding.switch(now, q5, q5, rome, pauseStart)
		assertEquals(pauseStart, at)
		assertTrue(InstantRounding.dropsClosing(pauseStart, at, now))
		/* a segment that began, or ends after its start, is closed as usual */
		assertFalse(InstantRounding.dropsClosing(t("2026-11-11T09:05:00Z"), t("2026-11-11T09:05:00Z"), now))
		assertFalse(InstantRounding.dropsClosing(pauseStart, t("2026-11-11T09:15:00Z"), now))
	}

	@Test
	fun wallClockMultiplesWithAHalfHourOffset() {
		val kolkata = ZoneId.of("Asia/Kolkata")	/* +05:30: 10:20 local is 04:50Z */
		assertEquals(t("2026-11-11T04:30:00Z"), InstantRounding.start(t("2026-11-11T04:50:00Z"), q60, kolkata))
		assertEquals(t("2026-11-11T05:30:00Z"), InstantRounding.stop(t("2026-11-11T04:50:00Z"), q60, kolkata))
		assertEquals(t("2026-11-11T05:00:00Z"), InstantRounding.start(t("2026-11-11T05:00:00Z"), Duration.ofMinutes(30), kolkata))
	}

	@Test
	fun dstEndInRome() {
		/* 2026-10-25: 03:00 CEST (01:00Z) back to 02:00 CET, 02:00-03:00 twice */
		assertEquals(t("2026-10-25T01:00:00Z"), InstantRounding.stop(t("2026-10-25T00:50:00Z"), q15, rome))
		assertEquals(t("2026-10-25T00:00:00Z"), InstantRounding.start(t("2026-10-25T00:50:00Z"), q60, rome))
		assertEquals(t("2026-10-25T01:00:00Z"), InstantRounding.start(t("2026-10-25T01:10:00Z"), q60, rome))
		assertEquals(t("2026-10-25T01:00:00Z"), InstantRounding.switch(t("2026-10-25T00:58:00Z"), q15, q5, rome, t("2026-10-25T00:00:00Z")))
	}

	@Test
	fun dstStartInRome() {
		/* 2026-03-29: 02:00 CET (01:00Z) forward to 03:00 CEST, 02:00-03:00 skipped */
		assertEquals(t("2026-03-29T01:00:00Z"), InstantRounding.stop(t("2026-03-29T00:50:00Z"), q15, rome))
		assertEquals(t("2026-03-29T01:00:00Z"), InstantRounding.start(t("2026-03-29T01:05:00Z"), q15, rome))
		assertEquals(t("2026-03-29T01:00:00Z"), InstantRounding.stop(t("2026-03-29T00:10:00Z"), q60, rome))
		assertEquals(t("2026-03-29T01:00:00Z"), InstantRounding.switch(t("2026-03-29T00:58:00Z"), q5, q5, rome, t("2026-03-29T00:00:00Z")))
	}

	@Test
	fun futureCloseLimit() {
		val now = t("2026-11-11T09:00:00Z")
		assertEquals(Duration.ofHours(24), FutureClose.MAX_AHEAD)
		assertEquals(t("2026-11-12T09:00:00Z"), FutureClose.limit(now, editableTimes = true))
		assertEquals(now, FutureClose.limit(now, editableTimes = false))
		assertTrue(FutureClose.accepts(t("2026-11-12T09:00:00Z"), now, editableTimes = true))
		assertFalse(FutureClose.accepts(t("2026-11-12T09:00:00.001Z"), now, editableTimes = true))
		assertFalse(FutureClose.accepts(t("2026-11-11T09:00:01Z"), now, editableTimes = false))
		assertTrue(FutureClose.accepts(now, now, editableTimes = false))
	}

	@Test
	fun anEndAheadMovesBackToANewStart() {
		val now = t("2026-11-11T09:00:00Z")
		val segStart = t("2026-11-11T08:00:00Z")
		val end = t("2026-11-11T09:30:00Z")
		assertEquals(FutureClose.Effect.EndAt(now), FutureClose.pulledBack(segStart, end, now, now))
		assertEquals(FutureClose.Effect.EndAt(t("2026-11-11T08:40:00Z")), FutureClose.pulledBack(segStart, end, t("2026-11-11T08:40:00Z"), now))
		/* nothing to move: the new start at or after the end, or an end in the past */
		assertNull(FutureClose.pulledBack(segStart, end, end, now))
		assertNull(FutureClose.pulledBack(segStart, t("2026-11-11T08:50:00Z"), t("2026-11-11T08:40:00Z"), now))
		/* a segment begun in the past after the new start keeps its end */
		assertNull(FutureClose.pulledBack(segStart, end, t("2026-11-11T07:00:00Z"), now))
	}

	/* R4 (issue #179): a segment wholly ahead (the declared return trip) goes instead of ending where it starts */
	@Test
	fun aSegmentWhollyAheadIsRemoved() {
		val now = t("2026-11-11T09:00:00Z")
		val ret = t("2026-11-11T09:20:00Z")
		val retEnd = t("2026-11-11T10:05:00Z")
		assertEquals(FutureClose.Effect.Removed, FutureClose.pulledBack(ret, retEnd, now, now))
		assertEquals(FutureClose.Effect.Removed, FutureClose.pulledBack(ret, retEnd, ret, now))
		/* at the start of a segment begun in the past: cut there it would be nothing */
		assertEquals(FutureClose.Effect.Removed, FutureClose.pulledBack(segStartPast, retEnd, segStartPast, now))
	}

	private val segStartPast = t("2026-11-11T08:30:00Z")
}
