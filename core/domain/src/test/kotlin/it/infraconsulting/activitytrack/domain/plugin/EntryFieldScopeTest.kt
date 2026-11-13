/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* The entry fields of a plugin for one activity: which required one is empty (issue #178, ACTIVITY-MODEL.md 2.4). */
class EntryFieldScopeTest {
	private fun plugin(fields: String) = assertIs<PluginResult.Valid>(
		PluginParser.parse(
			"""{
				"format": "activitytrack-plugin", "schema": 1, "id": "demo", "name": "Demo",
				"options": [ { "key": "url", "type": "url", "default": "https://example.org" } ],
				"http": { "baseUrl": "@option.url" },
				"import": { "clients": { "request": { "path": "/customers" }, "key": "$.Id", "map": { "name": "$.Name" } } },
				"entryFields": $fields
			}""",
		).also { (it as? PluginResult.Invalid)?.let { r -> error(r.errors.toString()) } },
	).plugin

	private fun scope(p: Plugin, own: Map<String, JsonElement> = emptyMap(), activity: JsonElement = JsonNull) =
		EntryFieldScope(p, own, emptyList(), PluginEnv(p, emptyMap(), { null }, ZoneId.of("UTC"))) { r ->
			if (r.root == "activity") (if (r.path.isEmpty()) activity else PluginEnv.dig(activity, r.path)) else null
		}

	private val constant = plugin("""[ { "key": "code", "type": "string", "required": true } ]""")

	@Test
	fun aConstantRequiredFieldMustBeFilled() {
		assertEquals("code", scope(constant).missingRequired()?.key)
		assertNull(scope(constant, mapOf("code" to JsonPrimitive("X1"))).missingRequired())
	}

	@Test
	fun blankNullAndMissingAreEmpty() {
		assertEquals("code", scope(constant, mapOf("code" to JsonPrimitive("  "))).missingRequired()?.key)
		assertEquals("code", scope(constant, mapOf("code" to JsonPrimitive(""))).missingRequired()?.key)
		assertEquals("code", scope(constant, mapOf("code" to JsonNull)).missingRequired()?.key)
		assertNull(scope(constant, mapOf("code" to JsonPrimitive(0))).missingRequired())
	}

	/* as the Antares portfolio: required only for an activity not imported from the plugin */
	@Test
	fun aFieldRequiredByAnExpression() {
		val p = plugin("""[ { "key": "portfolio", "type": "string", "required": { "eq": ["@activity", null] } } ]""")
		assertEquals("portfolio", scope(p).missingRequired()?.key)
		assertNull(scope(p, activity = JsonObject(mapOf("externalKey" to JsonPrimitive("T-1")))).missingRequired())
	}

	@Test
	fun aDefaultFillsAField() {
		val p = plugin("""[ { "key": "kind", "type": "string", "required": true, "default": "standard" } ]""")
		assertNull(scope(p).missingRequired())
		assertEquals(JsonPrimitive("standard"), scope(p).fields["kind"])
	}

	/* the Home checks a long list: @activity is loaded only where the check can read it */
	@Test
	fun whetherTheRequiredCheckReadsARoot() {
		/* as ServiceDesk Plus: @activity only in hidden, which the check does not evaluate */
		val sdp = plugin("""[ { "key": "worklogType", "type": "string", "required": true, "hidden": { "eq": ["@activity", null] } } ]""")
		assertEquals(false, EntryFieldScope.requiredReads(sdp, "activity"))
		assertEquals(scope(sdp).missingRequired(), scope(sdp, activity = JsonObject(mapOf("externalKey" to JsonPrimitive("T-1")))).missingRequired())
		assertEquals(true, EntryFieldScope.requiredReads(plugin("""[ { "key": "p", "type": "string", "required": { "eq": ["@activity", null] } } ]"""), "activity"))
		assertEquals(true, EntryFieldScope.requiredReads(plugin("""[ { "key": "p", "type": "string", "default": "@activity.externalKey" } ]"""), "activity"))
		/* the legacy alias @todo is @activity */
		assertEquals(true, EntryFieldScope.requiredReads(plugin("""[ { "key": "p", "type": "string", "default": "${'$'}{todo.title}" } ]"""), "activity"))
		assertEquals(false, EntryFieldScope.requiredReads(plugin("""[ { "key": "p", "type": "string", "required": true } ]"""), "activity"))
	}

	@Test
	fun nothingRequiredNothingMissing() {
		assertNull(scope(plugin("""[ { "key": "note", "type": "string" } ]""")).missingRequired())
		assertNull(scope(plugin("[]")).missingRequired())
	}
}
