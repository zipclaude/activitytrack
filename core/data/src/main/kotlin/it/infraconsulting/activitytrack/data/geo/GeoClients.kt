/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.geo

import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Online geo services (A10, DESIGN §9): each optional, endpoint from the
 * settings. Plain HttpURLConnection and the platform org.json, no extra
 * dependency. Coordinates always use '.' as decimal separator.
 */
private const val TIMEOUT_MS = 10_000

private fun coord(v: Double) = String.format(Locale.ROOT, "%.6f", v)

private suspend fun getJson(url: String, userAgent: String): JSONObject = withContext(Dispatchers.IO) {
	val conn = URL(url).openConnection() as HttpURLConnection
	try {
		conn.connectTimeout = TIMEOUT_MS
		conn.readTimeout = TIMEOUT_MS
		conn.setRequestProperty("User-Agent", userAgent)
		conn.setRequestProperty("Accept", "application/json")
		val code = conn.responseCode
		if (code != HttpURLConnection.HTTP_OK)
			throw java.io.IOException("HTTP $code from ${conn.url.host}")
		JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
	} finally {
		conn.disconnect()
	}
}

/*
 * Reverse geocoding (Nominatim usage policy: identifying User-Agent, at most
 * one request per second, cache results). Cached by coordinates rounded to
 * 3 decimals (about 100 m), the size of a site.
 */
class Nominatim(private val userAgent: String) {
	private val mutex = Mutex()
	private var lastRequestMs = 0L
	private val cache = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > CACHE_SIZE
	}

	/* Locality (city, town, village...) at [p] in [language]; null when the place has none. */
	suspend fun locality(endpoint: String, p: GeoPoint, language: String): String? = mutex.withLock {
		val key = String.format(Locale.ROOT, "%.3f,%.3f", p.lat, p.lon)
		if (cache.containsKey(key))
			return@withLock cache[key]
		val wait = lastRequestMs + MIN_INTERVAL_MS - System.currentTimeMillis()
		if (wait > 0)
			delay(wait)
		lastRequestMs = System.currentTimeMillis()
		val url = endpoint.trimEnd('/') + "/reverse?format=jsonv2&zoom=14" +
			"&lat=${coord(p.lat)}&lon=${coord(p.lon)}" +
			"&accept-language=" + URLEncoder.encode(language, "UTF-8")
		parseLocality(getJson(url, userAgent)).also { cache[key] = it }
	}

	companion object {
		private const val MIN_INTERVAL_MS = 1_100L
		private const val CACHE_SIZE = 256
		private val LEVELS = listOf("city", "town", "village", "hamlet", "municipality", "suburb")

		fun parseLocality(json: JSONObject): String? {
			val address = json.optJSONObject("address") ?: return null
			return LEVELS.firstNotNullOfOrNull { address.optString(it).trim().ifEmpty { null } }
		}
	}
}

/*
 * Road distance (OSRM route service, driving profile): one request per
 * chunk of waypoints, the route's legs give the distance of each pair of
 * consecutive points. Chunks overlap by one point so no leg is lost.
 */
class Osrm(private val userAgent: String) {
	/* Metres along the road for each leg of [points] (size - 1); null when no route was found. */
	suspend fun legsM(endpoint: String, points: List<GeoPoint>): List<Double>? {
		if (points.size < 2)
			return emptyList()
		val out = ArrayList<Double>()
		var from = 0
		while (from < points.size - 1) {
			val chunk = points.subList(from, minOf(points.size, from + MAX_POINTS))
			val url = endpoint.trimEnd('/') + "/route/v1/driving/" +
				chunk.joinToString(";") { "${coord(it.lon)},${coord(it.lat)}" } + "?overview=false"
			out += parseLegs(getJson(url, userAgent), chunk.size - 1) ?: return null
			from += chunk.size - 1
		}
		return out
	}

	companion object {
		/* conservative waypoints per request (osrm-routed --max-viaroute-size defaults to 500) */
		private const val MAX_POINTS = 100

		fun parseLegs(json: JSONObject, expected: Int): List<Double>? {
			if (json.optString("code") != "Ok")
				return null
			val legs = json.optJSONArray("routes")?.optJSONObject(0)?.optJSONArray("legs") ?: return null
			if (legs.length() != expected)
				return null
			return (0 until legs.length()).map { legs.getJSONObject(it).optDouble("distance") }
				.takeIf { list -> list.none { it.isNaN() } }
		}
	}
}
