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
	fun stopRoundsUpCappedAnHourAhead() {
		assertEquals(t("2026-11-11T09:15:00Z"), InstantRounding.stop(t("2026-11-11T09:00:00.001Z"), q15, rome))
		assertEquals(t("2026-11-11T09:15:00Z"), InstantRounding.stop(t("2026-11-11T09:15:00Z"), q15, rome))
		assertEquals(t("2026-11-11T10:00:00Z"), InstantRounding.stop(t("2026-11-11T09:01:00Z"), q60, rome, now = t("2026-11-11T09:01:00Z")))
		/* an end already near the limit: capped, never before the asked instant */
		assertEquals(t("2026-11-11T09:58:00Z"), InstantRounding.stop(t("2026-11-11T09:50:00Z"), q15, rome, now = t("2026-11-11T08:58:00Z")))
		assertEquals(t("2026-11-11T09:59:00Z"), InstantRounding.stop(t("2026-11-11T09:59:00Z"), q15, rome, now = t("2026-11-11T08:58:00Z")))
	}

	@Test
	fun switchTakesTheNearestMultipleOfTheSmallerQuantum() {
		val start = t("2026-11-11T08:00:00Z")
		assertEquals(t("2026-11-11T09:05:00Z"), InstantRounding.switch(t("2026-11-11T09:07:29Z"), q15, q5, rome, start))
		assertEquals(t("2026-11-11T09:10:00Z"), InstantRounding.switch(t("2026-11-11T09:07:30Z"), q5, q15, rome, start))
		assertEquals(t("2026-11-11T09:15:00Z"), InstantRounding.switch(t("2026-11-11T09:07:30Z"), q15, q60, rome, start))
		/* never before the start of the segment being closed */
		assertEquals(t("2026-11-11T09:06:00Z"), InstantRounding.switch(t("2026-11-11T09:07:00Z"), q15, q5, rome, t("2026-11-11T09:06:00Z")))
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
		assertEquals(t("2026-11-11T10:00:00Z"), FutureClose.limit(now, editableTimes = true))
		assertEquals(now, FutureClose.limit(now, editableTimes = false))
		assertTrue(FutureClose.accepts(t("2026-11-11T10:00:00Z"), now, editableTimes = true))
		assertFalse(FutureClose.accepts(t("2026-11-11T10:00:00.001Z"), now, editableTimes = true))
		assertFalse(FutureClose.accepts(t("2026-11-11T09:00:01Z"), now, editableTimes = false))
		assertTrue(FutureClose.accepts(now, now, editableTimes = false))
	}

	@Test
	fun anEndAheadMovesBackToANewStart() {
		val segStart = t("2026-11-11T08:00:00Z")
		assertEquals(t("2026-11-11T09:10:00Z"), FutureClose.pulledBack(t("2026-11-11T09:30:00Z"), segStart, t("2026-11-11T09:10:00Z")))
		assertEquals(null, FutureClose.pulledBack(t("2026-11-11T09:30:00Z"), segStart, t("2026-11-11T09:30:00Z")))
		assertEquals(segStart, FutureClose.pulledBack(t("2026-11-11T09:30:00Z"), segStart, t("2026-11-11T07:00:00Z")))
	}
}
