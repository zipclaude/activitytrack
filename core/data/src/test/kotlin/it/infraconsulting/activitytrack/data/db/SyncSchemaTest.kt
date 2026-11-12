/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import it.infraconsulting.activitytrack.domain.sync.SyncIds
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Issue #111 on a real SQLite: the 15 -> 16 migration must give the schema
 * Room exported for 16 (what Room checks when it opens the database), fill
 * the uids, and the triggers must log every change.
 */
class SyncSchemaTest {
	private val open = mutableListOf<Connection>()

	@AfterTest
	fun close() = open.forEach { it.close() }

	private fun create(version: Int): Connection = JdbcSql.create(version).also { open += it }

	private fun exec(c: Connection, sql: String, vararg args: Any?) = JdbcSql(c).exec(sql, *args)

	private fun rows(c: Connection, sql: String): List<List<Any?>> = JdbcSql(c).rows(sql)

	private fun sql(c: Connection) = JdbcSql(c)

	private fun shape(c: Connection) = JdbcSql.shape(c)

	private fun fresh16(): Connection = create(16).also { SyncSchema.onCreate(sql(it)) }

	private fun sample15(): Connection {
		val c = create(15)
		exec(c, "INSERT INTO company (id, name, archived) VALUES (1, 'Acme', 0), (2, 'Beta', 0)")
		exec(c, "INSERT INTO client (id, company_id, name, archived) VALUES (1, 1, 'Banca', 0)")
		exec(c, "INSERT INTO contact (id, client_id, name) VALUES (1, 1, 'Rossi')")
		exec(c, "INSERT INTO contact_phone (contact_id, number) VALUES (1, '+390461123456')")
		exec(c, "INSERT INTO role (id, name, archived) VALUES (1, 'Sysadmin', 0)")
		exec(c, "INSERT INTO lunch_break (day_of_week, start_min, end_min) VALUES (1, 750, 840), (2, 750, 840)")
		val entry = "INSERT INTO time_entry (id, uid, client_id, company_id, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) VALUES (?, ?, 1, 1, 'CLOSED', 'Europe/Rome', 0, 0, 0)"
		exec(c, entry, 1, "0123456789ABCDEF0123456789abcdef")
		exec(c, entry, 2, "entry-12")
		exec(c, entry, 3, null)
		exec(c, entry, 4, "6f1c0f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f")
		exec(c, "INSERT INTO segment (entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source) VALUES (1, 'WORK', 0, 60000, 'Europe/Rome', 'TRACKED'), (1, 'PAUSE', 60000, 120000, 'Europe/Rome', 'MANUAL')")
		exec(c, "INSERT INTO entry_tag (entry_id, tag) VALUES (1, 'urgente')")
		exec(c, "INSERT INTO todo (id, uid, company_id, title, refs, done, created_utc_ms, updated_utc_ms) VALUES (1, 'B6C4E1F2-0000-4000-8000-000000000001', 1, 'Patch', '', 0, 0, 0)")
		exec(c, "INSERT INTO day_off (date_epoch_day, note) VALUES (20000, 'ponte')")
		exec(c, "INSERT INTO remote_item (company_id, plugin_id, list, parent_id, id, name, active, extra) VALUES (1, 'sdp', 'services', '', 's1', 'Rete', 1, '{}'), (1, 'sdp', 'types', 's1', 't1', 'Intervento', 1, '{}')")
		return c
	}

	@Test
	fun migrationGivesTheSchemaRoomExpects() {
		val c = sample15()
		SyncSchema.migrate15To16(sql(c))
		assertEquals(shape(fresh16()), shape(c))
	}

	@Test
	fun migrationFillsCanonicalUniqueUids() {
		val c = sample15()
		SyncSchema.migrate15To16(sql(c))
		val present = rows(c, "SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }.toSet()
		/* tables of later schemas are not there yet */
		for (table in SyncSchema.TYPES.keys.filter { it in present }) {
			val uids = rows(c, "SELECT uid FROM `$table`").map { it[0] as String }
			assertTrue(uids.all { SyncIds.isCanonical(it) }, "$table: $uids")
			assertEquals(uids.size, uids.toSet().size, table)
		}
		val entries = rows(c, "SELECT id, uid FROM time_entry ORDER BY id").associate { (it[0] as Number).toLong() to it[1] as String }
		assertEquals("01234567-89ab-cdef-0123-456789abcdef", entries[1])
		assertEquals(SyncIds.canonical("entry-12"), entries[2])
		assertEquals("6f1c0f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", entries[4])
		assertEquals("b6c4e1f2-0000-4000-8000-000000000001", rows(c, "SELECT uid FROM todo")[0][0])
		assertEquals(SyncIds.dayOff(20000), rows(c, "SELECT uid FROM day_off")[0][0])
		val company = rows(c, "SELECT uid FROM company WHERE id = 1")[0][0] as String
		assertEquals(SyncIds.remoteItem(company, "sdp", "types", "s1", "t1"), rows(c, "SELECT uid FROM remote_item WHERE id = 't1'")[0][0])
		/* nothing logged: the app is not paired yet */
		assertEquals(0L, rows(c, "SELECT COUNT(*) FROM sync_change")[0][0])
	}

	/*
	 * 19 -> 20 (issue #133) is an AutoMigration: Room adds the tag table as
	 * 20.json has it; opening adds its triggers like those of the other tables.
	 */
	@Test
	fun tagTableAfterTheMigrationIsSyncedLikeTheOthers() {
		val c = create(19).also { SyncSchema.onCreate(sql(it)) }
		val tag = org.json.JSONObject(java.io.File("schemas/it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase/20.json").readText())
			.getJSONObject("database").getJSONArray("entities").let { a -> (0 until a.length()).map(a::getJSONObject).first { it.getString("tableName") == "tag" } }
		exec(c, tag.getString("createSql").replace("\${TABLE_NAME}", "tag"))
		val indices = tag.getJSONArray("indices")
		for (i in 0 until indices.length()) exec(c, indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "tag"))
		SyncSchema.onOpen(sql(c))
		assertEquals(shape(create(20).also { SyncSchema.onCreate(sql(it)) }), shape(c))
		exec(c, "UPDATE sync_state SET enabled = 1")
		val urgente = SyncIds.random()
		exec(c, "INSERT INTO tag (name, color, archived, uid, central) VALUES ('urgente', 14042181, 0, ?, 0)", urgente)
		assertEquals(listOf(listOf("tag", urgente, "upsert")), changes(c))
	}

	/*
	 * 21 -> 22 (activity model 1.0) on the shipped schema 21 of a paired
	 * phone: the schema Room expects for 22 with every trigger, the to-dos
	 * and absences turned into activities, entry ids kept, and the sync
	 * told to realign (its wire-2 log and shadows dropped, the cursor reset).
	 */
	@Test
	fun migration21To22GivesTheSchemaRoomExpectsAndRealigns() {
		val c = create(21).also { SyncSchema.onCreate(sql(it)) }
		exec(c, "INSERT INTO company (id, name, archived, uid) VALUES (1, 'Acme', 0, 'company-1')")
		exec(c, "INSERT INTO client (id, company_id, name, archived, uid) VALUES (1, 1, 'Banca', 0, 'client-1')")
		exec(c, "INSERT INTO work_schedule (day_of_week, start_min, end_min, uid) VALUES (5, 540, 1080, 'ws-5')")
		exec(c, "UPDATE sync_state SET enabled = 1, cursor = 42")
		exec(c, "INSERT INTO todo (id, uid, company_id, client_id, external_key, title, refs, priority, done, created_utc_ms, updated_utc_ms, kind) VALUES (7, 'todo-7', 1, 1, 'T-7', 'Patch', 'INC1', 1, 0, 1, 2, 'TASK')")
		exec(c, "INSERT INTO todo (id, uid, title, refs, done, created_utc_ms, updated_utc_ms, plan_epoch_day, kind) VALUES (8, 'todo-8', 'Ferie', '', 1, 1, 2, 20770, 'ABSENCE')")
		val entry = "INSERT INTO time_entry (id, uid, todo_id, company_id, client_id, status, mode, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) VALUES (?, ?, ?, 1, 1, ?, ?, 'Europe/Rome', 0, 1, 2)"
		exec(c, entry, 10, "e-10", 7, "ACTIVE", "WORK")
		exec(c, entry, 11, "e-11", null, "TO_COMPLETE", null)
		exec(c, "INSERT INTO segment (id, entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source, uid) VALUES (1, 10, 'WORK', 1000, NULL, 'Europe/Rome', 'TRACKED', 's-1'), (2, 11, 'WORK', 0, 900, 'Europe/Rome', 'TRACKED', 's-2')")
		exec(c, "INSERT INTO location_sample (entry_id, segment_id, ts_utc_ms, lat, lon) VALUES (10, 1, 1000, 46.0, 11.0)")
		exec(c, "INSERT INTO sync_shadow (type, uid, json) VALUES ('todo', 'todo-7', '{}')")
		exec(c, "INSERT INTO sync_shadow (type, uid, json) VALUES ('entry', 'e-11', '{\"todoUid\":null,\"status\":\"TO_COMPLETE\",\"notes\":null}')")
		exec(c, "INSERT INTO sync_shadow (type, uid, json) VALUES ('company', 'company-1', '{\"name\":\"Acme\"}')")
		/* synced so far; then two changes not synced: the to-do (absorbed by e-10) and the entry e-11 */
		exec(c, "DELETE FROM sync_change")
		exec(c, "UPDATE todo SET notes = 'local' WHERE id = 7")
		/* the to-do's trigger of a 0.9 app (this SyncSchema no longer has it) */
		exec(c, "INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES ('todo', 'todo-7', 'upsert', 5)")
		exec(c, "UPDATE time_entry SET notes = 'local' WHERE id = 11")
		/* as Room runs a migration: foreign keys off, then checked */
		exec(c, "PRAGMA foreign_keys = OFF")
		ActivityModelMigration.migrate21To22(sql(c), java.time.ZoneId.of("Europe/Rome"))
		exec(c, "PRAGMA foreign_keys = ON")
		assertEquals(shape(create(22).also { SyncSchema.onCreate(sql(it)) }), shape(c))
		assertEquals(emptyList(), rows(c, "PRAGMA foreign_key_check"))
		val got = rows(c, "SELECT id, uid, status, mode, kind, external_key, priority, absence_kind FROM time_entry ORDER BY id")
		assertEquals(
			listOf(
				/* running, absorbed the to-do (a running entry): its key and priority P1 -> 5 */
				listOf(10L, "e-10", "RUNNING", "WORK", "ACTIVITY", "T-7", 5L, null),
				listOf(11L, "e-11", "CLOSED", null, "ACTIVITY", null, 0L, null),
				/* the absence to-do: an ABSENCE activity with its uid, on Friday 2026-11-13 */
				listOf(12L, "todo-8", "CLOSED", null, "ABSENCE", null, 0L, "HOLIDAY"),
			),
			got,
		)
		assertEquals(listOf(listOf("ABSENCE", 12L)), rows(c, "SELECT kind, entry_id FROM segment WHERE id > 2"))
		assertEquals(listOf(listOf("INC1")), rows(c, "SELECT value FROM external_ref WHERE entry_id = 10"))
		assertEquals(1L, rows(c, "SELECT COUNT(*) FROM location_sample WHERE entry_id = 10")[0][0])
		/* the realignment: the unsynced changes stay, under their 1.0 records, with what the migration derived from them */
		assertEquals(listOf(listOf(1L, 1L, 0L, 0L)), rows(c, "SELECT enabled, realign, cursor, applying FROM sync_state"))
		val ref = rows(c, "SELECT uid FROM external_ref WHERE entry_id = 10")[0][0] as String
		assertEquals(
			setOf(listOf("entry", "e-10", "upsert"), listOf("externalRef", ref, "upsert"), listOf("entry", "e-11", "upsert")),
			changes(c).toSet(),
		)
		exec(c, "DELETE FROM sync_change")
		/* the shadows go but those of the dirty records, in wire 3: the base the realignment merges against */
		assertEquals(
			listOf(listOf("entry", "e-11", """{"notes":null,"status":"CLOSED","kind":"ACTIVITY","deletedUtcMs":null}""")),
			rows(c, "SELECT type, uid, json FROM sync_shadow"),
		)
		/* the rebuilt table logs again */
		exec(c, "UPDATE time_entry SET notes = 'x' WHERE id = 11")
		assertEquals(listOf(listOf("entry", "e-11", "upsert")), changes(c))
	}

	@Test
	fun everyTableWithAUidIsSynced() {
		val withUid = rows(create(ActivityTrackDatabase.VERSION), "SELECT m.name FROM sqlite_master m, pragma_table_info(m.name) p WHERE m.type = 'table' AND p.name = 'uid' AND m.name NOT LIKE 'sync_%'")
			.map { it[0] as String }.toSet()
		assertEquals(withUid, SyncSchema.TYPES.keys)
	}

	private fun changes(c: Connection) = rows(c, "SELECT type, uid, op FROM sync_change ORDER BY seq").map { it.map { v -> v as String } }

	@Test
	fun triggersLogChangesOnlyWhilePaired() {
		val c = fresh16()
		val acme = SyncIds.random()
		exec(c, "INSERT INTO company (id, name, archived, uid) VALUES (1, 'Acme', 0, ?)", acme)
		assertEquals(emptyList(), changes(c))
		exec(c, "UPDATE sync_state SET enabled = 1")
		exec(c, "UPDATE company SET name = 'Acme S.p.A.' WHERE id = 1")
		val beta = SyncIds.random()
		exec(c, "INSERT INTO company (id, name, archived, uid) VALUES (2, 'Beta', 0, ?)", beta)
		exec(c, "DELETE FROM company WHERE id = 2")
		assertEquals(listOf(listOf("company", acme, "upsert"), listOf("company", beta, "upsert"), listOf("company", beta, "delete")), changes(c))
		exec(c, "DELETE FROM sync_change")
		exec(c, "UPDATE sync_state SET applying = 1")
		exec(c, "UPDATE company SET name = 'from the server' WHERE id = 1")
		assertEquals(emptyList(), changes(c))
	}

	@Test
	fun anUpdateKeepsTheUid() {
		val c = fresh16()
		exec(c, "UPDATE sync_state SET enabled = 1")
		val uid = SyncIds.random()
		exec(c, "INSERT INTO role (id, name, archived, uid) VALUES (1, 'Dev', 0, ?)", uid)
		/* a Room @Update of an entity rebuilt with a new default uid */
		exec(c, "UPDATE role SET name = 'Developer', uid = ? WHERE id = 1", SyncIds.random())
		assertEquals(uid, rows(c, "SELECT uid FROM role")[0][0])
		assertEquals("Developer", rows(c, "SELECT name FROM role")[0][0])
		val log = changes(c)
		assertTrue(log.isNotEmpty() && log.all { it[1] == uid }, log.toString())
	}

	@Test
	fun foldedRowsTouchTheirParentAndCascadesAreLogged() {
		val c = fresh16()
		val client = SyncIds.random()
		val contact = SyncIds.random()
		val entry = SyncIds.random()
		val segment = SyncIds.random()
		exec(c, "INSERT INTO client (id, name, archived, uid) VALUES (1, 'Banca', 0, ?)", client)
		exec(c, "INSERT INTO contact (id, client_id, name, uid) VALUES (1, 1, 'Rossi', ?)", contact)
		exec(c, "INSERT INTO time_entry (id, uid, client_id, status, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) VALUES (1, ?, 1, 'CLOSED', 'Europe/Rome', 0, 0, 0)", entry)
		exec(c, "INSERT INTO segment (entry_id, kind, start_utc_ms, zone_id, source, uid) VALUES (1, 'WORK', 0, 'Europe/Rome', 'TRACKED', ?)", segment)
		exec(c, "UPDATE sync_state SET enabled = 1")
		exec(c, "INSERT INTO contact_phone (contact_id, number) VALUES (1, '+39055')")
		exec(c, "INSERT INTO entry_tag (entry_id, tag) VALUES (1, 'rete')")
		assertEquals(listOf(listOf("contact", contact, "upsert"), listOf("entry", entry, "upsert")), changes(c))
		exec(c, "DELETE FROM sync_change")
		exec(c, "DELETE FROM time_entry WHERE id = 1")
		val log = changes(c)
		assertTrue(listOf("segment", segment, "delete") in log, log.toString())
		assertEquals(listOf("entry", entry, "delete"), log.last())
	}

	@Test
	fun openingPutsBackLostTriggers() {
		val c = fresh16()
		exec(c, "DROP TRIGGER `sync_company_insert`")
		SyncSchema.onOpen(sql(c))
		assertEquals(shape(fresh16())["triggers"], shape(c)["triggers"])
	}
}
