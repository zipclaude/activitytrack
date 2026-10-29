/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.sync

import it.infraconsulting.activitytrack.data.db.JdbcSql
import it.infraconsulting.activitytrack.data.db.SyncSchema
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Issue #114: the engine between two phones' databases and a fake server
 * holding records by revision, as ActivityTrack Web does (last write per
 * field, tombstones, central records); one user, so every record is visible.
 */
class SyncEngineTest {
	private class Record(var fields: MutableMap<String, JsonElement>, var deleted: Boolean, val central: Boolean, var rev: Long)

	private class FakeServer : SyncTransport {
		val records = LinkedHashMap<Pair<String, String>, Record>()
		var rev = 0L
		var revoked = false

		fun put(type: String, uid: String, fields: Map<String, JsonElement>, central: Boolean = false) {
			records[type to uid] = Record(fields.toMutableMap(), false, central, ++rev)
		}

		private fun wire(type: String, uid: String, r: Record) = JsonObject(
			buildMap {
				put("type", JsonPrimitive(type))
				put("uid", JsonPrimitive(uid))
				put("rev", JsonPrimitive(r.rev))
				put("deleted", JsonPrimitive(r.deleted))
				put("central", JsonPrimitive(r.central))
				if (!r.deleted) put("fields", JsonObject(r.fields))
			},
		)

		override suspend fun send(method: String, path: String, body: String?): SyncTransport.Reply {
			if (revoked) return SyncTransport.Reply(401, """{"error":"revoked"}""")
			val out: JsonObject = if (path == "sync/push") {
				val changes = Json.parseToJsonElement(body!!).jsonObject["changes"]!!.jsonArray.map { it.jsonObject }
				JsonObject(
					mapOf(
						"serverTime" to JsonPrimitive(0),
						"results" to JsonArray(
							changes.map { c ->
								val type = c["type"]!!.jsonPrimitive.content
								val uid = c["uid"]!!.jsonPrimitive.content
								val r = records.getOrPut(type to uid) { Record(mutableMapOf(), false, false, 0) }
								if (c["op"]!!.jsonPrimitive.content == "delete") r.deleted = true
								else c["fields"]!!.jsonObject.forEach { (k, v) -> r.fields[k] = v }
								r.rev = ++rev
								JsonObject(mapOf("uid" to JsonPrimitive(uid), "status" to JsonPrimitive("applied"), "record" to wire(type, uid, r)))
							},
						),
					),
				)
			} else {
				val cursor = path.substringAfter("cursor=").substringBefore('&').toLong()
				val newer = records.entries.filter { it.value.rev > cursor }.sortedBy { it.value.rev }
				JsonObject(
					mapOf(
						"serverTime" to JsonPrimitive(0),
						"cursor" to JsonPrimitive(rev),
						"more" to JsonPrimitive(false),
						"records" to JsonArray(newer.map { (k, r) -> wire(k.first, k.second, r) }),
						"gone" to JsonArray(emptyList()),
					),
				)
			}
			return SyncTransport.Reply(200, out.toString())
		}
	}

	private class Phone {
		val c: Connection = JdbcSql.create(16)
		val sql = JdbcSql(c).also { SyncSchema.onCreate(it) }
		val db = object : SyncDb {
			override suspend fun <T> run(block: (SyncStore) -> T): T {
				sql.exec("BEGIN")
				try {
					return block(SyncStore(sql)).also { sql.exec("COMMIT") }
				} catch (e: Throwable) {
					sql.exec("ROLLBACK")
					throw e
				}
			}
		}
		val records = SyncRecords(sql)

		fun one(q: String, vararg args: Any?) = sql.rows(q, *args).firstOrNull()?.get(0)
	}

	private val server = FakeServer()
	private val phones = mutableListOf<Phone>()

	private fun phone() = Phone().also { phones += it }

	@AfterTest
	fun close() = phones.forEach { it.c.close() }

	private fun sync(p: Phone) = runBlocking { SyncEngine(p.db, server).sync() }

	private fun company(p: Phone, name: String): String {
		val uid = SyncIds.random()
		p.sql.exec("INSERT INTO company (name, archived, uid) VALUES (?, 0, ?)", name, uid)
		return uid
	}

	private fun entry(p: Phone, companyUid: String, activity: String): String {
		val uid = SyncIds.random()
		val company = p.one("SELECT id FROM company WHERE uid = ?", companyUid)
		p.sql.exec(
			"INSERT INTO time_entry (uid, company_id, client_id, activity, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) " +
				"VALUES (?, ?, NULL, ?, 'CLOSED', 'Europe/Rome', 0, 1, 1)",
			uid, company, activity,
		)
		return uid
	}

	@Test
	fun firstSyncSendsEverythingAndTheSecondPhoneGetsIt() {
		val a = phone()
		val acme = company(a, "Acme")
		val e = entry(a, acme, "Patching")
		a.sql.exec("INSERT INTO entry_tag (entry_id, tag) VALUES ((SELECT id FROM time_entry WHERE uid = ?), 'rete')", e)
		val result = sync(a)
		/* the company and the entry (tags travel inside it) */
		assertEquals(2, result.pushed)
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(1L, a.one("SELECT enabled FROM sync_state"))
		val b = phone()
		sync(b)
		assertEquals(a.records.read("entry", e), b.records.read("entry", e))
		assertEquals(JsonArray(listOf(JsonPrimitive("rete"))), b.records.read("entry", e)!!["tags"])
	}

	@Test
	fun changesTravelBothWaysFieldByField() {
		val a = phone()
		val acme = company(a, "Acme")
		val e = entry(a, acme, "Patching")
		sync(a)
		val b = phone()
		sync(b)
		/* b edits the notes and syncs; a edits the activity without syncing yet */
		b.sql.exec("UPDATE time_entry SET notes = 'from b' WHERE uid = ?", e)
		sync(b)
		a.sql.exec("UPDATE time_entry SET activity = 'from a' WHERE uid = ?", e)
		sync(a)
		assertEquals("from b", a.one("SELECT notes FROM time_entry WHERE uid = ?", e))
		assertEquals("from a", a.one("SELECT activity FROM time_entry WHERE uid = ?", e))
		sync(b)
		assertEquals("from a", b.one("SELECT activity FROM time_entry WHERE uid = ?", e))
		assertEquals(JsonPrimitive("from a"), server.records["entry" to e]!!.fields["activity"])
	}

	@Test
	fun deletesTravelAndAnUnsentRecordNeverReachesTheServer() {
		val a = phone()
		val acme = company(a, "Acme")
		val e = entry(a, acme, "Patching")
		sync(a)
		val b = phone()
		sync(b)
		a.sql.exec("DELETE FROM time_entry WHERE uid = ?", e)
		val draft = entry(a, acme, "never sent")
		a.sql.exec("DELETE FROM time_entry WHERE uid = ?", draft)
		sync(a)
		assertTrue(server.records["entry" to e]!!.deleted)
		assertNull(server.records["entry" to draft])
		sync(b)
		assertNull(b.records.read("entry", e))
	}

	@Test
	fun aCentralRecordAdoptsThePersonalOneOfTheSameName() {
		val central = SyncIds.random()
		server.put("company", central, mapOf("name" to JsonPrimitive("Acme"), "archived" to JsonPrimitive(false), "color" to JsonNull, "externalCode" to JsonNull, "position" to JsonNull), central = true)
		val a = phone()
		val mine = company(a, "Acme")
		val e = entry(a, mine, "Patching")
		sync(a)
		assertEquals(central, a.one("SELECT uid FROM company WHERE name = 'Acme'"))
		assertEquals(1L, a.one("SELECT COUNT(*) FROM company"))
		assertEquals(JsonPrimitive(central), server.records["entry" to e]!!.fields["companyUid"])
		/* the personal company never reached the server (first sync): nothing to delete there */
		assertNull(server.records["company" to mine])
	}

	@Test
	fun adoptionAfterTheFirstSyncDeletesThePersonalRecordThere() {
		val a = phone()
		val mine = company(a, "Acme")
		val e = entry(a, mine, "Patching")
		sync(a)
		val central = SyncIds.random()
		server.put("company", central, mapOf("name" to JsonPrimitive("Acme"), "archived" to JsonPrimitive(false), "color" to JsonNull, "externalCode" to JsonNull, "position" to JsonNull), central = true)
		val r = sync(a)
		assertEquals(emptyList(), r.errors)
		assertEquals(central, a.one("SELECT uid FROM company"))
		assertEquals(JsonPrimitive(central), server.records["entry" to e]!!.fields["companyUid"])
		assertTrue(server.records["company" to mine]!!.deleted)
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_change"))
	}

	@Test
	fun revokedStopsTheSync() {
		val a = phone()
		company(a, "Acme")
		sync(a)
		server.revoked = true
		assertFailsWith<SyncRevoked> { sync(a) }
		assertEquals("revoked", a.one("SELECT last_error FROM sync_state"))
		/* the cursor stays where the last good sync left it */
		assertEquals(server.rev, a.one("SELECT cursor FROM sync_state") as Long)
	}
}
