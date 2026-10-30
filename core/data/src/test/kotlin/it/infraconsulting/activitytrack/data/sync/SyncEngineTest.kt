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
		/* uids whose changes are refused, as ActivityTrack Web does for a forbidden one */
		val refuse = HashSet<String>()
		val files = HashMap<String, ByteArray>()

		override suspend fun upload(path: String, bytes: ByteArray, mime: String, sha256: String): SyncTransport.Reply {
			val uid = path.substringAfter("files/")
			val r = records.getValue("attachment" to uid)
			files[uid] = bytes
			r.fields["hasFile"] = JsonPrimitive(true)
			r.fields["sha256"] = JsonPrimitive(sha256)
			r.fields["size"] = JsonPrimitive(bytes.size)
			r.rev = ++rev
			return SyncTransport.Reply(200, JsonObject(mapOf("record" to wire("attachment", uid, r))).toString())
		}

		override suspend fun download(path: String): Pair<Int, ByteArray?> =
			files[path.substringAfter("files/")]?.let { 200 to it } ?: (404 to null)

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
			if (path == "sync/head") return SyncTransport.Reply(200, """{"rev":$rev,"serverTime":0,"filesMaxMb":1}""")
			val out: JsonObject = if (path == "sync/push") {
				val changes = Json.parseToJsonElement(body!!).jsonObject["changes"]!!.jsonArray.map { it.jsonObject }
				JsonObject(
					mapOf(
						"serverTime" to JsonPrimitive(0),
						"results" to JsonArray(
							changes.map { c ->
								val type = c["type"]!!.jsonPrimitive.content
								val uid = c["uid"]!!.jsonPrimitive.content
								if (uid in refuse)
									return@map JsonObject(mapOf("uid" to JsonPrimitive(uid), "status" to JsonPrimitive("error"), "error" to JsonPrimitive("forbidden")))
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

	private class Phone(val canEditCentral: Boolean = true) {
		val c: Connection = JdbcSql.create(19)
		val sql = JdbcSql(c).also { SyncSchema.onCreate(it) }
		val db = object : SyncDb {
			override suspend fun <T> run(block: (SyncStore) -> T): T {
				sql.exec("BEGIN")
				try {
					return block(SyncStore(sql, canEditCentral = canEditCentral)).also { sql.exec("COMMIT") }
				} catch (e: Throwable) {
					sql.exec("ROLLBACK")
					throw e
				}
			}
		}
		val records = SyncRecords(sql)
		val stored = HashMap<String, ByteArray>()
		val files = object : SyncFiles {
			override suspend fun read(relPath: String) = stored[relPath]

			override suspend fun write(entryId: Long, uid: String, mime: String, bytes: ByteArray): String =
				"attachments/2026/$entryId/$uid".also { stored[it] = bytes }
		}

		fun one(q: String, vararg args: Any?) = sql.rows(q, *args).firstOrNull()?.get(0)
	}

	private val server = FakeServer()
	private val phones = mutableListOf<Phone>()

	private fun phone(canEditCentral: Boolean = true) = Phone(canEditCentral).also { phones += it }

	@AfterTest
	fun close() = phones.forEach { it.c.close() }

	private fun sync(p: Phone, downloads: Boolean = false) = runBlocking { SyncEngine(p.db, server, files = p.files, downloads = downloads).sync() }

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
	fun attachmentFilesTravelAfterTheirRecords() {
		val a = phone()
		val acme = company(a, "Acme")
		val e = entry(a, acme, "Patching")
		val att = SyncIds.random()
		val big = SyncIds.random()
		a.stored["attachments/2026/1/receipt.jpg"] = "receipt".toByteArray()
		a.stored["attachments/2026/1/video.mp4"] = ByteArray(2 * 1024 * 1024)
		for ((uid, path) in listOf(att to "attachments/2026/1/receipt.jpg", big to "attachments/2026/1/video.mp4"))
			a.sql.exec("INSERT INTO attachment (entry_id, kind, rel_path, mime, created_utc_ms, uid) VALUES (1, 'RECEIPT', ?, 'image/jpeg', 1, ?)", path, uid)
		val r = sync(a)
		assertEquals("receipt", String(server.files.getValue(att)))
		assertEquals(JsonPrimitive(true), server.records["attachment" to att]!!.fields["hasFile"])
		/* above the server's limit (1 MB here): not sent, reported */
		assertNull(server.files[big])
		assertTrue(r.errors.any { big in it && "larger" in it }, r.errors.toString())
		assertEquals(1L, a.one("SELECT has_file FROM attachment WHERE uid = ?", att))
		val b = phone()
		sync(b)
		assertEquals("", b.one("SELECT rel_path FROM attachment WHERE uid = ?", att))
		sync(b, downloads = true)
		val path = b.one("SELECT rel_path FROM attachment WHERE uid = ?", att) as String
		assertEquals("receipt", String(b.stored.getValue(path)))
		/* the file's path is the phone's own business: nothing to push */
		assertEquals(0L, b.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(e, b.one("SELECT t.uid FROM time_entry t JOIN attachment a ON a.entry_id = t.id WHERE a.uid = ?", att))
	}

	@Test
	fun centralRecordsAreMarkedAndOnlyManagersChangeThem() {
		val central = SyncIds.random()
		server.put("company", central, mapOf("name" to JsonPrimitive("Acme"), "archived" to JsonPrimitive(false), "color" to JsonNull, "externalCode" to JsonNull, "position" to JsonNull), central = true)
		val user = phone(canEditCentral = false)
		val mine = company(user, "Mia")
		sync(user)
		assertEquals(1L, user.one("SELECT central FROM company WHERE uid = ?", central))
		assertEquals(0L, user.one("SELECT central FROM company WHERE uid = ?", mine))
		/* a user's own order of the list stays on the phone */
		user.sql.exec("UPDATE company SET sort_position = 3 WHERE uid = ?", central)
		sync(user)
		assertEquals(JsonNull, server.records["company" to central]!!.fields["position"])
		assertEquals(0L, user.one("SELECT COUNT(*) FROM sync_change"))
		val manager = phone(canEditCentral = true)
		sync(manager)
		manager.sql.exec("UPDATE company SET name = 'Acme S.p.A.' WHERE uid = ?", central)
		sync(manager)
		assertEquals(JsonPrimitive("Acme S.p.A."), server.records["company" to central]!!.fields["name"])
		sync(user)
		assertEquals("Acme S.p.A.", user.one("SELECT name FROM company WHERE uid = ?", central))
	}

	@Test
	fun overlappingSegmentsAreMarkedToReviewEverywhere() {
		val a = phone()
		val acme = company(a, "Acme")
		val e = entry(a, acme, "Patching")
		val seg = "INSERT INTO segment (entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source, uid) VALUES (1, 'WORK', ?, ?, 'Europe/Rome', 'MANUAL', ?)"
		val late = SyncIds.random()
		a.sql.exec(seg, 0L, 3_600_000L, SyncIds.random())
		a.sql.exec(seg, 1_800_000L, 5_400_000L, late)
		sync(a)
		assertEquals(1L, a.one("SELECT needs_review FROM time_entry WHERE uid = ?", e))
		assertEquals(JsonPrimitive(true), server.records["entry" to e]!!.fields["needsReview"])
		a.sql.exec("UPDATE segment SET start_utc_ms = 3600000 WHERE uid = ?", late)
		sync(a)
		assertEquals(0L, a.one("SELECT needs_review FROM time_entry WHERE uid = ?", e))
		assertEquals(JsonPrimitive(false), server.records["entry" to e]!!.fields["needsReview"])
	}

	@Test
	fun aRefusedChangeIsListedAndCanBeDiscarded() {
		val a = phone()
		val acme = company(a, "Acme")
		sync(a)
		server.refuse += acme
		a.sql.exec("UPDATE company SET name = 'Rinominata' WHERE uid = ?", acme)
		/* reported once, not once per round */
		assertEquals(1, sync(a).errors.size)
		assertEquals(listOf(listOf<Any?>("company", acme, "forbidden")), a.sql.rows("SELECT type, uid, message FROM sync_error"))
		/* kept for the next try */
		assertEquals(1L, a.one("SELECT COUNT(*) FROM sync_change"))
		runBlocking { a.db.run { it.discard("company", acme) } }
		assertEquals("Acme", a.one("SELECT name FROM company WHERE uid = ?", acme))
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_error"))
		assertEquals(null, a.sql.rows("SELECT last_error FROM sync_state")[0][0])
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
