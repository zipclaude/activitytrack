/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.report

import it.infraconsulting.activitytrack.domain.model.EntryTimeline
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind.PAUSE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.TRAVEL
import it.infraconsulting.activitytrack.domain.model.SegmentKind.WORK
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DailyReportTest {
	private val zone = ZoneId.of("Europe/Rome")
	private val d1 = LocalDate.of(2026, 10, 14)
	private val d2 = d1.plusDays(1)

	private fun at(day: LocalDate, hh: Int, mm: Int): Instant =
		LocalDateTime.of(day, java.time.LocalTime.of(hh, mm)).atZone(zone).toInstant()

	private fun seg(kind: SegmentKind, day: LocalDate, h1: Int, m1: Int, endDay: LocalDate, h2: Int, m2: Int) =
		Segment(kind, at(day, h1, m1), at(endDay, h2, m2))

	private fun min(m: Long) = Duration.ofMinutes(m)

	private val now = at(d2.plusDays(5), 12, 0)

	@Test
	fun nightEndingBeforeCutoffStaysOnStartDay() {
		val t = EntryTimeline(1, listOf(seg(WORK, d1, 22, 0, d2, 4, 30)))
		val days = DailyReport.build(listOf(t), zone, now)
		assertEquals(listOf(d1), days.map { it.day })
		assertEquals(min(390), days[0].lines[0].work)
	}

	@Test
	fun nightEndingExactlyAtCutoffStaysOnStartDay() {
		val t = EntryTimeline(1, listOf(seg(WORK, d1, 22, 0, d2, 6, 0)))
		val days = DailyReport.build(listOf(t), zone, now)
		assertEquals(listOf(d1), days.map { it.day })
	}

	@Test
	fun nightEndingAfterCutoffIsSplitAtMidnight() {
		val t = EntryTimeline(1, listOf(seg(WORK, d1, 22, 0, d2, 7, 0)))
		val days = DailyReport.build(listOf(t), zone, now)
		assertEquals(listOf(d1, d2), days.map { it.day })
		assertEquals(min(120), days[0].lines[0].work)
		assertEquals(min(420), days[1].lines[0].work)
	}

	@Test
	fun pauseIsComplementIncludingSuspension() {
		/* 09:00-11:00 work, 11:00-11:20 pause, 11:20-12:00 work,
		 * suspended 12:00-14:00, 14:00-14:30 travel, 14:30-17:00 work */
		val t = EntryTimeline(
			1,
			listOf(
				seg(WORK, d1, 9, 0, d1, 11, 0),
				seg(PAUSE, d1, 11, 0, d1, 11, 20),
				seg(WORK, d1, 11, 20, d1, 12, 0),
				seg(TRAVEL, d1, 14, 0, d1, 14, 30),
				seg(WORK, d1, 14, 30, d1, 17, 0),
			),
		)
		val line = DailyReport.build(listOf(t), zone, now).single().lines.single()
		assertEquals(min(310), line.work)
		assertEquals(min(30), line.travel)
		assertEquals(min(140), line.pause)
		assertEquals(min(315), line.roundedWork)
		assertEquals(min(30), line.roundedTravel)
		assertEquals(min(140), line.roundedPause)
	}

	@Test
	fun concurrentEntriesSolarVersusReported() {
		/* A: 09:00-12:00 work; B: 10:00-10:40 work (meeting while A runs) */
		val a = EntryTimeline(1, listOf(seg(WORK, d1, 9, 0, d1, 12, 0)))
		val b = EntryTimeline(2, listOf(seg(WORK, d1, 10, 0, d1, 10, 40)))
		val day = DailyReport.build(listOf(a, b), zone, now).single()
		assertEquals(min(180), day.solar)
		assertEquals(min(180 + 45), day.reported)
	}

	@Test
	fun solarIncludesTravelExcludesPause() {
		val t = EntryTimeline(
			1,
			listOf(
				seg(TRAVEL, d1, 8, 0, d1, 9, 0),
				seg(WORK, d1, 9, 0, d1, 12, 30),
				seg(PAUSE, d1, 12, 30, d1, 13, 30),
				seg(WORK, d1, 13, 30, d1, 17, 0),
				seg(TRAVEL, d1, 17, 0, d1, 18, 10),
			),
		)
		val day = DailyReport.build(listOf(t), zone, now).single()
		assertEquals(min(60 + 210 + 210 + 70), day.solar)
	}

	@Test
	fun openSegmentUsesNow() {
		val t = EntryTimeline(1, listOf(Segment(WORK, at(d1, 9, 0), null)))
		val day = DailyReport.build(listOf(t), zone, at(d1, 9, 50)).single()
		assertEquals(min(50), day.lines.single().work)
	}

	@Test
	fun dstFallBackNightCountsRealElapsedTime() {
		/* 2026-10-25: Europe/Rome goes from CEST to CET at 03:00 -> 02:00 */
		val day = LocalDate.of(2026, 10, 24)
		val t = EntryTimeline(1, listOf(seg(WORK, day, 23, 0, day.plusDays(1), 4, 0)))
		val days = DailyReport.build(listOf(t), zone, now)
		assertEquals(listOf(day), days.map { it.day })
		assertEquals(min(360), days[0].lines[0].work)
	}
}
