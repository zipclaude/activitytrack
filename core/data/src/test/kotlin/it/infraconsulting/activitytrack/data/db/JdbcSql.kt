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
