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

	/* what Room compares: columns, indexes and foreign keys of every table, and the triggers */
	private fun shape(c: Connection): Map<String, Any> {
		val tables = rows(c, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('room_master_table', 'sqlite_sequence') ORDER BY name")
			.map { it[0] as String }
		val out = sortedMapOf<String, Any>()
		for (t in tables) {
			val columns = rows(c, "PRAGMA table_info(`$t`)").map { listOf(it[1], it[2], it[3], it[4], it[5]) }.sortedBy { it[0] as String }
			val indexes = rows(c, "PRAGMA index_list(`$t`)").filter { (it[1] as String).startsWith("index_") }
				.map { listOf(it[1], it[2], rows(c, "PRAGMA index_info(`${it[1]}`)").map { col -> col[2] }) }.sortedBy { it[0] as String }
			val keys = rows(c, "PRAGMA foreign_key_list(`$t`)").map { it.drop(2) }.sortedBy { it.toString() }
			out[t] = listOf(columns, indexes, keys)
		}
		out["triggers"] = rows(c, "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
		return out
	}

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
		for (table in SyncSchema.TYPES.keys) {
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

	@Test
	fun everyTableWithAUidIsSynced() {
		val withUid = rows(create(16), "SELECT m.name FROM sqlite_master m, pragma_table_info(m.name) p WHERE m.type = 'table' AND p.name = 'uid' AND m.name NOT LIKE 'sync_%'")
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
