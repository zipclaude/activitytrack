/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/* Queries of the DAOs run on sqlite-jdbc against the exported schema, as Room would run them. */
class DaoSqlTest {
	private val c: Connection = JdbcSql.create(ActivityTrackDatabase.VERSION)
	private val sql = JdbcSql(c)

	@AfterTest
	fun close() = c.close()

	private fun entry(id: Long, uid: String, status: String = "CLOSED", key: String? = null, company: Long = 1, kind: String = "ACTIVITY", created: Long = 0) =
		sql.exec(
			"INSERT INTO time_entry (id, uid, company_id, client_id, status, kind, external_key, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms) " +
				"VALUES (?, ?, ?, NULL, ?, ?, ?, 'UTC', 0, ?, 0)",
			id, uid, company, status, kind, key, created,
		)

	private fun uuidRef(entryId: Long, value: String) =
		sql.exec("INSERT INTO external_ref (entry_id, kind, value, uid) VALUES (?, 'UUID', ?, ?)", entryId, value, "ref-$entryId-$value")

	private fun ids(query: String, vararg args: Any?) = sql.rows(query, *args).map { it[0] as Long }

	private fun companies() {
		sql.exec("INSERT INTO company (id, name, archived, uid) VALUES (1, 'Acme', 0, 'c1'), (2, 'Other', 0, 'c2')")
	}

	/* a migrated to-do's entries point at it with a UUID reference only: they are sent, with @activity */
	@Test
	fun entriesOfAnImportedActivityByUuidAreSentAndFindIt() {
		companies()
		/* the to-do, still open, its own TODO activity (ACTIVITY-MODEL.md 6, step 3) */
		entry(1, "todo-1", status = "TODO", key = "T-1")
		/* two closed entries of it, and an unrelated one */
		entry(2, "e-2")
		uuidRef(2, "todo-1")
		entry(3, "e-3", created = 5)
		uuidRef(3, "todo-1")
		entry(4, "e-4")
		/* a UUID reference to an activity that was not imported */
		entry(5, "e-5")
		uuidRef(5, "e-4")
		/* the same in another company */
		entry(6, "todo-6", status = "TODO", key = "T-6", company = 2)
		entry(7, "e-7")
		uuidRef(7, "todo-6")
		assertEquals(listOf(2L, 3L), ids(ENTRIES_TO_SEND, 1L, "plugin"))
		assertEquals(listOf(1L), ids(LINKED_ACTIVITY, 2L, 1L))
		assertEquals(listOf(1L), ids(LINKED_ACTIVITY, 3L, 1L))
		assertEquals(emptyList(), ids(LINKED_ACTIVITY, 5L, 1L))
		assertEquals(emptyList(), ids(LINKED_ACTIVITY, 7L, 1L))
	}
}
