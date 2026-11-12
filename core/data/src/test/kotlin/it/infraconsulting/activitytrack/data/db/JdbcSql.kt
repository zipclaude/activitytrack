/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject

/* SyncSchema.Sql on sqlite-jdbc, with values as Android's cursor gives them (Long, Double, String). */
class JdbcSql(private val c: Connection) : SyncSchema.Sql {
	/* SQLite errors as unchecked exceptions, like Android's SQLiteException */
	override fun exec(sql: String, vararg args: Any?) {
		try {
			c.prepareStatement(sql).use { s ->
				args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
				s.execute()
			}
		} catch (e: java.sql.SQLException) {
			throw IllegalStateException(e.message, e)
		}
	}

	override fun rows(sql: String, vararg args: Any?): List<List<Any?>> = c.prepareStatement(sql).use { s ->
		args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
		s.executeQuery().use { r ->
			val n = r.metaData.columnCount
			buildList {
				while (r.next()) add(
					(1..n).map {
						when (val v = r.getObject(it)) {
							is Int -> v.toLong()
							is Float -> v.toDouble()
							else -> v
						}
					},
				)
			}
		}
	}

	companion object {
		/* what Room compares: columns, indexes and foreign keys of every table, and the triggers */
		fun shape(c: Connection): Map<String, Any> {
			val sql = JdbcSql(c)
			val tables = sql.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('room_master_table', 'sqlite_sequence', 'android_metadata') ORDER BY name")
				.map { it[0] as String }
			val out = sortedMapOf<String, Any>()
			for (t in tables) {
				val columns = sql.rows("PRAGMA table_info(`$t`)").map { listOf(it[1], it[2], it[3], it[4], it[5]) }.sortedBy { it[0] as String }
				val indexes = sql.rows("PRAGMA index_list(`$t`)").filter { (it[1] as String).startsWith("index_") }
					.map { listOf(it[1], it[2], sql.rows("PRAGMA index_info(`${it[1]}`)").map { col -> col[2] }) }.sortedBy { it[0] as String }
				val keys = sql.rows("PRAGMA foreign_key_list(`$t`)").map { it.drop(2) }.sortedBy { it.toString() }
				out[t] = listOf(columns, indexes, keys)
			}
			out["triggers"] = sql.rows("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
			return out
		}

		private val dir = File("schemas/it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase")

		/* a database of that version as Room creates it, foreign keys on as Room has them */
		fun create(version: Int): Connection {
			val c = DriverManager.getConnection("jdbc:sqlite::memory:")
			val sql = JdbcSql(c)
			sql.exec("PRAGMA foreign_keys = ON")
			val db = JSONObject(File(dir, "$version.json").readText()).getJSONObject("database")
			val entities = db.getJSONArray("entities")
			for (i in 0 until entities.length()) {
				val e = entities.getJSONObject(i)
				val table = e.getString("tableName")
				sql.exec(e.getString("createSql").replace("\${TABLE_NAME}", table))
				val indices = e.optJSONArray("indices") ?: continue
				for (j in 0 until indices.length())
					sql.exec(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
			}
			return c
		}
	}
}
