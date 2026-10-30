/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.sync

import it.infraconsulting.activitytrack.data.db.SyncSchema
import it.infraconsulting.activitytrack.domain.sync.RecordType
import it.infraconsulting.activitytrack.domain.sync.SyncMapping
import it.infraconsulting.activitytrack.domain.sync.WireException
import it.infraconsulting.activitytrack.domain.sync.WireKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Synced records read from and written to the database as their wire fields
 * (issue #112), through SyncMapping. The caller runs these in a transaction
 * with sync_state.applying set when they come from the server.
 */
class SyncRecords(private val db: SyncSchema.Sql) {
	/* a reference to a record this phone does not have */
	class UnknownRef(val field: String, val uid: String) : Exception("$field: no record $uid")

	private fun typeOf(type: String) = SyncMapping.byType[type] ?: throw WireException("unknown type $type")

	/* the record's wire fields, or null when there is no such row */
	fun read(type: String, uid: String): JsonObject? {
		val t = typeOf(type)
		val select = (listOf("rowid") + t.fields.map { "`${it.column}`" }).joinToString()
		val row = db.rows("SELECT $select FROM `${t.table}` WHERE uid = ?", uid).firstOrNull() ?: return null
		val out = LinkedHashMap<String, JsonElement>()
		t.fields.forEachIndexed { i, f ->
			val value = row[i + 1]
			out[f.name] = if (f.kind == WireKind.REF) {
				SyncMapping.toWire(f, value?.let { uidOf(f.ref!!, it) })
			} else {
				SyncMapping.toWire(f, value)
			}
		}
		for (l in t.lists) {
			val values = db.rows("SELECT `${l.valueColumn}` FROM `${l.table}` WHERE `${l.parentColumn}` = (SELECT id FROM `${t.table}` WHERE rowid = ?)", row[0])
				.map { it[0] as String }
			out[l.name] = SyncMapping.list(values)
		}
		return JsonObject(out)
	}

	/*
	 * Sets the given fields of the record, creating it when missing (then
	 * every required field must be there). Unknown fields are skipped: a newer
	 * server may send more.
	 */
	fun write(type: String, uid: String, fields: JsonObject) {
		val t = typeOf(type)
		val columns = LinkedHashMap<String, Any?>()
		for ((name, value) in fields) {
			val f = t.byName[name] ?: continue
			val v = SyncMapping.fromWire(f, value)
			columns[f.column] = if (f.kind == WireKind.REF && v != null) idOf(f.ref!!, f.name, v as String) else v
		}
		val exists = db.rows("SELECT 1 FROM `${t.table}` WHERE uid = ?", uid).isNotEmpty()
		if (exists) {
			if (columns.isNotEmpty())
				db.exec(
					"UPDATE `${t.table}` SET ${columns.keys.joinToString { "`$it` = ?" }} WHERE uid = ?",
					*columns.values.toTypedArray(), uid,
				)
		} else {
			val missing = t.fields.filter { !it.nullable && it.column !in columns }.map { it.name }
			if (missing.isNotEmpty()) throw WireException("$type $uid: missing ${missing.joinToString()}")
			val all = LinkedHashMap<String, Any?>(t.localDefaults)
			all.putAll(columns)
			all["uid"] = uid
			db.exec(
				"INSERT INTO `${t.table}` (${all.keys.joinToString { "`$it`" }}) VALUES (${all.keys.joinToString { "?" }})",
				*all.values.toTypedArray(),
			)
		}
		for (l in t.lists) {
			val value = fields[l.name] ?: continue
			val values = SyncMapping.listFromWire(l.name, value).distinct()
			val id = db.rows("SELECT id FROM `${t.table}` WHERE uid = ?", uid).first()[0]
			db.exec("DELETE FROM `${l.table}` WHERE `${l.parentColumn}` = ?", id)
			for (v in values) db.exec("INSERT INTO `${l.table}` (`${l.parentColumn}`, `${l.valueColumn}`) VALUES (?, ?)", id, v)
		}
	}

	/* removes the record; the database's cascades remove its children */
	fun delete(type: String, uid: String) {
		val t = typeOf(type)
		/*
		 * what a plugin sent for an entry is deleted on the management system
		 * at its next sync, as for an entry deleted by hand (issue #118)
		 */
		if (type == "entry")
			db.exec(
				"INSERT OR REPLACE INTO remote_deleted (company_id, plugin_id, remote_id, deleted_utc_ms) " +
					"SELECT t.company_id, r.plugin_id, r.remote_id, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) " +
					"FROM remote_record r JOIN time_entry t ON t.id = r.entry_id WHERE t.uid = ? AND t.company_id IS NOT NULL",
				uid,
			)
		db.exec("DELETE FROM `${t.table}` WHERE uid = ?", uid)
	}

	private fun table(type: String): RecordType = typeOf(type)

	private fun uidOf(type: String, id: Any): String? =
		db.rows("SELECT uid FROM `${table(type).table}` WHERE id = ?", id).firstOrNull()?.get(0) as String?

	private fun idOf(type: String, field: String, uid: String): Any =
		db.rows("SELECT id FROM `${table(type).table}` WHERE uid = ?", uid).firstOrNull()?.get(0) ?: throw UnknownRef(field, uid)
}
