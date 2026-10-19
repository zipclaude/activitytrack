/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.calls

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/* Phone numbers as stored and compared (issue #28). */
object PhoneNumbers {
	/* digits only, with the leading + kept: "+39 333 123-45 67" -> "+393331234567" */
	fun normalize(raw: String): String {
		val t = raw.trim()
		val digits = t.filter { it.isDigit() }
		return if (t.startsWith("+") || t.startsWith("00")) "+" + digits.removePrefix(if (t.startsWith("00")) "00" else "") else digits
	}

	/*
	 * The same line with or without the international prefix: the last 9
	 * digits match (Italian mobiles have 10, landlines 6-11); shorter numbers
	 * must match exactly.
	 */
	fun same(a: String, b: String): Boolean {
		val x = normalize(a).trimStart('+')
		val y = normalize(b).trimStart('+')
		if (x.isEmpty() || y.isEmpty())
			return false
		return if (x.length >= 9 && y.length >= 9) x.takeLast(9) == y.takeLast(9) else x == y
	}
}

/* A running entry as the call logic sees it. */
data class RunningEntry(val id: Long, val clientId: Long?, val working: Boolean)

/* Call settings (issue #28): all off until the user enables recognition. */
data class CallSettings(
	val incoming: Boolean = false,
	val outgoing: Boolean = false,
	val autoPause: Boolean = true,
	val proposeEntry: Boolean = true,
)

/* What to do once a recognised call ends. */
data class CallOutcome(
	/* the entry to pause during the call, null = none */
	val pauseEntryId: Long? = null,
	/* propose a new entry for the call's client, with these rounded times */
	val propose: Boolean = false,
	val proposedStart: Instant? = null,
	val proposedEnd: Instant? = null,
)

object CallRules {
	val MIN_PAUSE: Duration = Duration.ofMinutes(1)

	/*
	 * Owner, issue #28: a call with a contact during working hours.
	 * - While working for the same client: nothing (the call is part of it).
	 * - While working for another client: that entry is paused for the call
	 *   (auto pause on; a pause under a minute is not recorded).
	 * - Unless working for the same client, the call itself is proposed as
	 *   an entry (option), start rounded down and end up to 5 minutes.
	 */
	fun decide(
		incoming: Boolean,
		start: Instant,
		end: Instant,
		clientId: Long,
		running: List<RunningEntry>,
		settings: CallSettings,
		workingHours: Boolean,
		zone: ZoneId,
	): CallOutcome? {
		if (!workingHours || (incoming && !settings.incoming) || (!incoming && !settings.outgoing) || !end.isAfter(start))
			return null
		val working = running.filter { it.working }
		if (working.any { it.clientId == clientId })
			return CallOutcome()
		val pause = if (settings.autoPause && Duration.between(start, end) >= MIN_PAUSE) working.firstOrNull()?.id else null
		return CallOutcome(
			pauseEntryId = pause,
			propose = settings.proposeEntry,
			proposedStart = floor5(start, zone),
			proposedEnd = ceil5(end, zone),
		)
	}

	fun floor5(t: Instant, zone: ZoneId): Instant {
		val z = t.atZone(zone).truncatedTo(ChronoUnit.MINUTES)
		return z.minusMinutes((z.minute % 5).toLong()).toInstant()
	}

	fun ceil5(t: Instant, zone: ZoneId): Instant {
		val f = floor5(t, zone)
		return if (f == t) f else f.atZone(zone).plusMinutes(5).toInstant()
	}
}
