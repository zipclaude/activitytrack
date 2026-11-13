/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.sync

import it.infraconsulting.activitytrack.data.db.SyncSchema
import it.infraconsulting.activitytrack.domain.activity.ActivityMigration
import it.infraconsulting.activitytrack.domain.sync.SyncIds
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
 * Who is the source of trust when the phone and the server migrated the same
 * to-do differently (issue #176, owner): the phone, or the server when a
 * policy signed by it is in force on this phone. Decided when the sync of
 * the realignment starts; a PC interface is never one (it drops its ghosts).
 */
enum class SyncAuthority { PHONE, SERVER }

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

	/* [realign]: the data were migrated (activity model 1.0, a restored older backup): the next sync realigns */
	class State(val enabled: Boolean, val cursor: Long, val lastSyncUtcMs: Long?, val lastError: String?, val realign: Boolean = false)

	fun state(): State {
		val r = db.rows("SELECT enabled, cursor, last_sync_utc_ms, last_error, realign FROM sync_state WHERE id = 1").firstOrNull()
			?: return State(false, 0, null, null)
		return State((r[0] as Long) != 0L, r[1] as Long, r[2] as Long?, r[3] as String?, (r[4] as Long) != 0L)
	}

	/* the realignment is done: the full pull went through and what the server lacks is logged */
	fun realigned() = db.exec("UPDATE sync_state SET realign = 0 WHERE id = 1")

	/*
	 * The realignment pushes the local version of every record still logged
	 * (changed before the migration and not synced, or since): its changes
	 * take the time of this sync, so they win over the server's migration,
	 * which stamped every field it wrote with its own time.
	 */
	fun restamp() = db.exec("UPDATE sync_change SET changed_utc_ms = ?", now())

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

	/* the changes to push, in the log's order; [only]: just those of these records (the realignment's first push, issue #176) */
	fun pending(limit: Int, only: Set<Pair<String, String>>? = null): List<Pending> = db.rows(
		"SELECT c.type, c.uid, (SELECT op FROM sync_change l WHERE l.type = c.type AND l.uid = c.uid ORDER BY seq DESC LIMIT 1), " +
			"MAX(c.changed_utc_ms), MAX(c.seq) FROM sync_change c GROUP BY c.type, c.uid ORDER BY MIN(c.seq)" + (if (only == null) " LIMIT ?" else ""),
		*(if (only == null) arrayOf<Any?>(limit) else emptyArray()),
	).map { Pending(it[0] as String, it[1] as String, it[2] as String, it[3] as Long, it[4] as Long) }
		.let { all -> if (only == null) all else all.filter { (it.type to it.uid) in only }.take(limit) }

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
	 * Never work (issue #176): an activity the server never had that has
	 * real segments, or such a segment, is not discarded; false then.
	 */
	fun discard(type: String, uid: String): Boolean {
		val shadow = shadow(type, uid)
		if (shadow == null && holdsWork(type, uid))
			return false
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
		return true
	}

	/* an activity with real segments, or a real segment: what discarding without a server version would lose */
	fun holdsWork(type: String, uid: String): Boolean = when (type) {
		"entry" -> db.rows("SELECT 1 FROM segment s JOIN time_entry t ON t.id = s.entry_id WHERE t.uid = ? AND s.planned = 0 LIMIT 1", uid).isNotEmpty()
		"segment" -> db.rows("SELECT 1 FROM segment WHERE uid = ? AND planned = 0", uid).isNotEmpty()
		else -> false
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
		/* the uid becomes file names and request paths: a plain token or nothing (security review 0.9.8, R2) */
		if (!SyncIds.isSafe(uid)) return "$type: uid refused (not a plain token)"
		/* the server has it: not a ghost of the migration (dropGhosts) */
		strike(type, uid)
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

	/*
	 * a record deleted on the server, or no longer this user's: removed here,
	 * without echo; a type this app does not know (todo, absence after 1.0)
	 * is skipped, as a pulled record of one is
	 */
	fun gone(type: String, uid: String) {
		if (type !in SyncMapping.byType) return
		strike(type, uid)
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

	private fun strike(type: String, uid: String) {
		db.exec("DELETE FROM sync_realign WHERE type = ? AND uid = ?", type, uid)
		db.exec("DELETE FROM sync_realign_dirty WHERE type = ? AND uid = ?", type, uid)
	}

	/*
	 * The realignment's pull from 0 is over: the records the 21 -> 22
	 * migration wrote that the server did not send are ghosts (its own
	 * migration made something else of the same data, e.g. a to-do absorbed
	 * by an entry on another phone). They go, without echo, unless a local
	 * change still holds them (that one is pushed); an activity also stays
	 * while something hangs from it. With the server as the source of trust
	 * ([authority], issue #176) a ghost goes even when a local change holds
	 * it: with that change, those of what hangs from it and what hangs from
	 * it; an activity whose change goes is named in [Ghosts.dropped] edits,
	 * for the notice. Then seed() pushes only what the server never had.
	 */
	fun dropGhosts(authority: SyncAuthority = SyncAuthority.PHONE): Ghosts {
		var dropped = 0
		val edits = ArrayList<String>()
		val order = listOf("segment", "externalRef", "entry")
		val left = db.rows("SELECT type, uid FROM sync_realign").map { it[0] as String to it[1] as String }
			.sortedBy { (type, _) -> order.indexOf(type).let { if (it < 0) order.size else it } }
		if (authority == SyncAuthority.SERVER) {
			val ghostEntries = left.filter { it.first == "entry" }.map { it.second }.toSet()
			for (uid in ghostEntries) {
				val children = childrenOf(uid)
				val changed = hasChanges("entry", uid) || children.any { (t, u) -> hasChanges(t, u) }
				if (!changed)
					continue
				edits += titleOf(uid)
				for ((t, u) in children) {
					dropChanges(t, u)
					dropShadow(t, u)
					db.exec("DELETE FROM sync_error WHERE type = ? AND uid = ?", t, u)
				}
				dropChanges("entry", uid)
				db.exec("DELETE FROM sync_error WHERE type = 'entry' AND uid = ?", uid)
				/* the children go with it (foreign keys) */
				applying { records.delete("entry", uid) }
				dropShadow("entry", uid)
				dropped++
			}
			for ((type, uid) in left)
				if (type != "entry" && type in SyncMapping.byType && hasChanges(type, uid) && records.read(type, uid) != null) {
					dropChanges(type, uid)
					applying { records.delete(type, uid) }
					dropShadow(type, uid)
					dropped++
				}
		}
		for ((type, uid) in left) {
			if (type !in SyncMapping.byType || hasChanges(type, uid) || (type == "entry" && hasChildren(uid)))
				continue
			if (records.read(type, uid) == null)
				continue
			applying { records.delete(type, uid) }
			dropShadow(type, uid)
			dropped++
		}
		db.exec("DELETE FROM sync_realign")
		return Ghosts(dropped, edits)
	}

	/* what dropGhosts did: how many records went, the names of the activities whose local change went with them */
	class Ghosts(val dropped: Int, val edits: List<String>)

	/* the records hanging from the activity [entryUid], as (type, uid) */
	private fun childrenOf(entryUid: String): List<Pair<String, String>> =
		SyncMapping.TYPES.filter { it.parent == "entryUid" }.flatMap { t ->
			db.rows("SELECT c.uid FROM `${t.table}` c JOIN time_entry e ON e.id = c.entry_id WHERE e.uid = ? AND c.uid IS NOT NULL", entryUid)
				.map { t.type to it[0] as String }
		}

	/* an activity as the user knows it: its description, else its key, else the start of its uid */
	private fun titleOf(entryUid: String): String {
		val r = db.rows("SELECT activity, external_key FROM time_entry WHERE uid = ?", entryUid).firstOrNull()
		return (r?.get(0) as String?)?.trim()?.ifEmpty { null } ?: (r?.get(1) as String?)?.trim()?.ifEmpty { null } ?: entryUid.take(8)
	}

	/*
	 * The realignment's pull from 0 begins (issue #176): the records with
	 * changes not synced are noted; those the pull does not bring are pushed
	 * whole (unsentBases). A pull resumed from a later cursor keeps the list.
	 */
	fun realignBegin() {
		db.exec(SyncSchema.REALIGN_DIRTY_TABLE)
		if (state().cursor != 0L)
			return
		db.exec("DELETE FROM sync_realign_dirty")
		db.exec("INSERT OR IGNORE INTO sync_realign_dirty (type, uid) SELECT DISTINCT type, uid FROM sync_change")
	}

	/*
	 * The realignment's pull is complete: a dirty record the server did not
	 * send has no version there to merge with (the server's migration made
	 * something else of it): its wire-2 base shadow goes, so the whole
	 * record is pushed, not a partial new one (issue #176, D). Returns how
	 * many went.
	 */
	fun unsentBases(): Int {
		var n = 0
		for (r in db.rows("SELECT type, uid FROM sync_realign_dirty")) {
			val (type, uid) = r[0] as String to r[1] as String
			if (shadow(type, uid) != null && hasChanges(type, uid)) {
				dropShadow(type, uid)
				n++
			}
		}
		db.exec("DELETE FROM sync_realign_dirty")
		return n
	}

	/*
	 * The local activity that holds the external key a pulled entry carries,
	 * when it is another one: the two nodes migrated the same to-do
	 * differently (issue #176). Null otherwise.
	 */
	fun clash(record: JsonObject): String? {
		if (record["type"]?.jsonPrimitive?.contentOrNull != "entry" || record["deleted"]?.jsonPrimitive?.booleanOrNull == true)
			return null
		val uid = record["uid"]?.jsonPrimitive?.contentOrNull ?: return null
		val f = record["fields"]?.jsonObject ?: return null
		val key = f["externalKey"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull ?: return null
		val company = f["companyUid"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull ?: return null
		return db.rows(
			"SELECT t.uid FROM time_entry t JOIN company c ON c.id = t.company_id WHERE c.uid = ? AND t.external_key = ? AND t.uid != ?",
			company, key, uid,
		).firstOrNull()?.get(0) as String?
	}

	/*
	 * Resolves a pulled entry [record] (S) that clashes on (company, external
	 * key) with the local activity [local] (L), with the realignment pull
	 * complete ([children]: the pulled records hanging from S, held with it).
	 * Issue #176, B:
	 * - PHONE: L keeps the key. S an empty shell (TODO, no real segment) is
	 *   not stored and its delete is pushed; S with work, or ticked (CLOSED),
	 *   is stored without the key, with a UUID reference to L, and the key's
	 *   removal is pushed.
	 * - SERVER: S is stored as it is; L loses the key, gets a UUID reference
	 *   to S, and is pushed as an ordinary activity.
	 * Returns the changes to push before the others (a delete, a key's
	 * removal), so the server's unique key never refuses the rest; and
	 * whether [children] are to be applied after it (false: S is not stored).
	 */
	fun resolveClash(record: JsonObject, local: String, children: List<JsonObject>, authority: SyncAuthority): Resolution {
		val uid = record["uid"]!!.jsonPrimitive.content
		val fields = record["fields"]!!.jsonObject
		strike("entry", uid)
		if (authority == SyncAuthority.SERVER) {
			applying { db.exec("UPDATE time_entry SET external_key = NULL WHERE uid = ?", local) }
			log("entry", local, "upsert")
			val err = pulled(record)
			if (err != null)
				return Resolution(emptySet(), true, err)
			link(local, uid)
			return Resolution(emptySet(), true, null)
		}
		val work = children.any { c ->
			c["type"]?.jsonPrimitive?.contentOrNull == "segment" && c["deleted"]?.jsonPrimitive?.booleanOrNull != true &&
				c["fields"]?.jsonObject?.get("planned")?.jsonPrimitive?.booleanOrNull != true
		}
		val ticked = fields["status"]?.jsonPrimitive?.contentOrNull == "CLOSED"
		if (!work && !ticked && fields["status"]?.jsonPrimitive?.contentOrNull == "TODO") {
			/* an empty shell: what the to-do became where nothing absorbed it; its delete goes first (its children go with it there) */
			putShadow("entry", uid, fields)
			log("entry", uid, "delete")
			return Resolution(setOf("entry" to uid), false, null)
		}
		/* stored as pulled (a local change of it kept), without the key */
		val err = pulled(JsonObject(record + ("fields" to JsonObject(fields + ("externalKey" to JsonNull)))))
		if (err != null)
			return Resolution(emptySet(), true, err)
		/* the server's version as the base: the key's removal is the change to push */
		putShadow("entry", uid, fields)
		log("entry", uid, "upsert")
		link(uid, local)
		return Resolution(setOf("entry" to uid), true, null)
	}

	class Resolution(val first: Set<Pair<String, String>>, val applyChildren: Boolean, val error: String?)

	/*
	 * A UUID reference from the activity [from] to [to], in the form the
	 * migration gives the other entries of an absorbed to-do (ACTIVITY-MODEL.md
	 * 6, step 3): a name-based uid, the same on every node; logged to push.
	 */
	private fun link(from: String, to: String) {
		val refUid = ActivityMigration.uid(to, "link/$from")
		if (db.rows("SELECT 1 FROM external_ref WHERE uid = ?", refUid).isNotEmpty())
			return
		val id = db.rows("SELECT id FROM time_entry WHERE uid = ?", from).firstOrNull()?.get(0) ?: return
		applying { db.exec("INSERT INTO external_ref (entry_id, kind, value, uid) VALUES (?, 'UUID', ?, ?)", id, to, refUid) }
		log("externalRef", refUid, "upsert")
	}

	/* a pulled record held aside counts as sent by the server: not a ghost, not a dirty record it lacks */
	fun sent(record: JsonObject) {
		val type = record["type"]?.jsonPrimitive?.contentOrNull ?: return
		val uid = record["uid"]?.jsonPrimitive?.contentOrNull ?: return
		strike(type, uid)
	}

	/* a pulled record that failed may succeed later in the pull (its parent came); not a record refused for good */
	fun retryable(record: JsonObject): Boolean {
		val type = record["type"]?.jsonPrimitive?.contentOrNull ?: return false
		val uid = record["uid"]?.jsonPrimitive?.contentOrNull ?: return false
		return type in SyncMapping.byType && SyncIds.isSafe(uid)
	}

	private fun hasChildren(entryUid: String): Boolean = db.rows(
		"SELECT 1 FROM time_entry t WHERE t.uid = ? AND (EXISTS (SELECT 1 FROM segment WHERE entry_id = t.id) " +
			"OR EXISTS (SELECT 1 FROM external_ref WHERE entry_id = t.id) OR EXISTS (SELECT 1 FROM expense WHERE entry_id = t.id) " +
			"OR EXISTS (SELECT 1 FROM attachment WHERE entry_id = t.id) OR EXISTS (SELECT 1 FROM entry_material WHERE entry_id = t.id))",
		entryUid,
	).isNotEmpty()

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
	 * phone the server does not have yet is logged for the push, parents
	 * first. The same after a migration of the data (ACTIVITY-MODEL.md 6):
	 * the pull from the start gave a shadow to every record the server has,
	 * the server's version won; the others go up.
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
		val WITH_CENTRAL = setOf("company", "companySite", "client", "clientSite", "contact", "role", "tag", "preset", "material")

		/* the unique indexes a central record could collide with */
		val NATURAL_KEYS: Map<String, List<List<String>>> = mapOf(
			"company" to listOf(listOf("externalCode"), listOf("name")),
			"client" to listOf(listOf("companyUid", "externalCode"), listOf("companyUid", "name")),
			"role" to listOf(listOf("name")),
			"tag" to listOf(listOf("name")),
		)
	}
}

