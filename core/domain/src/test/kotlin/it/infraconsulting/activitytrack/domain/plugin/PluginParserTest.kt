/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class PluginParserTest {
	/* the published descriptor, as shipped in plugins/ (the test runs in core/domain) */
	private val antares = File("../../plugins/antares.json").readText()

	private fun minimal(extra: String = "") = """{
		"format": "activitytrack-plugin", "schema": 1, "id": "demo", "name": "Demo",
		"options": [ { "key": "url", "type": "url", "default": "https://example.org" } ],
		"http": { "baseUrl": "@option.url" },
		"import": { "clients": { "request": { "path": "/customers" }, "key": "$.Id", "map": { "name": "$.Name" } } }
		$extra
	}"""

	private fun errors(text: String) = assertIs<PluginResult.Invalid>(PluginParser.parse(text)).errors

	@Test
	fun antaresDescriptorIsValid() {
		val r = PluginParser.parse(antares, "antares")
		val p = assertIs<PluginResult.Valid>(r, (r as? PluginResult.Invalid)?.errors?.joinToString("\n").orEmpty()).plugin
		assertEquals("antares", p.id)
		assertEquals(setOf("portfolio", "activityType"), p.lists.keys)
		assertEquals("portfolio", p.lists.getValue("activityType").parent)
		assertEquals(listOf("portfolio", "activityType", "included"), p.entryFields.map { it.key })
		assertEquals(GroupBy.DAY_BLOCK, p.export!!.group.by)
		assertTrue(p.export.update!!.fetchFirst)
		assertIs<Auth.OAuth2Pkce>(p.auth)
		assertIs<Paging.OData>(p.http.paging)
	}

	@Test
	fun minimalIsValidAndTheIdMustMatchTheFile() {
		assertIs<PluginResult.Valid>(PluginParser.parse(minimal()))
		assertEquals("id", assertIs<PluginResult.Invalid>(PluginParser.parse(minimal(), "other")).errors.single().path)
	}

	@Test
	fun referencesMustExistAndFitTheirSection() {
		val e = errors(
			minimal(
				""",
				"lookups": { "states": { "request": { "path": "/states" }, "select": "$[*].Id" } },
				"entryFields": [ { "key": "kind", "type": "string", "default": "@block.from" } ],
				"export": {
					"body": { "A": "@option.nope", "B": "@lookup.missing", "C": "@field.kind", "D": "$.Id", "E": "@identity.person" },
					"create": { "method": "POST", "path": "/x/${'$'}{remote.id}" }
				}
				""",
			),
		).map { it.path to it.message }
		val paths = e.map { it.first }.toSet()
		assertTrue("entryFields.0.default" in paths, e.toString())		/* @block outside the export */
		assertTrue("export.body.A" in paths)		/* no such option */
		assertTrue("export.body.B" in paths)		/* no such lookup */
		assertTrue("export.body.C" !in paths)		/* a declared field */
		assertTrue("export.body.D" in paths)		/* no remote record in a body */
		assertTrue("export.body.E" in paths)		/* no identity */
		assertTrue("export.create.path" in paths)	/* @remote only for update and delete */
		assertTrue("export.create.id" in paths)		/* required */
	}

	@Test
	fun badExpressionsAreReportedWithTheirPath() {
		val paths = errors(
			minimal(
				""",
				"lists": { "l": { "sources": [ { "request": { "path": "/l" }, "map": { "id": "$.Id", "name": { "frobnicate": "$.Name" }, "kind": "$..bad" } } ] } }
				""",
			),
		).map { it.path }.toSet()
		assertEquals(setOf("lists.l.sources.0.map.name", "lists.l.sources.0.map.kind"), paths)
	}

	@Test
	fun listParentsMustExistWithoutCycles() {
		val paths = errors(
			minimal(
				""",
				"lists": {
					"a": { "parent": "b", "sources": [ { "request": { "path": "/a/${'$'}{parent.id}" }, "map": { "id": "$.Id", "name": "$.N" } } ] },
					"b": { "parent": "a", "sources": [ { "request": { "path": "/b" }, "map": { "id": "$.Id", "name": "$.N" } } ] },
					"c": { "sources": [ { "request": { "path": "/c/${'$'}{parent.id}" }, "map": { "id": "$.Id", "name": "$.N" } } ] }
				}
				""",
			),
		).map { it.path }.toSet()
		assertTrue("lists.a.parent" in paths && "lists.b.parent" in paths, paths.toString())
		assertTrue("lists.c.sources.0.request.path" in paths)		/* @parent in a list without parent */
	}

	@Test
	fun optionsAreChecked() {
		val paths = errors(
			"""{ "format": "activitytrack-plugin", "schema": 1, "id": "demo", "name": "Demo",
				"options": [ { "key": "mode", "type": "choice", "default": "z", "choices": { "a": { "en": "A" } } },
				             { "key": "mode", "type": "secret", "default": "x" }, { "key": "u", "type": "url", "default": "http://x" } ],
				"http": { "baseUrl": "@option.u" },
				"import": { "clients": { "request": { "path": "/c" }, "key": "$.Id", "map": { "name": "$.Name" } } } }""",
		).map { it.path }
		assertEquals(listOf("options.0.default", "options.1.key", "options.1.default", "options.2.default"), paths)
	}

	/* the expressions on records and app values */
	private val env = object : EvalEnv {
		override val zone: ZoneId = ZoneId.of("Europe/Rome")
		val refs = mapOf("@todo" to JsonNull, "@field.portfolio" to JsonPrimitive("P1"), "@lookup.states" to Json.parseToJsonElement("[1, 2]"),
			"@block.from" to JsonPrimitive(1_792_400_400_000L), "@entry.activity" to JsonPrimitive("Install"))
		override fun ref(r: Expr.Ref): JsonElement = refs[r.toString()] ?: JsonNull
		override fun round(rule: String, minutes: Long) = (minutes + 14) / 15 * 15
		override fun materials(mode: String): JsonElement = JsonPrimitive("Cable 2 m")
	}

	private fun ev(expr: String, item: String = "{}"): JsonElement =
		Expr.parse(Json.parseToJsonElement(expr)) { p, m -> error("$p: $m") }!!.eval(Json.parseToJsonElement(item), env)

	@Test
	fun selectorsAndTransforms() {
		val claims = """[ { "type": "antares:person:id", "value": "42" }, { "type": "other", "value": "x" } ]"""
		assertEquals(JsonPrimitive("42"), ev("\"$[?(@.type == 'antares:person:id')].value\"", claims).let { (it as kotlinx.serialization.json.JsonArray).first() })
		assertEquals(JsonPrimitive("B"), ev("""{ "coalesce": ["$.Name", "$.Code"] }""", """{ "Name": "", "Code": "B" }"""))
		assertEquals(JsonPrimitive(true), ev("""{ "in": ["$.State", "@lookup.states"] }""", """{ "State": 2.0 }"""))
		assertEquals(JsonPrimitive(false), ev("""{ "notIn": ["$.State", "@lookup.states"] }""", """{ "State": 1 }"""))
		assertEquals(JsonPrimitive(5), ev("""{ "if": { "ne": ["@todo", null] }, "then": 2, "else": { "map": "$.kind", "cases": { "service": 1, "project": 5 } } }""", """{ "kind": "project" }"""))
		assertEquals(JsonPrimitive("2026-10-19T11:00:00+02:00"), ev("""{ "date": "@block.from", "format": "isoOffset" }"""))
		assertEquals(JsonPrimitive(45L), ev("""{ "minutes": 31, "round": "work" }"""))
		assertEquals(JsonPrimitive("Install\n\nCable 2 m"), ev("""{ "concat": ["@entry.activity", { "materials": "text" }], "separator": "\n\n" }"""))
		assertEquals(JsonPrimitive("/v3/x/P1"), ev("\"/v3/x/\${field.portfolio}\""))
		assertEquals(JsonPrimitive(12L), ev("""{ "number": "$.n" }""", """{ "n": "12" }"""))
		assertEquals(JsonNull, ev("\"$.missing.deeper\""))
		/* a ticket description of the Antares demo: tags out, entities decoded */
		assertEquals(
			JsonPrimitive("Xss --&gt;Attack\nprova prova\n\ndsjafkl l\u00f2jflska"),
			ev("""{ "plainText": "$.d" }""", """{ "d": "<div>Xss <img src=\"1\" onerror=\"alert(1)\"/> --&amp;gt;Attack</div><div>prova prova</div><div>&nbsp;</div><div>dsjafkl l&ograve;jflska</div>" }"""),
		)
	}

	@Test
	fun antaresActivityTypesPathUsesTheParent() {
		val p = (PluginParser.parse(antares) as PluginResult.Valid).plugin
		val path = p.lists.getValue("activityType").sources.single().request.path
		val parent = Json.parseToJsonElement("""{ "id": "S9", "extra": { "path": "/v3/ServicePortfolios" } }""").jsonObject
		val env = object : EvalEnv {
			override val zone: ZoneId = ZoneId.of("UTC")
			override fun ref(r: Expr.Ref): JsonElement =
				if (r.root == "parent") r.path.fold(parent as JsonElement) { e, k -> (e as? kotlinx.serialization.json.JsonObject)?.get(k) ?: JsonNull } else JsonNull
		}
		assertEquals(JsonPrimitive("/v3/ServicePortfolios/S9/ActivityTypes"), path.eval(null, env))
	}
}
