/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.todo

/*
 * The plan of an absence to-do (issue #158) that a recorded absence of the
 * day-start prompt stands for: the whole day, or the part of the working
 * hours [first]..[last] (minutes of the day) before a late entry / after an
 * early exit at [timeMin].
 */
object AbsenceSpan {
	/* (start, duration) of the plan; null, null = the whole day. */
	data class Span(val startMin: Int?, val durationMin: Int?)

	/* null: the time is outside the working hours, nothing was missed. */
	fun of(kind: Kind, timeMin: Int?, first: Int, last: Int): Span? = when (kind) {
		Kind.FULL_DAY -> Span(null, null)
		Kind.LATE_ENTRY -> timeMin?.takeIf { it > first }?.let { Span(first, it - first) }
		Kind.EARLY_EXIT -> timeMin?.takeIf { it < last }?.let { Span(it, last - it) }
	}

	enum class Kind {
		FULL_DAY, LATE_ENTRY, EARLY_EXIT;

		companion object {
			fun of(name: String): Kind? = entries.firstOrNull { it.name == name }
		}
	}
}
