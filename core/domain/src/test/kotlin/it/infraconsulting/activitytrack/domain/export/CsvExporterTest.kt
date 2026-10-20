/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.export

import it.infraconsulting.activitytrack.domain.model.EntryTimeline
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.report.DailyReport
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvExporterTest {
	private val zone = ZoneId.of("Europe/Rome")
	private val d = LocalDate.of(2026, 10, 14)
	private fun at(day: LocalDate, hh: Int, mm: Int): Instant = day.atTime(LocalTime.of(hh, mm)).atZone(zone).toInstant()
	private val now = at(d.plusDays(10), 12, 0)

	private val meta = EntryMeta(
		company = "Acme",
		client = "Bank; Ltd",
		role = "Consultant",
		activity = "Patch \"night\"",
		notes = "line1\nline2",
		locality = "Milano",
		externalRefs = listOf("INC1", "OPS-2"),
		km = 42.25,
		expensesCents = 1250,
		tags = listOf("remoto", "urgente"),
	)

	private fun csv(rows: List<ExportRow>, locale: Locale): String {
		val out = ByteArrayOutputStream()
		CsvExporter(locale).export(rows, out)
		return out.toString(Charsets.UTF_8)
	}

	@Test
	fun italianProfile() {
		/* 08:00-08:40 travel, 08:40-12:00 work, pause 12:00-12:20, 12:20-13:00 work */
		val t = EntryTimeline(
			1,
			listOf(
				Segment(SegmentKind.TRAVEL, at(d, 8, 0), at(d, 8, 40)),
				Segment(SegmentKind.WORK, at(d, 8, 40), at(d, 12, 0)),
				Segment(SegmentKind.PAUSE, at(d, 12, 0), at(d, 12, 20)),
				Segment(SegmentKind.WORK, at(d, 12, 20), at(d, 13, 0)),
			),
		)
		val rows = ExportRows.build(DailyReport.build(listOf(t), zone, now), mapOf(1L to meta), zone)
		val text = csv(rows, Locale.ITALY)
		val lines = text.removePrefix("﻿").split("\r\n")
		assertEquals(CsvExporter.HEADER.joinToString(";"), lines[0])
		/* work 4:00 -> 4,00; pause 20 -> 0,33 (FLOOR 5 = 20 min); travel 40 -> 0,67 */
		assertEquals(
			"2026-10-14;08:00;2026-10-14;13:00;4,00;0,33;0,67;Acme;\"Bank; Ltd\";Consultant;" +
				"\"Patch \"\"night\"\"\";42,3;12,50;\"line1\nline2\";INC1, OPS-2;Milano;#remoto #urgente;",
			lines[1],
		)
		/* the LF inside the quoted notes does not end the record; CRLF does */
		assertEquals(listOf(""), lines.drop(2))
	}

	@Test
	fun englishDecimalPoint() {
		assertEquals("0.25", CsvExporter(Locale.US).hours(Duration.ofMinutes(15)))
		assertEquals("1,50", CsvExporter(Locale.ITALY).hours(Duration.ofMinutes(90)))
	}

	@Test
	fun kmAndExpensesOnFirstDayOnly() {
		/* 20:00 -> 09:00 next day: past the 06:00 carry-over, split at midnight */
		val t = EntryTimeline(1, listOf(Segment(SegmentKind.WORK, at(d, 20, 0), at(d.plusDays(1), 9, 0))))
		val rows = ExportRows.build(DailyReport.build(listOf(t), zone, now), mapOf(1L to meta), zone)
		assertEquals(2, rows.size)
		assertEquals(42.25, rows[0].km)
		assertEquals(1250, rows[0].expensesCents)
		assertEquals(null, rows[1].km)
		assertEquals(0, rows[1].expensesCents)
		assertEquals(LocalTime.MIDNIGHT, rows[1].start.toLocalTime())
	}

	@Test
	fun quoting() {
		val c = CsvExporter(Locale.ITALY)
		assertEquals("plain", c.quote("plain"))
		assertEquals("\"a;b\"", c.quote("a;b"))
		assertEquals("\"say \"\"hi\"\"\"", c.quote("say \"hi\""))
	}
}
