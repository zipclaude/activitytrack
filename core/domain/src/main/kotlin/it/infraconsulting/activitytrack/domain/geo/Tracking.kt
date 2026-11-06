/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.geo

/* A location fix; [accuracyM] is the provider's 68 % confidence radius. */
data class Fix(val atMs: Long, val point: GeoPoint, val accuracyM: Double)

/*
 * Movement during WORK (SPEC §5, DESIGN §5). The anchor is the first fix
 * accurate enough to tell the threshold apart (accuracy <= threshold); a fix
 * is away when distance - accuracy > threshold. The caller passes the fixes
 * since the start of the WORK segment, or since the last "ignore".
 */
object Movement {
	/*
	 * Departure time: the first of two consecutive away fixes, i.e. the
	 * first fix that observed the move (owner decision 2026-10-17); null
	 * while still at the anchor.
	 */
	fun departure(fixes: List<Fix>, thresholdM: Double): Long? {
		val i0 = fixes.indexOfFirst { it.accuracyM <= thresholdM }
		if (i0 < 0)
			return null
		val anchor = fixes[i0].point
		val away = fixes.drop(i0 + 1).map { Geo.distanceM(anchor, it.point) - it.accuracyM > thresholdM }
		for (i in 0 until away.size - 1) {
			if (away[i] && away[i + 1])
				return fixes[i0 + 1 + i].atMs
		}
		return null
	}
}

/*
 * Arrival during TRAVEL: the longest run of latest fixes lying within
 * [radiusM] of their centroid, once it spans at least [windowMs]. Fixes
 * less accurate than the radius cannot tell a stop from a move and are
 * skipped. The caller passes the fixes of the current TRAVEL segment.
 */
object Stationarity {
	/* Arrival time: the first fix of the stationary run (owner decision 2026-10-17). */
	fun arrival(fixes: List<Fix>, radiusM: Double, windowMs: Long): Long? {
		val good = fixes.filter { it.accuracyM <= radiusM }
		if (good.size < 2)
			return null
		var start = good.size - 1
		while (start > 0 && withinRadius(good.subList(start - 1, good.size), radiusM))
			start--
		if (start == good.size - 1)
			return null
		val first = good[start].atMs
		return if (good.last().atMs - first >= windowMs) first else null
	}

	/* Centroid as plain mean of coordinates: fine at a few hundred metres, away from the antimeridian. */
	private fun withinRadius(run: List<Fix>, radiusM: Double): Boolean {
		val c = GeoPoint(run.sumOf { it.point.lat } / run.size, run.sumOf { it.point.lon } / run.size)
		return run.all { Geo.distanceM(c, it.point) <= radiusM }
	}
}

/*
 * Travel distance (SPEC §6), per leg between consecutive fixes:
 * - fixes at most [DENSE_MS] apart follow the road themselves: the straight
 *   line between them is the distance, no routing, no factor;
 * - farther apart, the road distance when known (OSRM), else the straight
 *   line times the factor;
 * - a leg shorter than the larger accuracy of its ends is GPS jitter and
 *   counts zero, so a long stop does not accumulate distance;
 * - a leg that would need more than [MAX_SPEED_KMH] counts zero too, and
 *   [plausible] drops such stray fixes beforehand.
 */
object KmEstimate {
	/* fixes this close in time (2 min) are joined directly */
	const val DENSE_MS = 120_000L

	/* no vehicle of the tracked trips is faster: a fix that implies more is wrong */
	const val MAX_SPEED_KMH = 250.0

	/*
	 * A road leg longer than this times its straight line is a waypoint the
	 * router snapped to the wrong road (the other carriageway, a parallel
	 * lane), not a real detour: the factor is used instead.
	 */
	const val MAX_ROAD_RATIO = 3.0

	/*
	 * The fixes without the stray ones: a fix is dropped when it implies
	 * more than [MAX_SPEED_KMH] both from the previous kept fix and to the
	 * next one (a lone point, in the sea or anywhere), or, at the ends of
	 * the list, from its only neighbour. Speeds ignore the part of a
	 * distance covered by the accuracy of the two fixes.
	 */
	fun plausible(fixes: List<Fix>): List<Fix> {
		val kept = ArrayList<Fix>(fixes.size)
		for ((i, f) in fixes.withIndex()) {
			val prev = kept.lastOrNull()
			val next = fixes.getOrNull(i + 1)
			val fastIn = prev != null && tooFast(prev, f)
			val fastOut = next != null && tooFast(f, next)
			val stray = when {
				prev == null -> next != null && fastOut && fixes.getOrNull(i + 2)?.let { !tooFast(next, it) } != false
				next == null -> fastIn
				else -> fastIn && fastOut
			}
			if (!stray)
				kept += f
		}
		return kept
	}

	/*
	 * The legs (index i = fixes[i] to fixes[i + 1]) a road distance is worth
	 * asking for, in runs of consecutive legs: those farther apart in time
	 * than [DENSE_MS] that are neither jitter nor impossible.
	 */
	fun roadRuns(fixes: List<Fix>): List<IntRange> {
		val runs = ArrayList<IntRange>()
		var start = -1
		for (i in 0 until fixes.size - 1) {
			if (needsRoad(fixes[i], fixes[i + 1])) {
				if (start < 0)
					start = i
			} else if (start >= 0) {
				runs += start until i
				start = -1
			}
		}
		if (start >= 0)
			runs += start until fixes.size - 1
		return runs
	}

	/* [roadM]: road distance by leg index, for the legs [roadRuns] named and the router answered. */
	fun km(fixes: List<Fix>, factor: Double, roadM: Map<Int, Double> = emptyMap()): Double {
		var m = 0.0
		for (i in 0 until fixes.size - 1) {
			val a = fixes[i]
			val b = fixes[i + 1]
			val straight = Geo.distanceM(a.point, b.point)
			if (straight <= maxOf(a.accuracyM, b.accuracyM) || tooFast(a, b))
				continue
			m += if (b.atMs - a.atMs <= DENSE_MS) straight
			else roadM[i]?.takeIf { it <= straight * MAX_ROAD_RATIO } ?: (straight * factor)
		}
		return m / 1000.0
	}

	private fun needsRoad(a: Fix, b: Fix) =
		b.atMs - a.atMs > DENSE_MS && Geo.distanceM(a.point, b.point) > maxOf(a.accuracyM, b.accuracyM) && !tooFast(a, b)

	private fun tooFast(a: Fix, b: Fix): Boolean {
		val m = Geo.distanceM(a.point, b.point) - maxOf(a.accuracyM, b.accuracyM)
		val s = maxOf(b.atMs - a.atMs, 1_000L) / 1000.0
		return m / s * 3.6 > MAX_SPEED_KMH
	}
}
