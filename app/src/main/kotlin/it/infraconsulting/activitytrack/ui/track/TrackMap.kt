/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.track

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.track.TrackEntry
import it.infraconsulting.activitytrack.domain.track.TrackGeometry
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/* Centre of Italy at country zoom, while nothing is drawn. */
private val FALLBACK = LatLng(42.5, 12.5)

/* Points are drawn one by one only while they are few enough to stay readable. */
private const val MAX_DRAWN_POINTS = 600

/* Tap tolerance in map pixels. */
private const val TAP_PX = 28.0

/*
 * The track on the OSM tiles (issue #155): a line per segment, green for
 * work and blue for travel, a green and a red dot at the start and the end of
 * each activity, the recorded samples as small dots when few. [onTap] gets the
 * tapped point and the tolerance in metres. MapLibre shows the OSM attribution.
 */
@Composable
fun TrackMap(
	tracks: List<TrackEntry>,
	basemap: Basemap,
	toleranceM: Double,
	onTap: (GeoPoint, Double) -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val tap by rememberUpdatedState(onTap)
	val view = remember {
		MapLibre.getInstance(context.applicationContext)
		MapView(context).apply {
			onCreate(null)
			getMapAsync { m ->
				m.cameraPosition = CameraPosition.Builder().target(FALLBACK).zoom(5.0).build()
				m.addOnMapClickListener { ll ->
					tap(GeoPoint(ll.latitude, ll.longitude), m.projection.getMetersPerPixelAtLatitude(ll.latitude) * TAP_PX)
					true
				}
			}
		}
	}
	DisposableEffect(lifecycle) {
		val observer = LifecycleEventObserver { _, event ->
			when (event) {
				Lifecycle.Event.ON_START -> view.onStart()
				Lifecycle.Event.ON_RESUME -> view.onResume()
				Lifecycle.Event.ON_PAUSE -> view.onPause()
				Lifecycle.Event.ON_STOP -> view.onStop()
				else -> {}
			}
		}
		lifecycle.addObserver(observer)
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
			view.onStart()
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
			view.onResume()
		onDispose {
			lifecycle.removeObserver(observer)
			view.onPause()
			view.onStop()
			view.onDestroy()
		}
	}
	/* a new style per change of data: simple, and the data are small once simplified */
	LaunchedEffect(tracks, basemap, toleranceM) {
		view.getMapAsync { m ->
			m.setStyle(Style.Builder().fromJson(styleJson(basemap, tracks, toleranceM))) {
				fit(m, tracks)
			}
		}
	}
	AndroidView(factory = { view }, modifier = modifier.fillMaxSize())
}

private fun fit(m: org.maplibre.android.maps.MapLibreMap, tracks: List<TrackEntry>) {
	val all = tracks.flatMap { it.points }.map { LatLng(it.lat, it.lon) }
	if (all.isEmpty())
		return
	if (all.distinct().size == 1) {
		m.moveCamera(CameraUpdateFactory.newLatLngZoom(all.first(), 15.0))
		return
	}
	m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(all).build(), 72))
}

/* Style spec v8: the raster tiles and the track as GeoJSON. */
internal fun styleJson(basemap: Basemap, tracks: List<TrackEntry>, toleranceM: Double): String {
	val drawPoints = tracks.sumOf { it.points.size } <= MAX_DRAWN_POINTS
	val features = JSONArray()
	for (e in tracks) {
		for (s in e.segments) {
			val pts = TrackGeometry.simplify(s.points, toleranceM)
			if (pts.size >= 2)
				features.put(
					feature(
						JSONObject().put("type", "LineString").put("coordinates", JSONArray().also { a -> pts.forEach { a.put(coord(it.lon, it.lat)) } }),
						"kind" to (s.kind?.name ?: "OTHER"),
					),
				)
			if (drawPoints)
				s.points.forEach { features.put(point(it.lon, it.lat, "sample")) }
		}
		val first = e.points.firstOrNull() ?: continue
		val last = e.points.last()
		features.put(point(first.lon, first.lat, if (e.points.size == 1) "single" else "start"))
		if (e.points.size > 1)
			features.put(point(last.lon, last.lat, "end"))
	}
	val geo = JSONObject().put("type", "FeatureCollection").put("features", features)
	val line = JSONArray().put("==").put(JSONArray().put("geometry-type")).put("LineString")
	val sources = JSONObject()
	val layers = JSONArray()
	val style = JSONObject().put("version", 8)
	when (basemap) {
		is Basemap.Online -> {
			sources.put(
				"osm",
				JSONObject().put("type", "raster").put("tiles", JSONArray().put(basemap.tilesUrl)).put("tileSize", 256)
					.put("maxzoom", 19).put("attribution", "© OpenStreetMap contributors"),
			)
			layers.put(JSONObject().put("id", "osm").put("type", "raster").put("source", "osm"))
		}
		is Basemap.Offline -> {
			sources.put("basemap", offlineSource(basemap))
			style.put("glyphs", GLYPHS)
			offlineLayers().forEach { layers.put(it) }
		}
	}
	sources.put("track", JSONObject().put("type", "geojson").put("data", geo))
	layers
		.put(
			JSONObject().put("id", "track-line").put("type", "line").put("source", "track").put("filter", line)
				.put("layout", JSONObject().put("line-cap", "round").put("line-join", "round"))
				.put(
					"paint",
					JSONObject().put("line-width", 4).put("line-opacity", 0.85).put(
						"line-color",
						JSONArray().put("match").put(JSONArray().put("get").put("kind"))
							.put("TRAVEL").put("#1565c0").put("WORK").put("#2e7d32").put("#808080"),
					),
				),
		)
		.put(dots("track-samples", "sample", 3, "#ffffff", "#444444", 1))
		.put(dots("track-start", "start", 7, "#2e7d32", "#ffffff", 2))
		.put(dots("track-end", "end", 7, "#c62828", "#ffffff", 2))
		.put(dots("track-single", "single", 7, "#1565c0", "#ffffff", 2))
	return style.put("sources", sources).put("layers", layers).toString()
}

private fun coord(lon: Double, lat: Double) = JSONArray().put(lon).put(lat)

private fun feature(geometry: JSONObject, vararg props: Pair<String, String>) =
	JSONObject().put("type", "Feature").put("geometry", geometry)
		.put("properties", JSONObject().also { p -> props.forEach { (k, v) -> p.put(k, v) } })

private fun point(lon: Double, lat: Double, role: String) =
	feature(JSONObject().put("type", "Point").put("coordinates", coord(lon, lat)), "role" to role)

private fun dots(id: String, role: String, radius: Int, fill: String, stroke: String, strokeWidth: Int) =
	JSONObject().put("id", id).put("type", "circle").put("source", "track")
		.put("filter", JSONArray().put("==").put(JSONArray().put("get").put("role")).put(role))
		.put(
			"paint",
			JSONObject().put("circle-radius", radius).put("circle-color", fill)
				.put("circle-stroke-color", stroke).put("circle-stroke-width", strokeWidth),
		)
