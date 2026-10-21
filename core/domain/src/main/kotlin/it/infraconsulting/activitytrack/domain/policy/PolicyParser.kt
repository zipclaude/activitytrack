/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/* A problem in policy.json: [path] like "fields.client.visible", [line] 1-based when known. */
data class PolicyError(val path: String, val line: Int?, val message: String) {
	override fun toString() = (if (line != null) "line $line, " else "") + (if (path.isEmpty()) "" else "$path: ") + message
}

sealed interface PolicyResult {
	data class Valid(val policy: Policy) : PolicyResult
	data class Invalid(val errors: List<PolicyError>) : PolicyResult
}

/*
 * Reads and checks policy.json (issue #48): syntax, schema (unknown keys
 * are errors, so a typo never passes silently), values, essential fields
 * and features, coherence. Every error is collected, not only the first.
 */
object PolicyParser {
	const val FORMAT = "activitytrack-policy"
	const val SCHEMA = 1

	val EXPORT_FORMATS = setOf("csv", "json", "xml")
	val UI_LANGUAGES = setOf("it", "en", "de", "es")

	private val COLOR = Regex("#[0-9A-Fa-f]{6}")
	private val LANGUAGE = Regex("[a-z]{2,3}")
	private val NAME = Regex("[a-z][a-z0-9_-]{0,39}")
	private val PLUGIN_FILE = Regex("[A-Za-z0-9_-]{1,40}\\.json")
	private const val LABEL_MAX = 40

	/* SHA-256 of the file as read, hex: decides whether a policy.json is new. */
	fun fingerprint(bytes: ByteArray): String =
		MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

	fun parse(text: String): PolicyResult {
		val root = try {
			Json.parseToJsonElement(text)
		} catch (e: SerializationException) {
			val offset = Regex("offset (\\d+)").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
			return PolicyResult.Invalid(listOf(PolicyError("", offset?.let { lineAt(text, it) }, "syntax error: " + e.message.orEmpty().lineSequence().first())))
		}
		val r = Reader(text)
		val policy = r.policy(root)
		if (policy != null && r.errors.isEmpty())
			PolicyValidator.check(policy).forEach { (path, msg) -> r.error(path, msg) }
		return if (r.errors.isEmpty() && policy != null) PolicyResult.Valid(policy) else PolicyResult.Invalid(r.errors)
	}

	private fun lineAt(text: String, offset: Int): Int = 1 + text.subSequence(0, offset.coerceIn(0, text.length)).count { it == '\n' }

	/*
	 * The line of a path, found by looking for each key in turn after the
	 * previous one: good enough for error messages in a hand-written file.
	 */
	internal fun lineOf(text: String, path: String): Int? {
		var at = 0
		for (key in path.split('.').filter { it.isNotEmpty() && it.toIntOrNull() == null }) {
			val m = Regex("\"" + Regex.escape(key) + "\"\\s*:").find(text, at) ?: return null
			at = m.range.first
		}
		return if (path.isEmpty()) null else lineAt(text, at)
	}

	private class Reader(val text: String) {
		val errors = mutableListOf<PolicyError>()

		fun error(path: String, message: String) {
			errors += PolicyError(path, lineOf(text, path), message)
		}

		private fun obj(e: JsonElement?, path: String): JsonObject? = when (e) {
			null -> null
			is JsonObject -> e
			else -> null.also { error(path, "must be an object") }
		}

		private fun keys(o: JsonObject, path: String, allowed: Set<String>) {
			for (k in o.keys - allowed)
				error(join(path, k), "unknown key (allowed: ${allowed.sorted().joinToString()})")
		}

		private fun bool(e: JsonElement?, path: String): Boolean? = when {
			e == null -> null
			e is JsonPrimitive && !e.isString && e.booleanOrNull != null -> e.booleanOrNull
			else -> null.also { error(path, "must be true or false") }
		}

		private fun int(e: JsonElement?, path: String, range: IntRange): Int? {
			if (e == null)
				return null
			val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
			return when {
				v == null -> null.also { error(path, "must be an integer") }
				v !in range -> null.also { error(path, "must be in ${range.first}..${range.last}") }
				else -> v
			}
		}

		private fun string(e: JsonElement?, path: String): String? = when {
			e == null -> null
			e is JsonPrimitive && e.isString -> e.content
			else -> null.also { error(path, "must be a string") }
		}

		private fun <T> choice(e: JsonElement?, path: String, byKey: Map<String, T>): T? {
			val s = string(e, path) ?: return null
			return byKey[s] ?: null.also { error(path, "must be one of ${byKey.keys.joinToString()}") }
		}

		fun policy(root: JsonElement): Policy? {
			val o = obj(root, "") ?: return null.also { error("", "the policy must be a JSON object") }
			keys(o, "", setOf("format", "schema", "id", "version", "features", "fields", "settings", "behaviours", "integrations", "entries", "masterData", "export", "ui"))
			if (string(o["format"], "format") != FORMAT)
				error("format", "must be \"$FORMAT\"")
			val schema = int(o["schema"], "schema", 1..Int.MAX_VALUE)
			if (schema == null)
				error("schema", "required, $SCHEMA")
			else if (schema > SCHEMA)
				error("schema", "schema $schema is newer than this app ($SCHEMA): update the app")
			return Policy(
				id = string(o["id"], "id").orEmpty(),
				version = string(o["version"], "version").orEmpty(),
				features = features(o["features"]),
				fields = fields(o["fields"]),
				settings = settings(o["settings"]),
				behaviours = behaviours(o["behaviours"]),
				integrations = integrations(o["integrations"]),
				entrySource = obj(o["entries"], "entries")?.let { e ->
					keys(e, "entries", setOf("source"))
					choice(e["source"], "entries.source", EntrySource.byKey)
				} ?: EntrySource.FREE,
				masterData = obj(o["masterData"], "masterData")?.let { m ->
					keys(m, "masterData", setOf("source"))
					choice(m["source"], "masterData.source", MasterDataSource.byKey)
				} ?: MasterDataSource.USER,
				exportFormats = obj(o["export"], "export")?.let { x ->
					keys(x, "export", setOf("formats"))
					exportFormats(x["formats"])
				},
				ui = obj(o["ui"], "ui")?.let { u ->
					keys(u, "ui", setOf("language"))
					UiPolicy(string(u["language"], "ui.language")?.also {
						if (it !in UI_LANGUAGES)
							error("ui.language", "must be one of ${UI_LANGUAGES.joinToString()}")
					})
				} ?: UiPolicy(),
			)
		}

		private fun features(e: JsonElement?): Map<Feature, Boolean> {
			val o = obj(e, "features") ?: return emptyMap()
			keys(o, "features", Feature.byKey.keys)
			return o.mapNotNull { (k, v) ->
				val f = Feature.byKey[k] ?: return@mapNotNull null
				bool(v, "features.$k")?.let { f to it }
			}.toMap()
		}

		private fun fields(e: JsonElement?): Map<Field, FieldPolicy> {
			val o = obj(e, "fields") ?: return emptyMap()
			keys(o, "fields", Field.byKey.keys)
			return o.mapNotNull { (k, v) ->
				val f = Field.byKey[k] ?: return@mapNotNull null
				val p = "fields.$k"
				val fo = obj(v, p) ?: return@mapNotNull null
				keys(fo, p, setOf("enabled", "visible", "required", "label", "colors"))
				val colors = obj(fo["colors"], "$p.colors")?.also { keys(it, "$p.colors", setOf("invalid", "valid")) }
				f to FieldPolicy(
					enabled = bool(fo["enabled"], "$p.enabled") ?: true,
					visible = bool(fo["visible"], "$p.visible") ?: true,
					required = bool(fo["required"], "$p.required"),
					label = labels(fo["label"], "$p.label"),
					invalidColor = colors?.let { color(it["invalid"], "$p.colors.invalid") },
					validColor = colors?.let { color(it["valid"], "$p.colors.valid") },
				)
			}.toMap()
		}

		private fun labels(e: JsonElement?, path: String): Map<String, String> {
			val o = obj(e, path) ?: return emptyMap()
			return o.mapNotNull { (lang, v) ->
				val p = join(path, lang)
				val s = string(v, p) ?: return@mapNotNull null
				when {
					!LANGUAGE.matches(lang) -> null.also { error(p, "a language code like \"it\" or \"en\"") }
					s.isBlank() || s.length > LABEL_MAX -> null.also { error(p, "1 to $LABEL_MAX characters") }
					else -> lang to s.trim()
				}
			}.toMap()
		}

		private fun color(e: JsonElement?, path: String): Long? {
			val s = string(e, path) ?: return null
			return if (COLOR.matches(s)) 0xFF000000L or s.substring(1).toLong(16) else null.also { error(path, "a colour \"#RRGGBB\"") }
		}

		private fun settings(e: JsonElement?): Map<String, SettingPolicy> {
			val o = obj(e, "settings") ?: return emptyMap()
			keys(o, "settings", SettingsCatalog.byKey.keys)
			return o.mapNotNull { (k, v) ->
				val spec = SettingsCatalog.byKey[k] ?: return@mapNotNull null
				val p = "settings.$k"
				/* a bare value is shorthand for { "value": v } */
				val so = v as? JsonObject ?: JsonObject(mapOf("value" to v))
				keys(so, p, setOf("value", "locked", "hidden"))
				val value = so["value"]?.takeIf { it !is JsonNull }?.let { settingValue(spec, it, "$p.value") }
				if (so["value"] != null && so["value"] !is JsonNull && value == null)
					return@mapNotNull null
				k to SettingPolicy(value, bool(so["locked"], "$p.locked") ?: false, bool(so["hidden"], "$p.hidden") ?: false)
			}.toMap()
		}

		private fun settingValue(spec: SettingSpec, e: JsonElement, path: String): Any? {
			val v: Any = when (spec.kind) {
				SettingSpec.Kind.BOOLEAN -> bool(e, path)
				SettingSpec.Kind.INT -> int(e, path, Int.MIN_VALUE..Int.MAX_VALUE)
				SettingSpec.Kind.STRING -> string(e, path)
			} ?: return null
			return spec.check(v)?.let { msg -> null.also { error(path, msg) } } ?: v
		}

		private fun behaviours(e: JsonElement?): Behaviours {
			val o = obj(e, "behaviours") ?: return Behaviours()
			keys(o, "behaviours", setOf("askTravelDone", "closeCompleteOnStop", "minDurationSec", "exportMaxDays"))
			val d = Behaviours()
			return Behaviours(
				askTravelDone = bool(o["askTravelDone"], "behaviours.askTravelDone") ?: d.askTravelDone,
				closeCompleteOnStop = bool(o["closeCompleteOnStop"], "behaviours.closeCompleteOnStop") ?: d.closeCompleteOnStop,
				minDurationSec = int(o["minDurationSec"], "behaviours.minDurationSec", 0..3600) ?: d.minDurationSec,
				exportMaxDays = int(o["exportMaxDays"], "behaviours.exportMaxDays", 1..3660) ?: d.exportMaxDays,
			)
		}

		private fun integrations(e: JsonElement?): Map<String, IntegrationPolicy> {
			val o = obj(e, "integrations") ?: return emptyMap()
			return o.mapNotNull { (name, v) ->
				val p = "integrations.$name"
				if (!NAME.matches(name))
					return@mapNotNull null.also { error(p, "a name of lower-case letters, digits, '-' and '_'") }
				val io = obj(v, p) ?: return@mapNotNull null
				keys(io, p, setOf("enabled", "plugin"))
				val plugin = string(io["plugin"], "$p.plugin")
				when {
					plugin == null -> null.also { error("$p.plugin", "required, a file in plugins/ like \"$name.json\"") }
					!PLUGIN_FILE.matches(plugin) -> null.also { error("$p.plugin", "a file name like \"$name.json\", no folders") }
					else -> name to IntegrationPolicy(bool(io["enabled"], "$p.enabled") ?: true, plugin)
				}
			}.toMap()
		}

		private fun exportFormats(e: JsonElement?): Set<String>? {
			if (e == null)
				return null
			val a = e as? JsonArray ?: return null.also { error("export.formats", "must be a list") }
			return a.mapIndexedNotNull { i, v ->
				val s = string(v, "export.formats") ?: return@mapIndexedNotNull null
				if (s in EXPORT_FORMATS) s else null.also { error("export.formats", "item ${i + 1}: must be one of ${EXPORT_FORMATS.joinToString()}") }
			}.toSet()
		}

		private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"
	}
}

/*
 * Rules across the policy: essential fields and features (issue #48),
 * fields that could never be filled, sources that would leave the user
 * unable to start anything. Returns (path, message) pairs.
 */
object PolicyValidator {
	fun check(p: Policy): List<Pair<String, String>> = buildList {
		for (f in Feature.entries)
			if (f.essential && p.features[f] == false)
				add("features.${f.key}" to "an essential feature cannot be disabled")

		/*
		 * The client may be switched off only while the company stays on:
		 * then the company identifies the work and becomes essential itself.
		 */
		val clientOn = p.field(Field.CLIENT).let { it.visible && it.enabled }
		val companyOn = p.field(Field.COMPANY).let { it.visible && it.enabled }
		for (f in Field.entries) {
			val fp = p.fields[f] ?: continue
			val path = "fields.${f.key}"
			val on = fp.visible && fp.enabled
			when {
				f == Field.CLIENT && !on && !companyOn ->
					add(path to "the client can be switched off only while the company stays visible and enabled")
				f.essential && f != Field.CLIENT && !on ->
					add(path to "an essential field cannot be hidden or disabled")
			}
			val essential = (f.essential && f != Field.CLIENT) || (f == Field.CLIENT && clientOn) || (f == Field.COMPANY && !clientOn)
			if (essential && fp.required == false)
				add("$path.required" to "an essential field stays required")
			if (fp.required == true && !on)
				add("$path.required" to "a hidden or disabled field cannot be required")
		}

		if (p.entrySource == EntrySource.TODOS_ONLY && !p.enabled(Feature.TODOS))
			add("entries.source" to "\"todosOnly\" needs the feature \"todos\"")
		if (!p.enabled(Feature.PLANNING) && p.fields[Field.TODO_PLAN]?.required == true)
			add("fields.todoPlan.required" to "planning is disabled")
		if (p.masterData == MasterDataSource.IMPORT_ONLY && !p.enabled(Feature.IMPORT) && p.integrations.values.none { it.enabled })
			add("masterData.source" to "\"importOnly\" needs the feature \"import\" or an enabled integration")
		if (p.exportFormats?.isEmpty() == true && p.enabled(Feature.EXPORT))
			add("export.formats" to "empty: disable the feature \"export\" instead")
	}
}
