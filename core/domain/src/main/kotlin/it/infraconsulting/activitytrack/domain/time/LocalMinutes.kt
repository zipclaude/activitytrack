/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Instant of the local wall-clock time [minuteOfDay] (0..1440) on [date].
 * Built from the local date-time, not by adding minutes to midnight: on DST
 * days "midnight + 240 min" is not 04:00. 1440 is midnight of the next day;
 * a time skipped by DST resolves forward (java.time rules).
 */
fun LocalDate.atMinute(minuteOfDay: Int, zone: ZoneId): Instant {
	require(minuteOfDay in 0..1440) { "minute of day out of range: $minuteOfDay" }
	if (minuteOfDay == 1440)
		return plusDays(1).atStartOfDay(zone).toInstant()
	return atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)).atZone(zone).toInstant()
}

/*
 * The latest moment, not after [now], at the wall-clock time [minuteOfDay]
 * (0..1439): today if already passed, else yesterday. A start or end time
 * typed without a date (M17) means the most recent one.
 */
fun latestAt(minuteOfDay: Int, now: Instant, zone: ZoneId): Instant {
	require(minuteOfDay in 0 until 1440) { "minute of day out of range: $minuteOfDay" }
	val today = now.atZone(zone).toLocalDate()
	val t = today.atMinute(minuteOfDay, zone)
	return if (t.isAfter(now)) today.minusDays(1).atMinute(minuteOfDay, zone) else t
}
