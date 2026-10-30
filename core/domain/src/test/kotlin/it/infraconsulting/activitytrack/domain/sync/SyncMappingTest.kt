/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/*
 * The app's mapping against the server's wire schema (docs/samples/sync-schema.json,
 * written by `manage.py sync_schema` of ActivityTrack Web): when either side
 * changes a field, this fails until both agree.
 */
class SyncMappingTest {
	private val server = Json.parseToJsonElement(File("../../docs/samples/sync-schema.json").readText()).jsonObject["types"]!!.jsonArray
		.map { it.jsonObject }.associateBy { it["type"]!!.jsonPrimitive.content }

	private fun kind(k: WireKind) = k.name.lowercase().let { if (it == "instant") "instant" else it }

	@Test
	fun sameTypes() {
		assertEquals(server.keys, SyncMapping.byType.keys)
	}

	@Test
	fun sameFieldsKindsReferencesAndNulls() {
		for (t in SyncMapping.TYPES) {
			val theirs = server.getValue(t.type)["fields"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
			assertEquals(theirs.keys, t.fields.map { it.name }.toSet() + t.lists.map { it.name } + t.notYet.keys, t.type)
			for (f in t.fields) {
				val s = theirs.getValue(f.name)
				assertEquals(s["kind"]!!.jsonPrimitive.content, kind(f.kind), "${t.type}.${f.name} kind")
				assertEquals(s["null"]!!.jsonPrimitive.boolean, f.nullable, "${t.type}.${f.name} nullable")
				assertEquals(s["ref"]?.jsonPrimitive?.content, f.ref, "${t.type}.${f.name} ref")
				assertEquals(s["readOnly"]?.jsonPrimitive?.boolean ?: false, f.readOnly, "${t.type}.${f.name} read-only")
			}
			for (l in t.lists) assertEquals("json", theirs.getValue(l.name)["kind"]!!.jsonPrimitive.content, "${t.type}.${l.name}")
			val parent = server.getValue(t.type)["parent"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
			assertEquals(parent?.let { p -> p.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() } + "Uid" }, t.parent, "${t.type} parent")
		}
	}

	@Test
	fun valuesConvertBothWays() {
		val todo = SyncMapping.byType.getValue("todo")
		fun round(name: String, db: Any?) = SyncMapping.fromWire(todo.byName.getValue(name), SyncMapping.toWire(todo.byName.getValue(name), db))
		assertEquals(JsonPrimitive("2024-10-04"), SyncMapping.toWire(todo.byName.getValue("dueDate"), 20000L))
		assertEquals(20000L, round("dueDate", 20000L))
		assertEquals(JsonPrimitive(true), SyncMapping.toWire(todo.byName.getValue("done"), 1L))
		assertEquals(0L, round("done", 0L))
		assertEquals(1761600000000L, round("createdUtcMs", 1761600000000L))
		assertEquals(JsonObject(mapOf("service" to JsonPrimitive("s1"))), SyncMapping.toWire(todo.byName.getValue("pluginFields"), """{"service":"s1"}"""))
		assertEquals("""{"service":"s1"}""", round("pluginFields", """{"service":"s1"}"""))
		assertEquals(null, round("notes", null))
		assertEquals(listOf("a", "b"), SyncMapping.listFromWire("tags", SyncMapping.list(listOf("b", "a"))))
	}

	@Test
	fun badValuesAreRefused() {
		val todo = SyncMapping.byType.getValue("todo")
		assertFailsWith<WireException> { SyncMapping.fromWire(todo.byName.getValue("title"), JsonNull) }
		assertFailsWith<WireException> { SyncMapping.fromWire(todo.byName.getValue("title"), JsonPrimitive(3)) }
		assertFailsWith<WireException> { SyncMapping.fromWire(todo.byName.getValue("priority"), JsonPrimitive("3")) }
		assertFailsWith<WireException> { SyncMapping.fromWire(todo.byName.getValue("done"), JsonPrimitive(1)) }
		assertFailsWith<WireException> { SyncMapping.fromWire(todo.byName.getValue("dueDate"), JsonPrimitive("04/10/2024")) }
		assertFailsWith<WireException> { SyncMapping.listFromWire("tags", JsonPrimitive("a")) }
	}
}
