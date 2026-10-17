/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import it.infraconsulting.activitytrack.domain.time.atMinute
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/* ISO date (yyyy-mm-dd) or null. */
fun parseDate(s: String): LocalDate? =
	try {
		LocalDate.parse(s.trim())
	} catch (e: DateTimeParseException) {
		null
	}

/*
 * Local [startMin]..[endMin] on [date] as UTC epoch ms; an end not after the
 * start falls on the next day (night segments, e.g. 22:00-01:30).
 */
fun localRange(date: LocalDate, startMin: Int, endMin: Int, zone: ZoneId): Pair<Long, Long> {
	val endDay = if (endMin <= startMin) date.plusDays(1) else date
	return date.atMinute(startMin, zone).toEpochMilli() to endDay.atMinute(endMin, zone).toEpochMilli()
}

/* "HH:MM" of [ms] in [zone]. */
fun clockOf(ms: Long, zone: ZoneId): String {
	val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
	return formatMin(t.hour * 60 + t.minute)
}

/* Duration in ms as H:MM. */
fun hmOf(ms: Long): String {
	val min = ms / 60_000
	return "%d:%02d".format(min / 60, min % 60)
}
