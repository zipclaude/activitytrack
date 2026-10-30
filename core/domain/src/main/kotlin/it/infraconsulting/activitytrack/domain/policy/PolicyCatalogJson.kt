/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The policy catalog as JSON for ActivityTrack Web's editor (issue #117):
 * every key the parser accepts, its kind and allowed values, and the
 * description of docs/POLICY.md. docs/policy-catalog.json is this output;
 * PolicyCatalogJsonTest keeps it current, the server builds its form from it.
 */
object PolicyCatalogJson {
	const val SCHEMA = 1

	/* [doc]: the text of POLICY.md; its table rows give the descriptions */
	fun render(doc: String): String {
		val rows = rows(doc)
		fun describe(section: String, key: String): JsonElement = rows[section]?.get(key)?.let(::JsonPrimitive) ?: JsonPrimitive("")
		val b = Behaviours()
		val behaviours = listOf(
			Triple("askTravelDone", "BOOLEAN", JsonPrimitive(b.askTravelDone)),
			Triple("closeCompleteOnStop", "BOOLEAN", JsonPrimitive(b.closeCompleteOnStop)),
			Triple("minDurationSec", "INT", JsonPrimitive(b.minDurationSec)),
			Triple("exportMaxDays", "INT", JsonPrimitive(b.exportMaxDays)),
		)
		val root = JsonObject(
			linkedMapOf(
				"format" to JsonPrimitive("activitytrack-policy-catalog"),
				"schema" to JsonPrimitive(SCHEMA),
				"policySchema" to JsonPrimitive(1),
				"features" to JsonArray(
					Feature.entries.map {
						JsonObject(linkedMapOf("key" to JsonPrimitive(it.key), "essential" to JsonPrimitive(it.essential), "doc" to describe("features", it.key)))
					},
				),
				"fields" to JsonArray(
					Field.entries.map {
						JsonObject(linkedMapOf("key" to JsonPrimitive(it.key), "essential" to JsonPrimitive(it.essential), "doc" to describe("fields", it.key)))
					},
				),
				"settings" to JsonArray(
					SettingsCatalog.all.map { s ->
						JsonObject(
							buildMap {
								put("key", JsonPrimitive(s.key))
								put("kind", JsonPrimitive(s.kind.name))
								s.min?.let { put("min", JsonPrimitive(it)) }
								s.max?.let { put("max", JsonPrimitive(it)) }
								s.allowed?.let { a -> put("allowed", JsonArray(a.map { v -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) })) }
								if (s.multiple) put("multiple", JsonPrimitive(true))
								put("doc", describe("settings", s.key))
							},
						)
					},
				),
				"behaviours" to JsonArray(
					behaviours.map { (key, kind, default) ->
						JsonObject(linkedMapOf("key" to JsonPrimitive(key), "kind" to JsonPrimitive(kind), "default" to default, "doc" to describe("behaviours", key)))
					},
				),
				"entriesSource" to JsonArray(EntrySource.entries.map { JsonPrimitive(it.key) }),
				"masterDataSource" to JsonArray(MasterDataSource.entries.map { JsonPrimitive(it.key) }),
				"exportFormats" to JsonArray(PolicyParser.EXPORT_FORMATS.sorted().map(::JsonPrimitive)),
				"languages" to JsonArray(PolicyParser.UI_LANGUAGES.sorted().map(::JsonPrimitive)),
			),
		)
		return pretty.encodeToString(JsonElement.serializer(), root) + "\n"
	}

	@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
	private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

	/* "| `key` | a | b |" rows of each "## n. Section" of POLICY.md: section -> key -> the other cells */
	private fun rows(doc: String): Map<String, Map<String, String>> {
		val out = HashMap<String, HashMap<String, String>>()
		var section = ""
		for (line in doc.lines()) {
			Regex("""^## \d+\. (\w+)""").find(line)?.let { section = it.groupValues[1].lowercase() }
			val m = Regex("""^\| `([A-Za-z]+)` \|(.*)\|\s*$""").find(line) ?: continue
			val cells = m.groupValues[2].split('|').map(String::trim).filter(String::isNotEmpty)
			out.getOrPut(section) { HashMap() }[m.groupValues[1]] = cells.joinToString(" — ")
		}
		return out
	}
}
