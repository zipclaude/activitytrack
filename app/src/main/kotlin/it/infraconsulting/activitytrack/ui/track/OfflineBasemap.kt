/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.track

import org.json.JSONArray
import org.json.JSONObject

/* What the track map draws under the track (issue #155). */
sealed interface Basemap {
	/* The OSM raster tiles of the configured endpoint. */
	data class Online(val tilesUrl: String) : Basemap

	/* The offline PMTiles file on the phone, [maxZoom] the last zoom it holds. */
	data class Offline(val path: String, val maxZoom: Int) : Basemap
}

private const val ATTRIBUTION = "© OpenStreetMap contributors · Protomaps"
private const val FONT = "NotoSansRegular"

/* Where the glyphs are: the Noto Sans ranges of the app's assets (SIL OFL, app/src/main/assets/fonts/OFL.txt). */
internal const val GLYPHS = "asset://fonts/{fontstack}/{range}.pbf"

/* The vector source of the offline file: MapLibre reads a PMTiles archive on the device with range requests. */
internal fun offlineSource(b: Basemap.Offline): JSONObject =
	JSONObject().put("type", "vector").put("url", "pmtiles://file://" + b.path)
		.put("maxzoom", b.maxZoom).put("attribution", ATTRIBUTION)

private fun zoomRamp(vararg stops: Pair<Int, Double>): JSONArray =
	JSONArray().put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom")).also { a ->
		stops.forEach { (z, v) -> a.put(z).put(v) }
	}

private fun kindIs(vararg kinds: String): JSONArray =
	JSONArray().put("in").put(JSONArray().put("get").put("kind")).put(JSONArray().put("literal").put(JSONArray(kinds.toList())))

private fun layer(id: String, type: String, layer: String, filter: JSONArray?, paint: JSONObject, layout: JSONObject? = null, minzoom: Int? = null) =
	JSONObject().put("id", id).put("type", type).put("source", "basemap").put("source-layer", layer)
		.also { o ->
			filter?.let { o.put("filter", it) }
			layout?.let { o.put("layout", it) }
			minzoom?.let { o.put("minzoom", it) }
		}.put("paint", paint)

/* Background, land, water, borders, roads and place names of the Protomaps basemap, the layers kept in the offline files. */
internal fun offlineLayers(): List<JSONObject> {
	val round = JSONObject().put("line-cap", "round").put("line-join", "round")
	fun road(id: String, color: String, filter: JSONArray, width: JSONArray, minzoom: Int? = null) =
		layer(id, "line", "roads", JSONArray().put("all").put(filter).put(JSONArray().put("!=").put(JSONArray().put("get").put("kind")).put("aeroway")),
			JSONObject().put("line-color", color).put("line-width", width), round, minzoom)
	fun place(id: String, detail: String, size: Int, minzoom: Int) =
		layer(
			id, "symbol", "places",
			JSONArray().put("all").put(JSONArray().put("==").put(JSONArray().put("get").put("kind")).put("locality"))
				.put(JSONArray().put("==").put(JSONArray().put("get").put("kind_detail")).put(detail)),
			JSONObject().put("text-color", "#333333").put("text-halo-color", "#ffffff").put("text-halo-width", 1.5),
			JSONObject().put("text-field", JSONArray().put("get").put("name")).put("text-font", JSONArray().put(FONT)).put("text-size", size)
				.put("text-max-width", 8),
			minzoom,
		)
	return listOf(
		JSONObject().put("id", "bg").put("type", "background").put("paint", JSONObject().put("background-color", "#a0c8f0")),
		layer("earth", "fill", "earth", null, JSONObject().put("fill-color", "#eaeddf")),
		layer("water", "fill", "water", JSONArray().put("==").put(JSONArray().put("geometry-type")).put("Polygon"), JSONObject().put("fill-color", "#a0c8f0")),
		layer(
			"rivers", "line", "water", JSONArray().put("==").put(JSONArray().put("geometry-type")).put("LineString"),
			JSONObject().put("line-color", "#8fbce8").put("line-width", zoomRamp(6 to 0.5, 12 to 2.0)), round,
		),
		layer(
			"borders", "line", "boundaries", kindIs("country", "region"),
			JSONObject().put("line-color", "#a05080").put("line-width", zoomRamp(4 to 0.6, 12 to 1.4)).put("line-dasharray", JSONArray().put(3).put(2)),
		),
		road("rail", "#9a9a9a", kindIs("rail"), zoomRamp(8 to 0.3, 12 to 1.0), 8),
		road("roads-minor", "#ffffff", kindIs("minor_road", "other", "path"), zoomRamp(10 to 0.3, 12 to 1.4), 10),
		road("roads-medium", "#fde3bf", kindIs("medium_road"), zoomRamp(8 to 0.3, 10 to 0.7, 12 to 2.0)),
		road("roads-major", "#fbcf94", kindIs("major_road"), zoomRamp(5 to 0.4, 10 to 1.0, 12 to 2.8)),
		road("roads-highway", "#f9a05a", kindIs("highway"), zoomRamp(4 to 0.6, 10 to 1.8, 12 to 3.4)),
		place("places-village", "village", 11, 10),
		place("places-town", "town", 12, 8),
		place("places-city", "city", 15, 4),
	)
}
