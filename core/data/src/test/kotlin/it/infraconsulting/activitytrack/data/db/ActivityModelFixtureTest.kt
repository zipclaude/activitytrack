/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * The 21 -> 22 migration on real databases (ACTIVITY-MODEL.md 6, 6.1):
 * the backup zips of src/test/resources/activity-migration, written by
 * scripts/legacy-fixtures.py from the shared cases of
 * docs/samples/activity-migration.json (the standard working week), as a
 * restore unpacks them. The v21 result must equal expected-v21.json, the
 * Python reference's migration of the same data, in the normal form the
 * cases describe; the v20 one goes through 20 -> 21 first.
 */
class ActivityModelFixtureTest {
	private val open = mutableListOf<Connection>()
	private val rome = ZoneId.of("Europe/Rome")

	@AfterTest
	fun close() = open.forEach { it.close() }

	/* the database of a backup zip in a temporary file, as DatabaseBackup.stage unpacks it */
	private fun restore(name: String): Connection {
		val file = File.createTempFile("restore", ".db").apply { deleteOnExit() }
		ZipInputStream(javaClass.getResourceAsStream("/activity-migration/$name")!!).use { zip ->
			while (true) {
				val e = zip.nextEntry ?: break
				if (e.name == ActivityTrackDatabase.FILE_NAME) file.outputStream().use { zip.copyTo(it) }
			}
		}
		return DriverManager.getConnection("jdbc:sqlite:${file.path}").also { open += it }
	}

	private fun sql(c: Connection) = JdbcSql(c)

	/* as Room runs a migration: foreign keys off, checked by the migration itself */
	private fun migrate21To22(c: Connection) {
		sql(c).exec("PRAGMA foreign_keys = OFF")
		ActivityModelMigration.migrate21To22(sql(c), rome)
		sql(c).exec("PRAGMA foreign_keys = ON")
	}

	private fun userVersion(c: Connection) = (sql(c).rows("PRAGMA user_version")[0][0] as Number).toInt()

	/* the normal form of docs/samples/activity-migration.json: no ids, children by entry uid, nulls left out, plugin_fields parsed */
	private fun normalForm(c: Connection): Map<String, List<JsonObject>> {
		val db = sql(c)
		fun table(name: String): List<Map<String, Any?>> {
			val cols = db.rows("PRAGMA table_info(`$name`)").map { it[1] as String }
			return db.rows("SELECT ${cols.joinToString { "`$it`" }} FROM `$name`").map { r -> cols.zip(r).toMap() }
		}
		val entries = table("time_entry")
		val uidOf = entries.associate { (it["id"] as Number).toLong() to it["uid"] as String }
		fun json(row: Map<String, Any?>): JsonObject = JsonObject(
			row.filter { (k, v) -> v != null && k != "id" }.map { (k, v) ->
				when {
					k == "entry_id" -> "entry_uid" to JsonPrimitive(uidOf.getValue((v as Number).toLong()))
					k == "plugin_fields" -> k to Json.parseToJsonElement(v as String)
					v is Number -> k to JsonPrimitive(v)
					else -> k to JsonPrimitive(v as String)
				}
			}.toMap(),
		)
		return mapOf("time_entry" to entries.map(::json), "segment" to table("segment").map(::json), "external_ref" to table("external_ref").map(::json))
	}

	private fun canonical(o: JsonObject): String = JsonObject(o.toSortedMap().mapValues { (_, v) -> numbers(v) }).toString()

	/* 1 and 1.0 compare equal (JSON numbers of Python and of SQLite) */
	private fun numbers(v: JsonElement): JsonElement =
		if (v is JsonPrimitive && !v.isString) v.content.toDoubleOrNull()?.let { d -> if (d == Math.floor(d) && !v.content.contains("e")) JsonPrimitive(d.toLong()) else JsonPrimitive(d) } ?: v
		else v

	private fun expected(): Map<String, List<JsonObject>> {
		val doc = Json.parseToJsonElement(javaClass.getResource("/activity-migration/expected-v21.json")!!.readText()).jsonObject
		return TABLES.associateWith { t -> doc[t]!!.jsonArray.map { it.jsonObject } }
	}

	private fun diff(want: Map<String, List<JsonObject>>, got: Map<String, List<JsonObject>>): String = TABLES.mapNotNull { t ->
		val w = want.getValue(t).map(::canonical).sorted()
		val g = got.getValue(t).map(::canonical).sorted()
		if (w == g) null else "$t\n  missing ${w - g.toSet()}\n  extra   ${g - w.toSet()}"
	}.joinToString("\n")

	@Test
	fun v21BackupMatchesThePythonReference() {
		val c = restore("activitytrack-v21-backup.zip")
		assertEquals(21, userVersion(c))
		val before = sql(c).rows("SELECT id, uid FROM time_entry ORDER BY id")
		migrate21To22(c)
		assertEquals("", diff(expected(), normalForm(c)))
		/* every old entry kept its id and uid: the children still point at it */
		val after = sql(c).rows("SELECT id, uid FROM time_entry ORDER BY id").toSet()
		assertTrue(after.containsAll(before))
		assertEquals(listOf(listOf<Any?>("ok")), sql(c).rows("PRAGMA integrity_check"))
	}

	@Test
	fun v21BackupGivesTheSchemaRoomExpects() {
		val c = restore("activitytrack-v21-backup.zip")
		migrate21To22(c)
		val fresh = JdbcSql.create(22).also { open += it; SyncSchema.onCreate(sql(it)) }
		assertEquals(JdbcSql.shape(fresh), JdbcSql.shape(c))
	}

	/*
	 * A v20 backup restored (6.1): 20 -> 21 (Room's AutoMigration: three
	 * columns of todo, then AbsencesBecomeTodos) and 21 -> 22 in a row. The
	 * v20 data are the v21 data without the absence to-dos (their column did
	 * not exist); the legacy absence rows become absence to-dos with random
	 * uids, then ABSENCE activities: compared by their segments' times.
	 */
	@Test
	fun v20BackupGoesThroughTwoMigrations() {
		val c = restore("activitytrack-v20-backup.zip")
		assertEquals(20, userVersion(c))
		val db = sql(c)
		val cases = (db.rows("SELECT uid FROM time_entry") + db.rows("SELECT uid FROM todo") + db.rows("SELECT uid FROM absence")).map { (it[0] as String).take(4) }.toSet()
		val absences = (db.rows("SELECT COUNT(*) FROM absence")[0][0] as Number).toInt()
		assertTrue(absences > 0, "the fixture has legacy absences")
		/* 20 -> 21 as Room's AutoMigration does it */
		db.exec("ALTER TABLE `todo` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'TASK'")
		db.exec("ALTER TABLE `todo` ADD COLUMN `color` INTEGER")
		db.exec("ALTER TABLE `todo` ADD COLUMN `plan_end_epoch_day` INTEGER")
		ActivityTrackDatabase.Legacy.absencesBecomeTodos(db, 1_793_606_400_000L)
		assertEquals(JdbcSql.shape(JdbcSql.create(21).also { open += it }), JdbcSql.shape(c))
		migrate21To22(c)
		val fresh = JdbcSql.create(22).also { open += it; SyncSchema.onCreate(sql(it)) }
		assertEquals(JdbcSql.shape(fresh), JdbcSql.shape(c))

		val got = normalForm(c)
		val want = expected()
		/* what is not an absence: the v21 reference restricted to the cases the v20 file has */
		val absenceUids = { m: Map<String, List<JsonObject>> -> m.getValue("time_entry").filter { it.str("kind") == "ABSENCE" }.map { it.str("uid") }.toSet() }
		fun plain(m: Map<String, List<JsonObject>>, keep: (String) -> Boolean): Map<String, List<JsonObject>> {
			val abs = absenceUids(m)
			return mapOf(
				"time_entry" to m.getValue("time_entry").filter { it.str("uid") !in abs && keep(it.str("uid")!!) },
				"segment" to m.getValue("segment").filter { it.str("entry_uid") !in abs && keep(it.str("entry_uid")!!) },
				"external_ref" to m.getValue("external_ref").filter { it.str("entry_uid") !in abs && keep(it.str("entry_uid")!!) },
			)
		}
		assertEquals("", diff(plain(want) { it.take(4) in cases }, plain(got) { true }))
		/* the absences: the legacy rows of those cases, their segments where the v21 path puts them */
		fun absenceSegments(m: Map<String, List<JsonObject>>, uids: Set<String?>) =
			m.getValue("segment").filter { it.str("entry_uid") in uids }.map { listOf(it.str("kind"), it.str("start_utc_ms"), it.str("end_utc_ms")) }.sortedBy { it.toString() }
		val wantAbsences = absenceUids(want).filter { it!!.take(4) in cases }.toSet()
		val gotAbsences = absenceUids(got)
		assertEquals(wantAbsences.size, gotAbsences.size)
		assertEquals(absenceSegments(want, wantAbsences), absenceSegments(got, gotAbsences))
		assertEquals(0L, (sql(c).rows("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('todo', 'absence')")[0][0] as Number).toLong())
	}

	private fun JsonObject.str(k: String): String? = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

	companion object {
		val TABLES = listOf("time_entry", "segment", "external_ref")
	}
}
