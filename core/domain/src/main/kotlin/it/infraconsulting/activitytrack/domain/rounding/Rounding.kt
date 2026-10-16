/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.rounding

import java.time.Duration

/*
 * Rounding modes applied to reported durations. Stored timestamps are never
 * rounded; rounding happens only when building reports and exports.
 *
 * CEIL_TOLERANT: any non-zero duration not longer than one quantum counts as
 *   one quantum; above that, the remainder r is dropped when 3 * r < quantum,
 *   otherwise the duration is rounded up (tolerance = quantum / 3).
 *   Example with quantum = 15 min: 7 -> 15, 19:59 -> 15, 20 -> 30, 34:59 -> 30.
 * CEIL: round up to the next multiple of the quantum.
 * FLOOR: round down to the previous multiple of the quantum.
 *
 * In every mode a zero duration stays zero, and a non-zero result is raised
 * to [RoundingRule.minimum] when lower.
 */
enum class RoundingMode {
	CEIL_TOLERANT,
	CEIL,
	FLOOR,
}

data class RoundingRule(
	val mode: RoundingMode,
	val quantum: Duration,
	val minimum: Duration = Duration.ZERO,
) {
	init {
		require(!quantum.isNegative && !quantum.isZero) { "quantum must be positive" }
		require(!minimum.isNegative) { "minimum must not be negative" }
	}

	fun apply(value: Duration): Duration {
		require(!value.isNegative) { "duration must not be negative" }
		if (value.isZero)
			return Duration.ZERO

		val q = quantum.toNanos()
		val v = value.toNanos()
		val r = v % q
		val base = v - r

		val rounded = when (mode) {
			RoundingMode.CEIL_TOLERANT ->
				when {
					v <= q -> q
					r == 0L -> v
					3 * r < q -> base
					else -> base + q
				}
			RoundingMode.CEIL -> if (r == 0L) v else base + q
			RoundingMode.FLOOR -> base
		}
		return maxOf(Duration.ofNanos(rounded), minimum)
	}
}

/* Rounding rules applied by one export profile. */
data class ReportRounding(
	val work: RoundingRule,
	val travel: RoundingRule,
	val pause: RoundingRule,
) {
	companion object {
		val DEFAULT = ReportRounding(
			work = RoundingRule(RoundingMode.CEIL_TOLERANT, Duration.ofMinutes(15)),
			travel = RoundingRule(RoundingMode.CEIL, Duration.ofMinutes(5)),
			pause = RoundingRule(
				RoundingMode.FLOOR,
				Duration.ofMinutes(5),
				minimum = Duration.ofMinutes(5),
			),
		)
	}
}
