/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.geo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The offline maps ActivityTrack Web offers (issue #155, docs/OFFLINE-MAPS.md,
 * `GET maps`): small vector PMTiles files of OpenStreetMap data, each for an
 * area. The catalog is data from the network, so every field is checked: a file name can only be a
 * plain name, never a path, and the size and the SHA-256 are what the download
 * is checked against.
 */
data class OfflineMapEntry(
	val id: String,
	val name: String,
	/* relative to the catalog's URL */
	val file: String,
	val bytes: Long,
	val sha256: String,
	val minLon: Double,
	val minLat: Double,
	val maxLon: Double,
	val maxLat: Double,
	val maxZoom: Int,
	val updated: String?,
) {
}

object OfflineMapCatalog {
	const val SCHEMA = 1

	/* Largest file accepted, whatever the catalog says: the maps are meant to be a few MB. */
	const val MAX_BYTES = 64L * 1024 * 1024

	private val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
	private val FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.pmtiles")
	private val SHA = Regex("[0-9a-f]{64}")

	class Invalid(message: String) : Exception(message)

	/* Entries that pass the checks; a bad entry is left out, a bad document refused. */
	fun parse(json: String): List<OfflineMapEntry> {
		val root = try {
			Json.parseToJsonElement(json) as? JsonObject
		} catch (e: Exception) {
			null
		} ?: throw Invalid("not a JSON object")
		if ((root["schema"] as? JsonPrimitive)?.intOrNull != SCHEMA)
			throw Invalid("unsupported schema")
		val maps = root["maps"] as? JsonArray ?: throw Invalid("no maps")
		return maps.mapNotNull { (it as? JsonObject)?.let(::entry) }
	}

	private fun entry(o: JsonObject): OfflineMapEntry? {
		fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
		val id = str("id")?.takeIf { ID.matches(it) } ?: return null
		val file = str("file")?.takeIf { FILE.matches(it) } ?: return null
		val sha = str("sha256")?.lowercase()?.takeIf { SHA.matches(it) } ?: return null
		val bytes = (o["bytes"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..MAX_BYTES } ?: return null
		val bbox = (o["bbox"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
			?.takeIf { it.size == 4 } ?: return null
		val (minLon, minLat, maxLon, maxLat) = bbox
		if (minLat !in -90.0..90.0 || maxLat !in -90.0..90.0 || minLon !in -180.0..180.0 || maxLon !in -180.0..180.0 ||
			minLat >= maxLat || minLon >= maxLon
		)
			return null
		val zoom = (o["maxZoom"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..16 } ?: return null
		return OfflineMapEntry(id, str("name")?.take(80)?.ifBlank { null } ?: id, file, bytes, sha, minLon, minLat, maxLon, maxLat, zoom, str("updated")?.take(32)?.ifBlank { null })
	}
}
