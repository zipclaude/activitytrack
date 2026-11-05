/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.track

import it.infraconsulting.activitytrack.domain.geo.Geo
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos

/*
 * What the track map and the GPX / KML export show (issue #155): the location
 * samples grouped by the entry and the segment they were recorded for, with
 * what was done there. Points keep the accuracy they were recorded with;
 * [TrackPoint.id] is the row of the sample, to delete exactly what was
 * exported (0 when not from the database).
 */
data class TrackPoint(val tsUtcMs: Long, val lat: Double, val lon: Double, val accuracyM: Double?, val id: Long = 0) {
	val point get() = GeoPoint(lat, lon)
}

/* [kind] is null for samples whose segment was deleted afterwards. */
data class TrackSegment(
	val kind: SegmentKind?,
	val startUtcMs: Long?,
	val endUtcMs: Long?,
	val kmEstimated: Double?,
	val kmDeclared: Double?,
	val points: List<TrackPoint>,
) {
	/* the declared km win over the estimated, as in the exports */
	val km: Double? get() = kmDeclared ?: kmEstimated
}

data class TrackEntry(
	val entryId: Long,
	val zoneId: String,
	val activity: String?,
	val company: String?,
	val client: String?,
	val role: String?,
	val locality: String?,
	val tags: List<String>,
	val segments: List<TrackSegment>,
) {
	val points: List<TrackPoint> get() = segments.flatMap { it.points }

	val startUtcMs: Long? get() = segments.mapNotNull { it.startUtcMs ?: it.points.firstOrNull()?.tsUtcMs }.minOrNull()

	val endUtcMs: Long? get() = segments.mapNotNull { it.endUtcMs ?: it.points.lastOrNull()?.tsUtcMs }.maxOrNull()

	val zone: ZoneId get() = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC"))

	/* local day of the first sample or segment, where the entry's track is filed */
	val day: LocalDate? get() = (startUtcMs ?: return null).let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

	val km: Double? get() = segments.mapNotNull { it.km }.takeIf { it.isNotEmpty() }?.sum()
}

object TrackGeometry {
	/* Length of the polyline in metres. */
	fun lengthM(points: List<TrackPoint>): Double =
		points.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a.point, b.point) }

	/* The entry with a sample closest to [at], if within [toleranceM] metres: what a tap on the map selects. */
	fun nearest(entries: List<TrackEntry>, at: GeoPoint, toleranceM: Double): TrackEntry? {
		var best: TrackEntry? = null
		var bestD = toleranceM
		for (e in entries)
			for (p in e.points) {
				val d = Geo.distanceM(at, p.point)
				if (d <= bestD) {
					bestD = d
					best = e
				}
			}
		return best
	}

	/*
	 * Douglas–Peucker with a tolerance in metres, for drawing long periods:
	 * the first and the last point always stay. Iterative, since a month has
	 * thousands of points. Distances use a local equirectangular projection,
	 * exact enough at the scale of a tolerance of a few metres.
	 */
	fun simplify(points: List<TrackPoint>, toleranceM: Double): List<TrackPoint> {
		if (points.size < 3 || toleranceM <= 0)
			return points
		val lat0 = Math.toRadians(points.sumOf { it.lat } / points.size)
		val x = DoubleArray(points.size) { Math.toRadians(points[it].lon) * cos(lat0) * Geo.EARTH_RADIUS_M }
		val y = DoubleArray(points.size) { Math.toRadians(points[it].lat) * Geo.EARTH_RADIUS_M }
		val keep = BooleanArray(points.size)
		keep[0] = true
		keep[points.lastIndex] = true
		val stack = ArrayDeque<IntRange>()
		stack.addLast(0..points.lastIndex)
		while (stack.isNotEmpty()) {
			val r = stack.removeLast()
			var far = -1
			var farD = toleranceM
			for (i in r.first + 1 until r.last) {
				val d = segmentDistance(x[i], y[i], x[r.first], y[r.first], x[r.last], y[r.last])
				if (d > farD) {
					farD = d
					far = i
				}
			}
			if (far >= 0) {
				keep[far] = true
				stack.addLast(r.first..far)
				stack.addLast(far..r.last)
			}
		}
		return points.filterIndexed { i, _ -> keep[i] }
	}

	private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
		val dx = bx - ax
		val dy = by - ay
		val len2 = dx * dx + dy * dy
		val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
		return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
	}
}
