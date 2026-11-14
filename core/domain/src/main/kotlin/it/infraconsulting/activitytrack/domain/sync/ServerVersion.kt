/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * What the server said of itself in its last head answer (issue #194):
 * `{"server": version, "schema": n, "minSchema": m}`, each optional (an
 * older server omits them). Kept for the sync screen, beside the app's
 * version and WireVersion.SCHEMA.
 */
@Serializable
data class ServerVersion(val server: String? = null, val schema: Int? = null, val minSchema: Int? = null) {
	fun encode(): String = JSON.encodeToString(serializer(), this)

	/* "1.0.0 · protocol 3 (oldest 2)": the version, then the schema ([schemaText]) and the oldest one still spoken ([min]) */
	fun text(schemaText: (Int) -> String, min: String): String = listOfNotNull(
		server,
		schema?.let { s -> schemaText(s) + if (minSchema != null && minSchema != s) " ($min $minSchema)" else "" },
	).joinToString(" · ")

	companion object {
		private val JSON = Json { ignoreUnknownKeys = true }

		/* from a head answer; null when it says none of the three */
		fun of(head: JsonObject): ServerVersion? {
			fun prim(k: String) = head[k] as? JsonPrimitive
			val v = ServerVersion(
				server = prim("server")?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null },
				schema = prim("schema")?.takeIf { !it.isString }?.intOrNull,
				minSchema = prim("minSchema")?.takeIf { !it.isString }?.intOrNull,
			)
			return v.takeIf { it.server != null || it.schema != null || it.minSchema != null }
		}

		fun decode(text: String?): ServerVersion? = text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
	}
}
