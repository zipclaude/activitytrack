/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.sync

import it.infraconsulting.activitytrack.data.db.SyncSchema
import it.infraconsulting.activitytrack.domain.sync.SyncMapping
import it.infraconsulting.activitytrack.domain.sync.WireException
import it.infraconsulting.activitytrack.domain.sync.WireKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The phone's side of the sync (issue #114, server SYNC.md §2–§7), on the
 * database only: what to push, how to take the server's answers and
 * records. Every call runs inside the caller's transaction; the engine does
 * the network in between.
 */
class SyncStore(
	private val db: SyncSchema.Sql,
	private val now: () -> Long = System::currentTimeMillis,
	/* the user may change central master data (admin, manager); else their edits stay on this phone */
	private val canEditCentral: Boolean = true,
) {
	val records = SyncRecords(db)

	/* the changes of one record since the last push, collapsed */
	data class Pending(val type: String, val uid: String, val op: String, val time: Long, val maxSeq: Long)

	class State(val enabled: Boolean, val cursor: Long, val lastSyncUtcMs: Long?, val lastError: String?)

	fun state(): State {
		val r = db.rows("SELECT enabled, cursor, last_sync_utc_ms, last_error FROM sync_state WHERE id = 1").firstOrNull()
			?: return State(false, 0, null, null)
		return State((r[0] as Long) != 0L, r[1] as Long, r[2] as Long?, r[3] as String?)
	}

	fun setCursor(cursor: Long) = db.exec("UPDATE sync_state SET cursor = ? WHERE id = 1", cursor)

	fun finished(offsetMs: Long, error: String?) =
		db.exec("UPDATE sync_state SET clock_offset_ms = ?, last_sync_utc_ms = ?, last_error = ? WHERE id = 1", offsetMs, now(), error)

	fun failed(error: String) = db.exec("UPDATE sync_state SET last_error = ? WHERE id = 1", error)

	/* the change log records from now on (first sync, SYNC.md §7) */
	fun enable() = db.exec("UPDATE sync_state SET enabled = 1 WHERE id = 1")

	/* writes of the sync itself are not logged and may change uids (adoption) */
	fun <T> applying(block: () -> T): T {
		db.exec("UPDATE sync_state SET applying = 1 WHERE id = 1")
		try {
			return block()
		} finally {
			db.exec("UPDATE sync_state SET applying = 0 WHERE id = 1")
		}
	}

	fun pending(limit: Int): List<Pending> = db.rows(
		"SELECT c.type, c.uid, (SELECT op FROM sync_change l WHERE l.type = c.type AND l.uid = c.uid ORDER BY seq DESC LIMIT 1), " +
			"MAX(c.changed_utc_ms), MAX(c.seq) FROM sync_change c GROUP BY c.type, c.uid ORDER BY MIN(c.seq) LIMIT ?",
		limit,
	).map { Pending(it[0] as String, it[1] as String, it[2] as String, it[3] as Long, it[4] as Long) }

	fun shadow(type: String, uid: String): JsonObject? =
		db.rows("SELECT json FROM sync_shadow WHERE type = ? AND uid = ?", type, uid).firstOrNull()?.let { Json.parseToJsonElement(it[0] as String).jsonObject }

	private fun putShadow(type: String, uid: String, fields: JsonObject) =
		db.exec("INSERT OR REPLACE INTO sync_shadow (type, uid, json) VALUES (?, ?, ?)", type, uid, fields.toString())

	private fun dropShadow(type: String, uid: String) = db.exec("DELETE FROM sync_shadow WHERE type = ? AND uid = ?", type, uid)

	private fun dropChanges(type: String, uid: String, upToSeq: Long = Long.MAX_VALUE) =
		db.exec("DELETE FROM sync_change WHERE type = ? AND uid = ? AND seq <= ?", type, uid, upToSeq)

	private fun hasChanges(type: String, uid: String, afterSeq: Long = 0): Boolean =
		db.rows("SELECT 1 FROM sync_change WHERE type = ? AND uid = ? AND seq > ? LIMIT 1", type, uid, afterSeq).isNotEmpty()

	private fun log(type: String, uid: String, op: String) =
		db.exec("INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES (?, ?, ?, ?)", type, uid, op, now())

	private fun isCentral(type: String, uid: String): Boolean {
		val table = SyncMapping.byType.getValue(type).table
		return type in WITH_CENTRAL && (db.rows("SELECT central FROM `$table` WHERE uid = ?", uid).firstOrNull()?.get(0) as Long? ?: 0L) != 0L
	}

	private fun markCentral(type: String, uid: String, record: JsonObject) {
		if (type !in WITH_CENTRAL) return
		val central = record["central"]?.jsonPrimitive?.booleanOrNull ?: return
		db.exec("UPDATE `${SyncMapping.byType.getValue(type).table}` SET central = ? WHERE uid = ?", if (central) 1 else 0, uid)
	}

	/* the change to push for [p], or null when nothing differs from what the server has */
	fun change(p: Pending): JsonObject? {
		/* central data the user may not change: local only (the order of a list), the server's next version wins */
		if (!canEditCentral && isCentral(p.type, p.uid)) return null
		val shadow = shadow(p.type, p.uid)
		val current = if (p.op == "delete") null else records.read(p.type, p.uid)
		if (current == null) {
			/* never reached the server: nothing to delete there */
			if (shadow == null) return null
			return JsonObject(mapOf("type" to JsonPrimitive(p.type), "uid" to JsonPrimitive(p.uid), "op" to JsonPrimitive("delete"), "time" to JsonPrimitive(p.time)))
		}
		val t = SyncMapping.byType.getValue(p.type)
		val diff = current.filter { (k, v) -> t.byName[k]?.readOnly != true && shadow?.get(k) != v }
		if (diff.isEmpty()) return null
		return JsonObject(
			mapOf(
				"type" to JsonPrimitive(p.type),
				"uid" to JsonPrimitive(p.uid),
				"op" to JsonPrimitive("upsert"),
				"fields" to JsonObject(diff),
				"times" to JsonObject(diff.mapValues { JsonPrimitive(p.time) }),
			),
		)
	}

	/* the problems of the last sync (issue #119): one row per record */
	fun clearErrors() = db.exec("DELETE FROM sync_error")

	fun recordError(type: String, uid: String, message: String) =
		db.exec("INSERT OR REPLACE INTO sync_error (type, uid, message, at_utc_ms) VALUES (?, ?, ?, ?)", type, uid, message, now())

	/*
	 * Drops this phone's unsent changes of a record the server refuses: it
	 * goes back to what the server has (or away, if the server never had it).
	 */
	fun discard(type: String, uid: String) {
		val shadow = shadow(type, uid)
		applying {
			try {
				if (shadow != null) records.write(type, uid, shadow) else records.delete(type, uid)
			} catch (e: SyncRecords.UnknownRef) {
				records.delete(type, uid)
			}
		}
		dropChanges(type, uid)
		db.exec("DELETE FROM sync_error WHERE type = ? AND uid = ?", type, uid)
		/* the last problem shown in Settings was this one */
		db.exec("UPDATE sync_state SET last_error = NULL WHERE id = 1 AND NOT EXISTS (SELECT 1 FROM sync_error)")
	}

	/*
	 * Entries whose segments overlap after a merge are marked to review, and
	 * unmarked once fixed (issue #119, SYNC.md §11); the mark is synced.
	 */
	fun review() {
		val overlap = "SELECT DISTINCT a.entry_id FROM segment a JOIN segment b ON a.entry_id = b.entry_id AND a.id < b.id " +
			"WHERE a.start_utc_ms < coalesce(b.end_utc_ms, 9000000000000000000) AND b.start_utc_ms < coalesce(a.end_utc_ms, 9000000000000000000)"
		db.exec("UPDATE time_entry SET needs_review = 1 WHERE needs_review = 0 AND id IN ($overlap)")
		db.exec("UPDATE time_entry SET needs_review = 0 WHERE needs_review = 1 AND id NOT IN ($overlap)")
	}

	/* nothing to send for [p]: its log rows go */
	fun skip(p: Pending) = dropChanges(p.type, p.uid, p.maxSeq)

	/* the server's answer to a pushed change; returns the error it names, if any */
	fun pushed(p: Pending, result: JsonObject): String? {
		val status = result["status"]?.jsonPrimitive?.contentOrNull
		if (status != "applied" && status != "conflict") {
			val error = result["error"]?.jsonPrimitive?.contentOrNull ?: status ?: "error"
			val detail = result["detail"]?.jsonPrimitive?.contentOrNull.orEmpty()
			val message = "$error${if (detail.isEmpty()) "" else " ($detail)"}"
			recordError(p.type, p.uid, message)
			return "${p.type} ${p.uid}: $message"
		}
		val record = result["record"]?.takeIf { it !is JsonNull }?.jsonObject
		if (record == null || record["deleted"]?.jsonPrimitive?.booleanOrNull == true) {
			if (record != null) applying { records.delete(p.type, p.uid) }
			dropShadow(p.type, p.uid)
			dropChanges(p.type, p.uid)
			return null
		}
		val fields = record["fields"]?.jsonObject ?: return "${p.type} ${p.uid}: answer without fields"
		/* edited again while the push travelled: keep the new values, push them next */
		if (!hasChanges(p.type, p.uid, p.maxSeq)) applying { records.write(p.type, p.uid, fields) }
		applying { markCentral(p.type, p.uid, record) }
		putShadow(p.type, p.uid, fields)
		dropChanges(p.type, p.uid, p.maxSeq)
		return null
	}

	/* a pulled record; returns an error text when it cannot be applied (it is skipped) */
	fun pulled(record: JsonObject): String? {
		val type = record["type"]?.jsonPrimitive?.contentOrNull ?: return "record without type"
		val uid = record["uid"]?.jsonPrimitive?.contentOrNull ?: return "record without uid"
		if (type !in SyncMapping.byType) return null
		if (record["deleted"]?.jsonPrimitive?.booleanOrNull == true) {
			gone(type, uid)
			return null
		}
		val fields = record["fields"]?.jsonObject ?: return "$type $uid: no fields"
		return try {
			applying {
				val local = records.read(type, uid)
				if (local == null) {
					adopt(type, uid, fields)
					records.write(type, uid, fields)
				} else if (hasChanges(type, uid)) {
					/* fields edited here and not pushed yet stay; the push settles them */
					val base = shadow(type, uid)
					records.write(type, uid, JsonObject(fields.filter { (k, _) -> local[k] == base?.get(k) }))
				} else {
					records.write(type, uid, fields)
				}
				markCentral(type, uid, record)
			}
			putShadow(type, uid, fields)
			null
		} catch (e: SyncRecords.UnknownRef) {
			"$type $uid: ${e.message}"
		} catch (e: WireException) {
			"$type $uid: ${e.message}".also { recordError(type, uid, e.message.orEmpty()) }
		} catch (e: RuntimeException) {
			/* a unique index (a name already taken here) */
			"$type $uid: ${e.message}".also { recordError(type, uid, e.message.orEmpty()) }
		}
	}

	/* a record deleted on the server, or no longer this user's: removed here, without echo */
	fun gone(type: String, uid: String) {
		applying { records.delete(type, uid) }
		dropShadow(type, uid)
		dropChanges(type, uid)
	}

	/*
	 * A record arriving with the natural key of another one of this phone
	 * (SYNC.md §7): a central company of the same name, or the same user's
	 * from another phone. The local row takes the arriving uid, so every
	 * reference follows; the records pointing at it are pushed again before
	 * the local one, if the server had it, is deleted there.
	 */
	private fun adopt(type: String, uid: String, fields: JsonObject) {
		val keys = NATURAL_KEYS[type] ?: return
		val t = SyncMapping.byType.getValue(type)
		for (key in keys) {
			val values = key.map { name ->
				val f = t.byName.getValue(name)
				val v = fields[name] ?: return@map null
				if (v is JsonNull) return@map null
				if (f.kind == WireKind.REF) {
					val target = SyncMapping.byType.getValue(f.ref!!).table
					db.rows("SELECT id FROM `$target` WHERE uid = ?", (v as JsonPrimitive).content).firstOrNull()?.get(0)
				} else {
					SyncMapping.fromWire(f, v)
				}
			}
			if (values.any { it == null }) continue
			val where = key.joinToString(" AND ") { "`${t.byName.getValue(it).column}` = ?" }
			val row = db.rows("SELECT id, uid FROM `${t.table}` WHERE uid != ? AND $where", uid, *values.toTypedArray()).firstOrNull() ?: continue
			val (id, old) = row[0] to row[1] as String
			db.exec("UPDATE `${t.table}` SET uid = ? WHERE id = ?", uid, id)
			for (other in SyncMapping.TYPES) for (f in other.fields) if (f.kind == WireKind.REF && f.ref == type)
				db.rows("SELECT uid FROM `${other.table}` WHERE `${f.column}` = ?", id).forEach { log(other.type, it[0] as String, "upsert") }
			if (shadow(type, old) != null) log(type, old, "delete") else dropChanges(type, old)
			return
		}
	}

	/* an attachment's file to upload (issue #116) */
	class FileUp(val uid: String, val relPath: String, val mime: String)

	/* an attachment whose file is on the server and not here */
	class FileDown(val uid: String, val entryId: Long, val mime: String, val size: Long?)

	/* attachments the server knows (they have a shadow) whose file it has not yet */
	fun filesToUpload(): List<FileUp> = db.rows(
		"SELECT a.uid, a.rel_path, a.mime, s.json FROM attachment a JOIN sync_shadow s ON s.type = 'attachment' AND s.uid = a.uid WHERE a.rel_path != ''",
	).mapNotNull { r ->
		val has = Json.parseToJsonElement(r[3] as String).jsonObject["hasFile"]?.jsonPrimitive?.booleanOrNull == true
		if (has) null else FileUp(r[0] as String, r[1] as String, r[2] as String)
	}

	fun filesToDownload(): List<FileDown> =
		db.rows("SELECT uid, entry_id, mime, size FROM attachment WHERE rel_path = '' AND has_file = 1 ORDER BY created_utc_ms DESC")
			.map { FileDown(it[0] as String, it[1] as Long, it[2] as String, it[3] as Long?) }

	/* the server's attachment record after an upload */
	fun fileUploaded(record: JsonObject) {
		val uid = record["uid"]?.jsonPrimitive?.contentOrNull ?: return
		val fields = record["fields"]?.jsonObject ?: return
		applying { records.write("attachment", uid, fields) }
		putShadow("attachment", uid, fields)
	}

	/* a downloaded file is in the data folder at [relPath] */
	fun fileDownloaded(uid: String, relPath: String) = applying {
		db.exec("UPDATE attachment SET rel_path = ? WHERE uid = ?", relPath, uid)
	}

	/*
	 * First sync (SYNC.md §7), after pulling everything: every record of the
	 * phone the server does not have yet is logged for the push, parents first.
	 */
	fun seed() {
		val time = now()
		for (t in SyncMapping.TYPES)
			db.exec(
				"INSERT INTO sync_change (type, uid, op, changed_utc_ms) SELECT ?, uid, 'upsert', ? FROM `${t.table}` " +
					"WHERE uid IS NOT NULL AND uid NOT IN (SELECT uid FROM sync_shadow WHERE type = ?)",
				t.type, time, t.type,
			)
	}

	private companion object {
		/* master data that can be central (issue #115) */
		val WITH_CENTRAL = setOf("company", "companySite", "client", "clientSite", "contact", "role", "preset", "material")

		/* the unique indexes a central record could collide with */
		val NATURAL_KEYS: Map<String, List<List<String>>> = mapOf(
			"company" to listOf(listOf("externalCode"), listOf("name")),
			"client" to listOf(listOf("companyUid", "externalCode"), listOf("companyUid", "name")),
			"role" to listOf(listOf("name")),
		)
	}
}

