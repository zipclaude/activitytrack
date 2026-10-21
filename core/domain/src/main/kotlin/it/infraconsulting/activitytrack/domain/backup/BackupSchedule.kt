/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.backup

import java.time.Duration
import java.time.ZonedDateTime

/*
 * Automatic backup timing (owner, issue #44): every 6, 12 or 24 hours or
 * weekly, anchored to a preferred hour of the day. Android runs periodic
 * work only approximately: this gives the first run, the period does the rest.
 */
object BackupSchedule {
	val FREQUENCIES = listOf(6, 12, 24, 168)
	const val DEFAULT_FREQUENCY = 24
	const val DEFAULT_HOUR = 2

	/*
	 * Time until the next run: the next instant at [hour]:00, or at [hour]
	 * plus a multiple of [everyHours] for 6 and 12 hours (02, 08, 14, 20...).
	 */
	fun initialDelay(now: ZonedDateTime, everyHours: Int, hour: Int): Duration {
		val step = everyHours.coerceAtMost(24).toLong()
		var next = now.toLocalDate().atStartOfDay(now.zone).withHour(hour.coerceIn(0, 23) % step.toInt())
		while (!next.isAfter(now))
			next = next.plusHours(step)
		return Duration.between(now, next)
	}
}
