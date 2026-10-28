/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Reading of a hand-written JSON file (policy.json, plugin descriptors):
 * typed getters that record an error with path and line instead of
 * throwing, so every problem of the file is reported at once.
 */
open class CheckedReader(val text: String) {
	val errors = mutableListOf<PolicyError>()

	fun error(path: String, message: String) {
		errors += PolicyError(path, PolicyParser.lineOf(text, path), message)
	}

	fun obj(e: JsonElement?, path: String): JsonObject? = when (e) {
		null -> null
		is JsonObject -> e
		else -> null.also { error(path, "must be an object") }
	}

	fun keys(o: JsonObject, path: String, allowed: Set<String>) {
		for (k in o.keys - allowed)
			error(join(path, k), "unknown key (allowed: ${allowed.sorted().joinToString()})")
	}

	fun bool(e: JsonElement?, path: String): Boolean? = when {
		e == null -> null
		e is JsonPrimitive && !e.isString && e.booleanOrNull != null -> e.booleanOrNull
		else -> null.also { error(path, "must be true or false") }
	}

	fun int(e: JsonElement?, path: String, range: IntRange): Int? {
		if (e == null)
			return null
		val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
		return when {
			v == null -> null.also { error(path, "must be an integer") }
			v !in range -> null.also { error(path, "must be in ${range.first}..${range.last}") }
			else -> v
		}
	}

	fun double(e: JsonElement?, path: String): Double? {
		if (e == null)
			return null
		return (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: null.also { error(path, "must be a number") }
	}

	fun string(e: JsonElement?, path: String): String? = when {
		e == null -> null
		e is JsonPrimitive && e.isString -> e.content
		else -> null.also { error(path, "must be a string") }
	}

	fun <T> choice(e: JsonElement?, path: String, byKey: Map<String, T>): T? {
		val s = string(e, path) ?: return null
		return byKey[s] ?: null.also { error(path, "must be one of ${byKey.keys.joinToString()}") }
	}

	/* A label per language: { "it": "…", "en": "…" }. */
	fun labels(e: JsonElement?, path: String, max: Int = 40): Map<String, String> {
		val o = obj(e, path) ?: return emptyMap()
		return o.mapNotNull { (lang, v) ->
			val p = join(path, lang)
			val s = string(v, p) ?: return@mapNotNull null
			when {
				!LANGUAGE.matches(lang) -> null.also { error(p, "a language code like \"it\" or \"en\"") }
				s.isBlank() || s.length > max -> null.also { error(p, "1 to $max characters") }
				else -> lang to s.trim()
			}
		}.toMap()
	}

	fun join(path: String, key: String) = if (path.isEmpty()) key else if (key.isEmpty()) path else "$path.$key"

	private companion object {
		val LANGUAGE = Regex("[a-z]{2,3}")
	}
}
