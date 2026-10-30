/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.sync

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.JdbcSql
import it.infraconsulting.activitytrack.data.db.SyncSchema
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import it.infraconsulting.activitytrack.domain.sync.SyncMapping
import it.infraconsulting.activitytrack.domain.sync.WireException
import it.infraconsulting.activitytrack.domain.sync.WireKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/* Issue #112: every record type written from wire fields and read back the same, on the schema Room makes. */
class SyncRecordsTest {
	private val c: Connection = JdbcSql.create(ActivityTrackDatabase.VERSION)
	private val sql = JdbcSql(c).also { SyncSchema.onCreate(it) }
	private val records = SyncRecords(sql)

	@AfterTest
	fun close() = c.close()

	/* a full record of each type; nullable fields null every other type, to cover both */
	private fun samples(): List<Triple<String, String, JsonObject>> {
		val uids = HashMap<String, String>()
		return SyncMapping.TYPES.mapIndexed { n, t ->
			val uid = if (t.type == "dayOff") SyncIds.dayOff(20000) else SyncIds.random()
			val fields = LinkedHashMap<String, JsonElement>()
			for (f in t.fields) {
				fields[f.name] = if (f.nullable && n % 2 == 1) JsonNull else when (f.kind) {
					WireKind.TEXT -> JsonPrimitive("${t.type}-${f.name}")
					WireKind.INT -> JsonPrimitive(7 + n)
					WireKind.FLOAT -> JsonPrimitive(45.5 + n)
					WireKind.BOOL -> JsonPrimitive(n % 3 == 0)
					WireKind.INSTANT -> JsonPrimitive(1761600000000L + n)
					WireKind.DATE -> JsonPrimitive("2026-10-${10 + n}")
					WireKind.JSON -> JsonObject(mapOf("k" to JsonPrimitive("v$n")))
					WireKind.REF -> uids[f.ref!!]?.let(::JsonPrimitive) ?: JsonNull
				}
			}
			for (l in t.lists) fields[l.name] = JsonArray(listOf(JsonPrimitive("a$n"), JsonPrimitive("b$n")))
			uids[t.type] = uid
			Triple(t.type, uid, JsonObject(fields))
		}
	}

	@Test
	fun everyTypeRoundTrips() {
		val all = samples()
		for ((type, uid, fields) in all) records.write(type, uid, fields)
		for ((type, uid, fields) in all) assertEquals(fields, records.read(type, uid), type)
	}

	@Test
	fun partialUpdatesKeepTheOtherFields() {
		val all = samples()
		for ((type, uid, fields) in all) records.write(type, uid, fields)
		val (_, entry, before) = all.first { it.first == "entry" }
		records.write("entry", entry, JsonObject(mapOf("activity" to JsonPrimitive("Rete"), "tags" to JsonArray(listOf(JsonPrimitive("z"))))))
		val after = records.read("entry", entry)!!
		assertEquals(JsonPrimitive("Rete"), after["activity"])
		assertEquals(JsonArray(listOf(JsonPrimitive("z"))), after["tags"])
		assertEquals(before["zoneId"], after["zoneId"])
	}

	@Test
	fun newRecordsNeedTheirRequiredFieldsAndKnownReferences() {
		assertFailsWith<WireException> { records.write("role", SyncIds.random(), JsonObject(mapOf("archived" to JsonPrimitive(false)))) }
		val e = assertFailsWith<SyncRecords.UnknownRef> {
			records.write(
				"segment", SyncIds.random(),
				JsonObject(
					mapOf(
						"entryUid" to JsonPrimitive(SyncIds.random()), "kind" to JsonPrimitive("WORK"), "startUtcMs" to JsonPrimitive(0),
						"zoneId" to JsonPrimitive("Europe/Rome"), "source" to JsonPrimitive("TRACKED"),
					),
				),
			)
		}
		assertEquals("entryUid", e.field)
		assertFailsWith<WireException> { records.write("spaceship", SyncIds.random(), JsonObject(emptyMap())) }
	}

	@Test
	fun unknownFieldsAreSkipped() {
		val uid = SyncIds.random()
		records.write("role", uid, JsonObject(mapOf("name" to JsonPrimitive("Dev"), "archived" to JsonPrimitive(false), "colour" to JsonPrimitive("red"))))
		assertEquals(JsonPrimitive("Dev"), records.read("role", uid)!!["name"])
	}

	@Test
	fun aDeletedEntryLeavesItsRemoteIdsForThePlugin() {
		val all = samples()
		for ((type, uid, fields) in all) records.write(type, uid, fields)
		val entry = all.first { it.first == "entry" }.second
		/* the ids are kept per company: the sample's may be null */
		sql.exec("UPDATE time_entry SET company_id = (SELECT MIN(id) FROM company) WHERE uid = ?", entry)
		sql.exec("INSERT INTO remote_record (entry_id, plugin_id, block_key, remote_id, hash, sent_utc_ms) VALUES ((SELECT id FROM time_entry WHERE uid = ?), 'sdp', '2026-10-29#0', 'W-77', 'h', 1)", entry)
		records.delete("entry", entry)
		assertEquals(listOf(listOf<Any?>("sdp", "W-77")), sql.rows("SELECT plugin_id, remote_id FROM remote_deleted"))
	}

	@Test
	fun deletingAnEntryTakesItsChildren() {
		val all = samples()
		for ((type, uid, fields) in all) records.write(type, uid, fields)
		val entry = all.first { it.first == "entry" }.second
		val segment = all.first { it.first == "segment" }.second
		records.delete("entry", entry)
		assertNull(records.read("entry", entry))
		assertNull(records.read("segment", segment))
	}
}
