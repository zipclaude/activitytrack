/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double) {
	init {
		require(lat in -90.0..90.0) { "latitude out of range: $lat" }
		require(lon in -180.0..180.0) { "longitude out of range: $lon" }
	}
}

object Geo {
	/* IUGG mean Earth radius (m). */
	private const val EARTH_RADIUS_M = 6_371_008.8

	/* Great-circle distance in metres (haversine). */
	fun distanceM(a: GeoPoint, b: GeoPoint): Double {
		val p1 = Math.toRadians(a.lat)
		val p2 = Math.toRadians(b.lat)
		val dp = p2 - p1
		val dl = Math.toRadians(b.lon - a.lon)
		val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
		return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
	}
}
