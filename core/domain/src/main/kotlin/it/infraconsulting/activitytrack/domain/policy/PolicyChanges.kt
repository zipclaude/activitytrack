/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * What a new policy changes against the one in force, for the user's
 * confirmation of an unsigned policy.json: one line per key of a section
 * ("settings.language", "features.gps") or per top-level value, in the
 * order of the keys. [old]/[new] are compact JSON, null = absent.
 */
object PolicyChanges {
	data class Change(val path: String, val old: String?, val new: String?)

	private val IGNORED = setOf("format", "schema")

	fun of(inForce: String?, incoming: String): List<Change> {
		val a = obj(inForce)
		val b = obj(incoming)
		val out = ArrayList<Change>()
		for (key in (a.keys + b.keys).filter { it !in IGNORED }.distinct().sorted()) {
			val x = a[key]
			val y = b[key]
			if (x == y)
				continue
			if (x is JsonObject? && y is JsonObject? && (x != null || y != null)) {
				val xs = x ?: JsonObject(emptyMap())
				val ys = y ?: JsonObject(emptyMap())
				for (sub in (xs.keys + ys.keys).distinct().sorted())
					if (xs[sub] != ys[sub])
						out += Change("$key.$sub", xs[sub]?.let(::text), ys[sub]?.let(::text))
			} else {
				out += Change(key, x?.let(::text), y?.let(::text))
			}
		}
		return out
	}

	private fun obj(text: String?): JsonObject =
		text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())

	/* compact and short: the lines are a summary, the file has the rest */
	private fun text(e: JsonElement): String = e.toString().let { if (it.length > MAX) it.take(MAX - 1) + "…" else it }

	private const val MAX = 80
}
