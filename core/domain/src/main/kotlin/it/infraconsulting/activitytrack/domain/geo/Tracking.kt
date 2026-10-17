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
 * Travel distance (SPEC §6): per leg between consecutive fixes, the road
 * distance when known (OSRM), else the straight line times [factor]. Legs
 * shorter than the larger accuracy of their ends are GPS jitter and count
 * zero, so a long stop does not accumulate distance.
 */
object KmEstimate {
	/* [roadM]: road distance per leg (size = fixes.size - 1), null where unknown. */
	fun km(fixes: List<Fix>, factor: Double, roadM: List<Double?> = List(maxOf(0, fixes.size - 1)) { null }): Double {
		require(roadM.size == maxOf(0, fixes.size - 1)) { "one road distance per leg" }
		var m = 0.0
		for (i in 0 until fixes.size - 1) {
			val a = fixes[i]
			val b = fixes[i + 1]
			val straight = Geo.distanceM(a.point, b.point)
			if (straight <= maxOf(a.accuracyM, b.accuracyM))
				continue
			m += roadM[i] ?: (straight * factor)
		}
		return m / 1000.0
	}
}
