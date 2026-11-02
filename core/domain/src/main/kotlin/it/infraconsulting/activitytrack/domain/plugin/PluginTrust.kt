/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The user's confirmation of an unsigned plugin descriptor (owner,
 * security review 2026-11): a new descriptor, or one whose addresses
 * changed since the version last confirmed, is used only after the user
 * confirmed it; a descriptor whose addresses stay the same is used at once.
 * A refusal is remembered by fingerprint until the file changes. A plugin
 * signed by the pinned signer needs no confirmation.
 */
object PluginTrust {
	/* what the user confirmed last for one file */
	data class Confirmed(val fingerprint: String, val endpoints: Set<String>)

	sealed interface Decision {
		/* use the plugin; [record] = store [Confirmed] anew (same addresses, another file) */
		data class Use(val record: Boolean) : Decision
		/* ask the user; [added]/[removed] against the last confirmed version (new: all added) */
		data class Ask(val isNew: Boolean, val added: List<String>, val removed: List<String>) : Decision
		/* refused by the user, this very file */
		data object Refused : Decision
	}

	/* The keys whose addresses get the credentials: shown by name, the other URLs by value. */
	private val NAMED = listOf("http" to "baseUrl", "auth" to "issuer", "auth" to "authorizeUrl", "auth" to "tokenUrl", "auth" to "type")

	/*
	 * Where the plugin sends data and credentials: the base URL, the
	 * authentication type and endpoints ("auth.tokenUrl: …", the JSON of the
	 * value, so a reference to an option counts too) and every other string
	 * holding an absolute URL (option defaults, absolute paths) as "url: …".
	 */
	fun endpoints(descriptor: String): Set<String> {
		val root = runCatching { Json.parseToJsonElement(descriptor) as? JsonObject }.getOrNull() ?: return emptySet()
		val out = sortedSetOf<String>()
		for ((section, key) in NAMED)
			((root[section] as? JsonObject)?.get(key))?.let { out += "$section.$key: $it" }
		fun walk(e: JsonElement, named: Boolean) {
			when (e) {
				is JsonObject -> e.values.forEach { walk(it, named) }
				is JsonArray -> e.forEach { walk(it, named) }
				is JsonPrimitive -> if (!named && e.isString && "://" in e.content) out += "url: ${e.content}"
			}
		}
		for ((k, v) in root) {
			if (v is JsonObject && (k == "http" || k == "auth")) {
				for ((sub, x) in v)
					walk(x, NAMED.contains(k to sub))
			} else {
				walk(v, false)
			}
		}
		return out
	}

	fun decide(fingerprint: String, endpoints: Set<String>, confirmed: Confirmed?, refused: String?, signedByPinned: Boolean): Decision {
		if (signedByPinned)
			return Decision.Use(record = false)
		if (confirmed != null && confirmed.fingerprint == fingerprint)
			return Decision.Use(record = false)
		if (confirmed != null && confirmed.endpoints == endpoints)
			return Decision.Use(record = true)
		if (refused == fingerprint)
			return Decision.Refused
		if (confirmed == null)
			return Decision.Ask(isNew = true, added = endpoints.toList(), removed = emptyList())
		return Decision.Ask(isNew = false, added = (endpoints - confirmed.endpoints).toList(), removed = (confirmed.endpoints - endpoints).toList())
	}

	/* the stored form of [Confirmed] */
	fun encode(c: Confirmed): String =
		JsonObject(mapOf("fingerprint" to JsonPrimitive(c.fingerprint), "endpoints" to JsonArray(c.endpoints.map(::JsonPrimitive)))).toString()

	fun decode(text: String?): Confirmed? = runCatching {
		val o = Json.parseToJsonElement(text ?: return null) as JsonObject
		Confirmed(
			(o["fingerprint"] as JsonPrimitive).content,
			(o["endpoints"] as JsonArray).map { (it as JsonPrimitive).content }.toSet(),
		)
	}.getOrNull()
}
