/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.export

import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * CSV profile (DESIGN §11): UTF-8 with BOM (spreadsheets detect the
 * encoding), ';' separator, RFC 4180 quoting, CRLF line ends, hours as
 * decimals with the locale's decimal separator (comma for Italian).
 * The header uses stable English names for machine import.
 */
class CsvExporter(
	locale: Locale = Locale.getDefault(),
	private val separator: Char = ';',
) : Exporter {
	override val id = "csv"
	override val mimeType = "text/csv"
	override val extension = "csv"

	private val decimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator

	override fun export(rows: List<ExportRow>, out: OutputStream) {
		val w = out.bufferedWriter(Charsets.UTF_8)
		w.write("﻿")
		line(w, HEADER)
		for (r in rows)
			line(
				w,
				listOf(
					r.start.format(DATE), r.start.format(TIME),
					r.end.format(DATE), r.end.format(TIME),
					hours(r.work), hours(r.pause), hours(r.travel),
					r.company, r.client, r.role, r.activity,
					r.km?.let { number(BigDecimal.valueOf(it), 1) }.orEmpty(),
					if (r.expensesCents == 0L) "" else number(BigDecimal.valueOf(r.expensesCents, 2), 2),
					r.notes, r.externalRefs.joinToString(", "), r.locality,
					r.tags.joinToString(" ") { "#$it" },
					if (r.materialsCents == 0L) "" else number(BigDecimal.valueOf(r.materialsCents, 2), 2),
				),
			)
		w.flush()
	}

	/* Decimal hours, two places (15 min = 0,25). */
	fun hours(d: Duration): String =
		number(BigDecimal.valueOf(d.toSeconds()).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP), 2)

	private fun number(v: BigDecimal, scale: Int): String =
		v.setScale(scale, RoundingMode.HALF_UP).toPlainString().replace('.', decimal)

	private fun line(w: java.io.Writer, fields: List<String>) {
		w.write(fields.joinToString(separator.toString()) { quote(it) })
		w.write("\r\n")
	}

	/* RFC 4180: quote fields containing the separator, quotes or line breaks; double inner quotes. */
	fun quote(s: String): String =
		if (s.any { it == separator || it == '"' || it == '\n' || it == '\r' })
			"\"" + s.replace("\"", "\"\"") + "\""
		else
			s

	companion object {
		val HEADER = listOf(
			"start_date", "start_time", "end_date", "end_time",
			"work_hours", "pause_hours", "travel_hours",
			"company", "client", "role", "activity",
			"km", "expenses_eur", "notes", "external_refs", "locality",
			/* M15: added at the end, the earlier columns keep their positions */
			"tags",
			/* issue #41: consumables, kept apart from the expenses */
			"materials_eur",
		)
		private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
		private val TIME = DateTimeFormatter.ofPattern("HH:mm")
	}
}
