/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * The values a plugin's expressions see for one company: its options
 * (stored value, else the descriptor's default; secrets read on demand),
 * @identity and @lookup once fetched, and whatever [more] gives for the
 * roots of a phase (@entry, @block, @parent…). [with] layers more roots.
 */
class PluginEnv(
	val plugin: Plugin,
	private val values: Map<String, JsonElement>,
	private val secret: (String) -> String?,
	override val zone: ZoneId,
	val identity: MutableMap<String, JsonElement> = HashMap(),
	val lookups: MutableMap<String, JsonElement> = HashMap(),
	private val more: (Expr.Ref) -> JsonElement? = { null },
	private val rounding: (String, Long) -> Long = { _, m -> m },
	private val materialsOf: (String) -> JsonElement = { JsonNull },
) : EvalEnv {
	override fun ref(r: Expr.Ref): JsonElement {
		val name = r.path.firstOrNull()
		val rest = r.path.drop(1)
		return when (r.root) {
			"option" -> name?.let { option(it) }?.let { dig(it, rest) } ?: JsonNull
			"identity" -> name?.let { identity[it] }?.let { dig(it, rest) } ?: JsonNull
			"lookup" -> name?.let { lookups[it] }?.let { dig(it, rest) } ?: JsonNull
			else -> more(r) ?: JsonNull
		}
	}

	/* An option's value: secrets from the keystore, then the stored value, then the default; typed by the option. */
	fun option(key: String): JsonElement? {
		val o = plugin.option(key) ?: return null
		if (o.type == OptionType.SECRET)
			return secret(key)?.let(::JsonPrimitive)
		val v = values[key]?.takeIf { it !is JsonNull } ?: o.default ?: return null
		val p = v as? JsonPrimitive ?: return v
		return when (o.type) {
			OptionType.INT -> p.intOrNull?.let(::JsonPrimitive) ?: p.content.trim().toIntOrNull()?.let(::JsonPrimitive)
			OptionType.BOOL -> p.booleanOrNull?.let(::JsonPrimitive)
			OptionType.URL -> JsonPrimitive(p.content.trim().trimEnd('/'))
			else -> JsonPrimitive(p.content)
		}
	}

	/* Options that are required and missing: the integration is not usable until they are set. */
	fun missingOptions(): List<PluginOption> = plugin.options.filter { it.required && option(it.key).let { v -> v == null || (v is JsonPrimitive && v.content.isBlank()) } }

	override fun round(rule: String, minutes: Long) = rounding(rule, minutes)

	override fun materials(mode: String) = materialsOf(mode)

	/* The same env with more roots: [extra] wins over what this env gives. */
	fun with(extra: (Expr.Ref) -> JsonElement?): PluginEnv =
		PluginEnv(plugin, values, secret, zone, identity, lookups, { r -> extra(r) ?: more(r) }, rounding, materialsOf)

	companion object {
		/* a.b.c inside a value: object fields, list indexes */
		fun dig(e: JsonElement, path: List<String>): JsonElement {
			var cur = e
			for (k in path)
				cur = when (cur) {
					is JsonObject -> cur[k] ?: return JsonNull
					is JsonArray -> k.toIntOrNull()?.let { cur.getOrNull(it) } ?: return JsonNull
					else -> return JsonNull
				}
			return cur
		}
	}
}
