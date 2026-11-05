/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.track

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/*
 * The span of the track map and export (issue #155): a day, a week (Monday to
 * Sunday, as the agenda) or a calendar month around an anchor day. Bounds are
 * local midnights, so a day of 23 or 25 hours on a DST change is right.
 */
enum class TrackPeriod {
	DAY,
	WEEK,
	MONTH;

	/* First day and the day after the last one. */
	fun days(anchor: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
		DAY -> anchor to anchor.plusDays(1)
		WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(7) }
		MONTH -> anchor.withDayOfMonth(1).let { it to it.plusMonths(1) }
	}

	/* Epoch milliseconds, [from, to). */
	fun bounds(anchor: LocalDate, zone: ZoneId): Pair<Long, Long> {
		val (a, b) = days(anchor)
		return a.atStartOfDay(zone).toInstant().toEpochMilli() to b.atStartOfDay(zone).toInstant().toEpochMilli()
	}

	/* The anchor [n] periods away (negative: before). */
	fun shift(anchor: LocalDate, n: Long): LocalDate = when (this) {
		DAY -> anchor.plusDays(n)
		WEEK -> anchor.plusWeeks(n)
		MONTH -> anchor.plusMonths(n)
	}
}
