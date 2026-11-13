/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.sync

import it.infraconsulting.activitytrack.data.db.ActivityModelMigration
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
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
		/* a server outside this app's schema window (SYNC.md §12) */
		var incompatible: String? = null
		/* uids whose changes are refused, as ActivityTrack Web does for a forbidden one */
		val refuse = HashSet<String>()
		val files = HashMap<String, ByteArray>()
		/* scope exits sent with every pull, as (type, uid) */
		val gone = ArrayList<Pair<String, String>>()

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
			incompatible?.let { return SyncTransport.Reply(426, """{"error":"$it","server":"2.0.0","schema":4,"minSchema":2}""") }
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
								/* as ActivityTrack Web: a new entry needs its required fields, and the external key is unique with the company */
								if (type == "entry" && c["op"]!!.jsonPrimitive.content != "delete") {
									val before = records[type to uid]?.takeIf { !it.deleted }
									val merged = (before?.fields ?: emptyMap()) + c["fields"]!!.jsonObject
									fun invalid(detail: String) = JsonObject(
										mapOf("uid" to JsonPrimitive(uid), "status" to JsonPrimitive("error"), "error" to JsonPrimitive("invalid"), "detail" to JsonPrimitive(detail)),
									)
									if (before == null && listOf("status", "zoneId", "createdUtcMs").any { merged[it] == null || merged[it] is JsonNull })
										return@map invalid("partial new entry")
									val key = merged["externalKey"]?.takeIf { it !is JsonNull }
									if (key != null && records.any { (k, r) -> k.first == "entry" && k.second != uid && !r.deleted && r.fields["externalKey"] == key && r.fields["companyUid"] == merged["companyUid"] })
										return@map invalid("duplicate")
								}
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
						"gone" to JsonArray(gone.map { (t, u) -> JsonObject(mapOf("type" to JsonPrimitive(t), "uid" to JsonPrimitive(u))) }),
					),
				)
			}
			return SyncTransport.Reply(200, out.toString())
		}
	}

	private class Phone(val canEditCentral: Boolean = true, version: Int = ActivityTrackDatabase.VERSION) {
		val c: Connection = JdbcSql.create(version)
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

	private fun phone(canEditCentral: Boolean = true, version: Int = ActivityTrackDatabase.VERSION) = Phone(canEditCentral, version).also { phones += it }

	@AfterTest
	fun close() = phones.forEach { it.c.close() }

	private fun sync(p: Phone, downloads: Boolean = false, authority: SyncAuthority = SyncAuthority.PHONE) =
		runBlocking { SyncEngine(p.db, server, files = p.files, downloads = downloads, authority = authority).sync() }

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

	/*
	 * After a migration of the data (ACTIVITY-MODEL.md 6, a restored older
	 * backup too) the next sync is a full realignment: the migration reset the
	 * cursor, the log and the shadows; the server's version wins for every
	 * record it has, what it lacks is pushed, and later changes go as usual.
	 */
	@Test
	fun afterAMigrationTheServerWinsAndOnlyWhatItLacksGoesUp() {
		val a = phone()
		val acme = company(a, "Acme")
		val e = entry(a, acme, "Patching")
		sync(a)
		/* the server migrated its copy (another description); the phone migrated its own */
		server.records.getValue("entry" to e).let { it.fields["activity"] = JsonPrimitive("server's"); it.rev = ++server.rev }
		a.sql.exec("UPDATE sync_state SET applying = 1")
		a.sql.exec("UPDATE time_entry SET activity = 'phone''s migration' WHERE uid = ?", e)
		val only = entry(a, acme, "only here")
		a.sql.exec("UPDATE sync_state SET applying = 0, realign = 1, cursor = 0")
		a.sql.exec("DELETE FROM sync_change")
		a.sql.exec("DELETE FROM sync_shadow")
		val before = server.rev
		sync(a)
		assertEquals("server's", a.one("SELECT activity FROM time_entry WHERE uid = ?", e))
		assertEquals(JsonPrimitive("only here"), server.records["entry" to only]!!.fields["activity"])
		/* the realigned record was not pushed back */
		assertEquals(JsonPrimitive("server's"), server.records["entry" to e]!!.fields["activity"])
		assertEquals(0L, a.one("SELECT realign FROM sync_state"))
		assertTrue(server.rev > before)
		/* a change made afterwards goes up as usual */
		a.sql.exec("UPDATE time_entry SET notes = 'later' WHERE uid = ?", e)
		sync(a)
		assertEquals(JsonPrimitive("later"), server.records["entry" to e]!!.fields["notes"])
	}

	/*
	 * No data loss on the realignment (coordinator, ACTIVITY-MODEL.md 6): a
	 * phone of schema 21 with an entry and a to-do changed and not synced is
	 * migrated; the server migrated its copy too. The realignment keeps and
	 * pushes the local version of those two (the to-do as its activity), the
	 * server's version wins for the clean entry.
	 */
	@Test
	fun theRealignmentKeepsTheChangesNotSynced() {
		val a = phone(version = 21)
		val db = a.sql
		db.exec("UPDATE sync_state SET enabled = 1")
		db.exec("INSERT INTO company (id, name, archived, uid) VALUES (1, 'Acme', 0, ?)", SyncIds.random())
		val entry = "INSERT INTO time_entry (id, uid, company_id, activity, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) VALUES (?, ?, 1, ?, 'CLOSED', 'Europe/Rome', 0, 1, 2)"
		val clean = SyncIds.random()
		val dirty = SyncIds.random()
		val todo = SyncIds.random()
		db.exec(entry, 1, clean, "clean")
		db.exec(entry, 2, dirty, "before")
		db.exec("INSERT INTO todo (id, uid, company_id, title, refs, done, created_utc_ms, updated_utc_ms, kind) VALUES (1, ?, 1, 'before', '', 0, 1, 2, 'TASK')", todo)
		/* synced, then changed here only */
		db.exec("DELETE FROM sync_change")
		db.exec("UPDATE time_entry SET activity = 'local' WHERE id = 2")
		db.exec("UPDATE todo SET title = 'local todo' WHERE id = 1")
		/* the to-do's trigger of a 0.9 app (this SyncSchema no longer has it) */
		db.exec("INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES ('todo', ?, 'upsert', 5)", todo)
		db.exec("PRAGMA foreign_keys = OFF")
		ActivityModelMigration.migrate21To22(db, java.time.ZoneId.of("Europe/Rome"))
		db.exec("PRAGMA foreign_keys = ON")
		/* the server's migrated copies, each with something else */
		for ((uid, text) in listOf(clean to "server clean", dirty to "server dirty", todo to "server todo")) {
			val r = a.records.read("entry", uid)!!.toMutableMap()
			r["activity"] = JsonPrimitive(text)
			if (uid == todo) r["status"] = JsonPrimitive("TODO")
			server.put("entry", uid, r)
		}
		server.put("company", a.one("SELECT uid FROM company") as String, a.records.read("company", a.one("SELECT uid FROM company") as String)!!)
		sync(a)
		assertEquals("server clean", a.one("SELECT activity FROM time_entry WHERE uid = ?", clean))
		assertEquals("local", a.one("SELECT activity FROM time_entry WHERE uid = ?", dirty))
		assertEquals("local todo", a.one("SELECT activity FROM time_entry WHERE uid = ?", todo))
		assertEquals(JsonPrimitive("local"), server.records.getValue("entry" to dirty).fields["activity"])
		assertEquals(JsonPrimitive("local todo"), server.records.getValue("entry" to todo).fields["activity"])
		assertEquals(JsonPrimitive("server clean"), server.records.getValue("entry" to clean).fields["activity"])
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(0L, a.one("SELECT realign FROM sync_state"))
	}

	/*
	 * The realignment pushes only the fields changed here: the dirty entry's
	 * wire-2 shadow, translated by the migration, is the base. A field edited
	 * elsewhere (the notes, on the server) is not overwritten by the stale
	 * local value; the one edited here (the description) goes up.
	 */
	@Test
	fun theRealignmentPushesOnlyTheFieldsChangedHere() {
		val a = phone(version = 21)
		val db = a.sql
		db.exec("UPDATE sync_state SET enabled = 1")
		val acme = SyncIds.random()
		db.exec("INSERT INTO company (id, name, archived, uid) VALUES (1, 'Acme', 0, ?)", acme)
		val e = SyncIds.random()
		db.exec(
			"INSERT INTO time_entry (id, uid, company_id, activity, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) VALUES (1, ?, 1, 'before', 'TO_COMPLETE', 'Europe/Rome', 0, 1, 2)",
			e,
		)
		/* what the server had at the last sync (wire 2) */
		db.exec(
			"INSERT INTO sync_shadow (type, uid, json) VALUES ('entry', ?, ?)",
			e, """{"todoUid":null,"companyUid":"$acme","activity":"before","notes":null,"status":"TO_COMPLETE"}""",
		)
		db.exec("DELETE FROM sync_change")
		db.exec("UPDATE time_entry SET activity = 'local' WHERE id = 1")
		db.exec("PRAGMA foreign_keys = OFF")
		ActivityModelMigration.migrate21To22(db, java.time.ZoneId.of("Europe/Rome"))
		db.exec("PRAGMA foreign_keys = ON")
		/* the server's migrated copy, its notes edited from another device since */
		val r = a.records.read("entry", e)!!.toMutableMap()
		r["activity"] = JsonPrimitive("before")
		r["notes"] = JsonPrimitive("server notes")
		server.put("entry", e, r)
		server.put("company", acme, a.records.read("company", acme)!!)
		sync(a)
		assertEquals(JsonPrimitive("local"), server.records.getValue("entry" to e).fields["activity"])
		assertEquals(JsonPrimitive("server notes"), server.records.getValue("entry" to e).fields["notes"])
		assertEquals("local", a.one("SELECT activity FROM time_entry WHERE uid = ?", e))
		assertEquals("server notes", a.one("SELECT notes FROM time_entry WHERE uid = ?", e))
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_change"))
	}

	/*
	 * No ghosts after the realignment: here the open to-do stayed its own
	 * TODO activity and its closed entry got a UUID reference to it; the
	 * server's migration (the to-do done on another phone) let the entry
	 * absorb it instead. What the phone's migration made and the server does
	 * not have goes, and is not pushed back as new; a to-do changed here and
	 * not synced is kept and pushed.
	 */
	@Test
	fun theRealignmentDropsWhatTheServersMigrationDidNotMake() {
		val a = phone(version = 21)
		val db = a.sql
		db.exec("UPDATE sync_state SET enabled = 1")
		val acme = SyncIds.random()
		db.exec("INSERT INTO company (id, name, archived, uid) VALUES (1, 'Acme', 0, ?)", acme)
		val todo = SyncIds.random()
		val mine = SyncIds.random()
		val e = SyncIds.random()
		db.exec("INSERT INTO todo (id, uid, company_id, title, external_key, refs, done, created_utc_ms, updated_utc_ms, kind) VALUES (1, ?, 1, 'Patch', 'K-1', '', 0, 1, 2, 'TASK')", todo)
		db.exec("INSERT INTO todo (id, uid, company_id, title, refs, done, created_utc_ms, updated_utc_ms, kind) VALUES (2, ?, 1, 'Mine', '', 0, 1, 2, 'TASK')", mine)
		db.exec(
			"INSERT INTO time_entry (id, uid, todo_id, company_id, activity, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) VALUES (1, ?, 1, 1, 'work', 'CLOSED', 'Europe/Rome', 0, 1, 2)",
			e,
		)
		/* synced; then only the second to-do changed here */
		db.exec("DELETE FROM sync_change")
		db.exec("INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES ('todo', ?, 'upsert', 5)", mine)
		db.exec("PRAGMA foreign_keys = OFF")
		ActivityModelMigration.migrate21To22(db, java.time.ZoneId.of("Europe/Rome"))
		db.exec("PRAGMA foreign_keys = ON")
		val link = a.one("SELECT uid FROM external_ref WHERE entry_id = 1") as String
		assertEquals(todo, a.one("SELECT value FROM external_ref WHERE uid = ?", link))
		/* the server's migration: the entry absorbed the to-do (its key), no activity of the to-do, no link */
		val r = a.records.read("entry", e)!!.toMutableMap()
		r["externalKey"] = JsonPrimitive("K-1")
		server.put("company", acme, a.records.read("company", acme)!!)
		server.put("entry", e, r)
		sync(a)
		assertNull(a.one("SELECT id FROM time_entry WHERE uid = ?", todo))
		assertNull(a.one("SELECT id FROM external_ref WHERE uid = ?", link))
		assertEquals("K-1", a.one("SELECT external_key FROM time_entry WHERE uid = ?", e))
		assertNull(server.records["entry" to todo])
		assertNull(server.records["externalRef" to link])
		/* the to-do changed here: kept and pushed */
		assertEquals(JsonPrimitive("Mine"), server.records.getValue("entry" to mine).fields["activity"])
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_realign"))
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_error"))
	}

	/*
	 * Issue #176: the two nodes migrated the same to-do differently. A phone
	 * of schema 21 with the to-do T (key K-1), synced, and an entry E started
	 * from it offline: its migration lets E absorb T. The server never saw E:
	 * T stayed its own activity there, [serverT] as it is (TODO, or ticked).
	 */
	private class Absorbed(val a: Phone, val acme: String, val todo: String, val e: String, val seg: String)

	private fun phoneAbsorbs(serverStatus: String = "TODO"): Absorbed {
		val a = phone(version = 21)
		val db = a.sql
		db.exec("UPDATE sync_state SET enabled = 1")
		val acme = SyncIds.random()
		val todo = SyncIds.random()
		val e = SyncIds.random()
		val seg = SyncIds.random()
		db.exec("INSERT INTO company (id, name, archived, uid) VALUES (1, 'Acme', 0, ?)", acme)
		db.exec("INSERT INTO todo (id, uid, company_id, title, external_key, refs, done, created_utc_ms, updated_utc_ms, kind) VALUES (1, ?, 1, 'Patch', 'K-1', '', 0, 1, 2, 'TASK')", todo)
		/* synced; then E started from T, offline */
		db.exec("DELETE FROM sync_change")
		db.exec(
			"INSERT INTO time_entry (id, uid, todo_id, company_id, activity, status, mode, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) " +
				"VALUES (1, ?, 1, 1, 'work', 'ACTIVE', 'WORK', 'Europe/Rome', 0, 10, 10)",
			e,
		)
		db.exec("INSERT INTO segment (entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source, uid) VALUES (1, 'WORK', 10, NULL, 'Europe/Rome', 'AUTO', ?)", seg)
		db.exec("PRAGMA foreign_keys = OFF")
		ActivityModelMigration.migrate21To22(db, java.time.ZoneId.of("Europe/Rome"))
		db.exec("PRAGMA foreign_keys = ON")
		assertEquals("K-1", a.one("SELECT external_key FROM time_entry WHERE uid = ?", e))
		assertNull(a.one("SELECT id FROM time_entry WHERE uid = ?", todo))
		/* the server's migration: T its own activity, with the key */
		val t = a.records.read("entry", e)!!.toMutableMap()
		t["activity"] = JsonPrimitive("Patch")
		t["status"] = JsonPrimitive(serverStatus)
		t["mode"] = JsonNull
		t["closedUtcMs"] = if (serverStatus == "CLOSED") JsonPrimitive(5) else JsonNull
		t["createdUtcMs"] = JsonPrimitive(1)
		server.put("company", acme, a.records.read("company", acme)!!)
		server.put("entry", todo, t)
		return Absorbed(a, acme, todo, e, seg)
	}

	@Test
	fun phoneAbsorbedTheTodoThePhoneIsTrusted() {
		val x = phoneAbsorbs()
		val r = sync(x.a)
		assertEquals(emptyList(), r.errors)
		/* the server's empty shell is deleted there; E goes up whole, with the key */
		assertTrue(server.records.getValue("entry" to x.todo).deleted)
		val e = server.records.getValue("entry" to x.e).fields
		assertEquals(JsonPrimitive("K-1"), e["externalKey"])
		assertEquals(JsonPrimitive("RUNNING"), e["status"])
		assertEquals(JsonPrimitive("Europe/Rome"), e["zoneId"])
		assertTrue(server.records.containsKey("segment" to x.seg))
		assertNull(x.a.one("SELECT id FROM time_entry WHERE uid = ?", x.todo))
		assertEquals("K-1", x.a.one("SELECT external_key FROM time_entry WHERE uid = ?", x.e))
		assertEquals(0L, x.a.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(0L, x.a.one("SELECT COUNT(*) FROM sync_error"))
		assertEquals(0L, x.a.one("SELECT realign FROM sync_state"))
	}

	@Test
	fun phoneAbsorbedTheTodoTheServerIsTrusted() {
		val x = phoneAbsorbs()
		val r = sync(x.a, authority = SyncAuthority.SERVER)
		assertEquals(emptyList(), r.errors)
		/* T as the server has it; E without the key, pointing at T, pushed as an ordinary activity */
		assertEquals("K-1", x.a.one("SELECT external_key FROM time_entry WHERE uid = ?", x.todo))
		assertEquals("TODO", x.a.one("SELECT status FROM time_entry WHERE uid = ?", x.todo))
		assertNull(x.a.one("SELECT external_key FROM time_entry WHERE uid = ?", x.e))
		assertEquals(x.todo, x.a.one("SELECT r.value FROM external_ref r JOIN time_entry t ON t.id = r.entry_id WHERE t.uid = ? AND r.kind = 'UUID'", x.e))
		assertEquals(JsonPrimitive("K-1"), server.records.getValue("entry" to x.todo).fields["externalKey"])
		val e = server.records.getValue("entry" to x.e).fields
		assertTrue(e["externalKey"] == null || e["externalKey"] is JsonNull)
		assertEquals(JsonPrimitive("RUNNING"), e["status"])
		assertEquals(0L, x.a.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(0L, x.a.one("SELECT COUNT(*) FROM sync_error"))
	}

	/* T ticked off on another device while E was started here offline: kept, without the key, pointing at E */
	@Test
	fun aTodoTickedElsewhereKeepsItsRecordWithoutTheKey() {
		val x = phoneAbsorbs(serverStatus = "CLOSED")
		val r = sync(x.a)
		assertEquals(emptyList(), r.errors)
		assertEquals("CLOSED", x.a.one("SELECT status FROM time_entry WHERE uid = ?", x.todo))
		assertNull(x.a.one("SELECT external_key FROM time_entry WHERE uid = ?", x.todo))
		assertEquals(x.e, x.a.one("SELECT r.value FROM external_ref r JOIN time_entry t ON t.id = r.entry_id WHERE t.uid = ? AND r.kind = 'UUID'", x.todo))
		val t = server.records.getValue("entry" to x.todo)
		assertTrue(!t.deleted && t.fields["externalKey"] is JsonNull)
		assertEquals(JsonPrimitive("K-1"), server.records.getValue("entry" to x.e).fields["externalKey"])
		assertEquals(0L, x.a.one("SELECT COUNT(*) FROM sync_change"))
	}

	/* a to-do ticked here offline, nothing absorbed it anywhere: the same record, its status goes up */
	@Test
	fun aTodoTickedOfflineGoesUp() {
		val a = phone(version = 21)
		val db = a.sql
		db.exec("UPDATE sync_state SET enabled = 1")
		val acme = SyncIds.random()
		val todo = SyncIds.random()
		db.exec("INSERT INTO company (id, name, archived, uid) VALUES (1, 'Acme', 0, ?)", acme)
		db.exec("INSERT INTO todo (id, uid, company_id, title, external_key, refs, done, created_utc_ms, updated_utc_ms, kind) VALUES (1, ?, 1, 'Patch', 'K-1', '', 0, 1, 2, 'TASK')", todo)
		db.exec(
			"INSERT INTO sync_shadow (type, uid, json) VALUES ('todo', ?, ?)",
			todo, """{"companyUid":"$acme","title":"Patch","externalKey":"K-1","done":false,"doneUtcMs":null,"kind":"TASK","createdUtcMs":1,"updatedUtcMs":2}""",
		)
		db.exec("DELETE FROM sync_change")
		db.exec("UPDATE todo SET done = 1, done_utc_ms = 7 WHERE id = 1")
		db.exec("INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES ('todo', ?, 'upsert', 7)", todo)
		db.exec("PRAGMA foreign_keys = OFF")
		ActivityModelMigration.migrate21To22(db, java.time.ZoneId.of("Europe/Rome"))
		db.exec("PRAGMA foreign_keys = ON")
		val t = a.records.read("entry", todo)!!.toMutableMap()
		t["status"] = JsonPrimitive("TODO")
		t["closedUtcMs"] = JsonNull
		server.put("company", acme, a.records.read("company", acme)!!)
		server.put("entry", todo, t)
		assertEquals(emptyList(), sync(a).errors)
		assertEquals(JsonPrimitive("CLOSED"), server.records.getValue("entry" to todo).fields["status"])
		assertEquals("CLOSED", a.one("SELECT status FROM time_entry WHERE uid = ?", todo))
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_change"))
	}

	/*
	 * Issue #176, the other way: here the open to-do T, changed and not
	 * synced, stayed its own activity and its closed entry E2 points at it;
	 * the server's migration let E2 absorb T (the key), with E2's work.
	 */
	private class ServerAbsorbed(val a: Phone, val todo: String, val e2: String)

	private fun serverAbsorbs(): ServerAbsorbed {
		val a = phone(version = 21)
		val db = a.sql
		db.exec("UPDATE sync_state SET enabled = 1")
		val acme = SyncIds.random()
		val todo = SyncIds.random()
		val e2 = SyncIds.random()
		db.exec("INSERT INTO company (id, name, archived, uid) VALUES (1, 'Acme', 0, ?)", acme)
		db.exec("INSERT INTO todo (id, uid, company_id, title, external_key, refs, done, created_utc_ms, updated_utc_ms, kind) VALUES (1, ?, 1, 'Patch', 'K-1', '', 0, 1, 2, 'TASK')", todo)
		db.exec(
			"INSERT INTO time_entry (id, uid, todo_id, company_id, activity, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) VALUES (1, ?, 1, 1, 'work', 'CLOSED', 'Europe/Rome', 0, 3, 4)",
			e2,
		)
		db.exec("INSERT INTO segment (entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source, uid) VALUES (1, 'WORK', 3, 4, 'Europe/Rome', 'AUTO', ?)", SyncIds.random())
		db.exec(
			"INSERT INTO sync_shadow (type, uid, json) VALUES ('todo', ?, ?)",
			todo, """{"companyUid":"$acme","title":"Patch","externalKey":"K-1","done":false,"doneUtcMs":null,"kind":"TASK","createdUtcMs":1,"updatedUtcMs":2}""",
		)
		/* synced; then T's title changed here */
		db.exec("DELETE FROM sync_change")
		db.exec("UPDATE todo SET title = 'Patch the router' WHERE id = 1")
		db.exec("INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES ('todo', ?, 'upsert', 5)", todo)
		db.exec("PRAGMA foreign_keys = OFF")
		ActivityModelMigration.migrate21To22(db, java.time.ZoneId.of("Europe/Rome"))
		db.exec("PRAGMA foreign_keys = ON")
		assertEquals("K-1", a.one("SELECT external_key FROM time_entry WHERE uid = ?", todo))
		server.put("company", acme, a.records.read("company", acme)!!)
		val r = a.records.read("entry", e2)!!.toMutableMap()
		r["externalKey"] = JsonPrimitive("K-1")
		server.put("entry", e2, r)
		val segUid = a.one("SELECT uid FROM segment WHERE entry_id = (SELECT id FROM time_entry WHERE uid = ?)", e2) as String
		server.put("segment", segUid, a.records.read("segment", segUid)!!)
		return ServerAbsorbed(a, todo, e2)
	}

	@Test
	fun serverAbsorbedTheTodoThePhoneIsTrusted() {
		val x = serverAbsorbs()
		val r = sync(x.a)
		assertEquals(emptyList(), r.errors)
		/* T keeps the key and goes up whole; E2 loses it and points at T */
		assertEquals("K-1", x.a.one("SELECT external_key FROM time_entry WHERE uid = ?", x.todo))
		assertNull(x.a.one("SELECT external_key FROM time_entry WHERE uid = ?", x.e2))
		assertEquals(x.todo, x.a.one("SELECT r.value FROM external_ref r JOIN time_entry t ON t.id = r.entry_id WHERE t.uid = ? AND r.kind = 'UUID'", x.e2))
		assertTrue(server.records.getValue("entry" to x.e2).fields["externalKey"] is JsonNull)
		val t = server.records.getValue("entry" to x.todo).fields
		assertEquals(JsonPrimitive("K-1"), t["externalKey"])
		assertEquals(JsonPrimitive("Patch the router"), t["activity"])
		assertEquals(JsonPrimitive("TODO"), t["status"])
		assertEquals(JsonPrimitive("Europe/Rome"), t["zoneId"])
		assertEquals(0L, x.a.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(0L, x.a.one("SELECT COUNT(*) FROM sync_error"))
		assertEquals(emptyList(), r.dropped)
	}

	@Test
	fun serverAbsorbedTheTodoTheServerIsTrusted() {
		val x = serverAbsorbs()
		val r = sync(x.a, authority = SyncAuthority.SERVER)
		assertEquals(emptyList(), r.errors)
		/* T is a ghost: it goes with its change, and the user is told */
		assertNull(x.a.one("SELECT id FROM time_entry WHERE uid = ?", x.todo))
		assertEquals(listOf("Patch the router"), r.dropped)
		assertEquals("K-1", x.a.one("SELECT external_key FROM time_entry WHERE uid = ?", x.e2))
		assertNull(server.records["entry" to x.todo])
		assertEquals(0L, x.a.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(0L, x.a.one("SELECT COUNT(*) FROM sync_error"))
	}

	/* "Scarta" never deletes work (issue #176): an activity the server never had, with a real segment, stays */
	@Test
	fun discardIsRefusedOnAnUnsyncedActivityWithWork() {
		val a = phone()
		val acme = company(a, "Acme")
		val e = entry(a, acme, "Patching")
		a.sql.exec("INSERT INTO segment (entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source, uid) VALUES (1, 'WORK', 0, 60000, 'Europe/Rome', 'MANUAL', ?)", SyncIds.random())
		server.refuse += e
		sync(a)
		assertEquals(1L, a.one("SELECT COUNT(*) FROM sync_error WHERE uid = ?", e))
		assertEquals(false, runBlocking { a.db.run { it.discard("entry", e) } })
		assertEquals(e, a.one("SELECT uid FROM time_entry WHERE uid = ?", e))
		assertEquals(1L, a.one("SELECT COUNT(*) FROM sync_error WHERE uid = ?", e))
		/* without work it goes, as before */
		val empty = entry(a, acme, "nothing")
		server.refuse += empty
		sync(a)
		assertEquals(true, runBlocking { a.db.run { it.discard("entry", empty) } })
		assertNull(a.one("SELECT id FROM time_entry WHERE uid = ?", empty))
	}

	/* issue #176, C: a pulled record that cannot be applied keeps the cursor before it; the realignment is tried again */
	@Test
	fun theRealignmentDoesNotMovePastARecordNotApplied() {
		val a = phone()
		val acme = company(a, "Acme")
		sync(a)
		/* a segment of an entry the server does not send (another user's, say): never applicable */
		val orphan = SyncIds.random()
		server.put("segment", orphan, mapOf("entryUid" to JsonPrimitive(SyncIds.random()), "kind" to JsonPrimitive("WORK"), "startUtcMs" to JsonPrimitive(0), "zoneId" to JsonPrimitive("Europe/Rome"), "source" to JsonPrimitive("MANUAL")))
		val rev = server.rev
		server.put("company", SyncIds.random(), mapOf("name" to JsonPrimitive("Beta"), "archived" to JsonPrimitive(false)))
		a.sql.exec("UPDATE sync_state SET realign = 1, cursor = 0")
		assertFailsWith<SyncFailed> { sync(a) }
		assertEquals(rev - 1, a.one("SELECT cursor FROM sync_state") as Long)
		assertEquals(1L, a.one("SELECT realign FROM sync_state"))
		/* the record comes right: the realignment goes through */
		server.records.remove("segment" to orphan)
		sync(a)
		assertEquals(0L, a.one("SELECT realign FROM sync_state"))
		assertEquals(1L, a.one("SELECT COUNT(*) FROM company WHERE name = 'Beta'"))
		assertEquals(acme, a.one("SELECT uid FROM company WHERE name = 'Acme'"))
	}

	@Test
	fun upgradeRequiredIsReportedAsIncompatible() {
		val a = phone()
		server.incompatible = "upgrade_required"
		val ex = assertFailsWith<SyncIncompatible> { sync(a) }
		assertEquals("upgrade_required", ex.code)
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
	fun aRecordWhoseUidIsNotAPlainTokenIsRefused() {
		val b = phone()
		server.put("company", "../../etc/x", mapOf("name" to JsonPrimitive("Evil"), "archived" to JsonPrimitive(false)))
		val ok = SyncIds.random()
		server.put("company", ok, mapOf("name" to JsonPrimitive("Good"), "archived" to JsonPrimitive(false)))
		val r = sync(b)
		assertEquals("Good", b.one("SELECT name FROM company WHERE uid = ?", ok))
		assertEquals(0L, b.one("SELECT COUNT(*) FROM company WHERE name = 'Evil'"))
		assertTrue(r.errors.any { "uid refused" in it }, r.errors.toString())
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

	/* a server migrated to 1.0 still sends the scope exits of the removed types (todo, absence): skipped, the pull goes on */
	@Test
	fun aGoneRecordOfAnUnknownTypeIsSkipped() {
		val a = phone()
		company(a, "Acme")
		sync(a)
		server.gone += "todo" to SyncIds.random()
		server.put("company", SyncIds.random(), mapOf("name" to JsonPrimitive("Beta"), "archived" to JsonPrimitive(false)))
		sync(a)
		assertEquals(server.rev, a.one("SELECT cursor FROM sync_state") as Long)
		assertEquals(null, a.one("SELECT last_error FROM sync_state"))
		assertEquals(1L, a.one("SELECT COUNT(*) FROM company WHERE name = 'Beta'"))
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

	@Test
	fun anIncompatibleServerStopsTheSyncAndKeepsTheData() {
		val a = phone()
		company(a, "Acme")
		sync(a)
		company(a, "Beta")
		server.incompatible = "app_too_old"
		val e = assertFailsWith<SyncIncompatible> { sync(a) }
		assertEquals("app_too_old", e.code)
		assertEquals("incompatible: app_too_old", a.one("SELECT last_error FROM sync_state"))
		/* the change waits for the update */
		assertEquals(1L, a.one("SELECT COUNT(*) FROM sync_change"))
		server.incompatible = null
		sync(a)
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_change"))
	}
}
