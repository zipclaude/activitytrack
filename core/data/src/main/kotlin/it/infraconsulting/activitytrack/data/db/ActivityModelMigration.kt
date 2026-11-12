/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import it.infraconsulting.activitytrack.domain.activity.ActivityMigration
import it.infraconsulting.activitytrack.domain.activity.ActivityRow
import it.infraconsulting.activitytrack.domain.activity.LegacyAbsence
import it.infraconsulting.activitytrack.domain.activity.LegacyData
import it.infraconsulting.activitytrack.domain.activity.LegacyEntry
import it.infraconsulting.activitytrack.domain.activity.LegacyRef
import it.infraconsulting.activitytrack.domain.activity.LegacySegment
import it.infraconsulting.activitytrack.domain.activity.LegacyTodo
import it.infraconsulting.activitytrack.domain.activity.MigrationResult
import it.infraconsulting.activitytrack.domain.activity.Priority
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * DB 21 -> 22, activity model 1.0 (docs/ACTIVITY-MODEL.md 6): plain SQL
 * behind SyncSchema.Sql, so the JVM tests run the very statements Room runs.
 * Room runs a migration in one transaction with the foreign keys off (its
 * own table rebuilds rely on it and check them afterwards, as done here).
 *
 * 1. the DB 21 rows are read into LegacyData and ActivityMigration.migrate
 *    computes the activities, the new ABSENCE segments and references;
 * 2. time_entry is rebuilt with the schema-22 columns (todo_id and its
 *    foreign key go), every row keeping its id, so the children keep
 *    pointing at it; segment gains planned;
 * 3. the result is written: existing entries updated, new activities
 *    inserted, their children found by uid;
 * 4. todo and absence are dropped;
 * 5. the sync is told to realign (6): the shadows are of wire 2 and go,
 *    the server migrated its own copy with the same function, so the next
 *    sync pulls everything; the server's version wins except for the
 *    records with changes not synced yet (and what the migration derived
 *    from them), which stay logged and are pushed, field by field against
 *    their shadows translated to wire 3; the triggers the rebuild dropped
 *    come back.
 */
object ActivityModelMigration {
	/* the schema-22 table, as Room exported it (22.json) */
	private const val ENTRY_TABLE = "CREATE TABLE IF NOT EXISTS `%s` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT, " +
		"`contact_id` INTEGER, `company_id` INTEGER, `client_id` INTEGER, `role_id` INTEGER, `activity` TEXT, `status` TEXT NOT NULL, " +
		"`mode` TEXT, `locality` TEXT, `location_kind` TEXT, `notes` TEXT, `zone_id` TEXT NOT NULL, `last_confirmed_utc_ms` INTEGER, " +
		"`unanswered_reminders` INTEGER NOT NULL, `planned_end_utc_ms` INTEGER, `plugin_fields` TEXT, `created_utc_ms` INTEGER NOT NULL, " +
		"`updated_utc_ms` INTEGER NOT NULL, `needs_review` INTEGER NOT NULL DEFAULT 0, `kind` TEXT NOT NULL DEFAULT 'ACTIVITY', " +
		"`deleted_utc_ms` INTEGER, `closed_utc_ms` INTEGER, `external_key` TEXT, `due_epoch_day` INTEGER, `due_time_min` INTEGER, " +
		"`plan_epoch_day` INTEGER, `plan_start_min` INTEGER, `duration_min` INTEGER, `plan_reminded_utc_ms` INTEGER, " +
		"`priority` INTEGER NOT NULL DEFAULT 0, `absence_kind` TEXT, " +
		"FOREIGN KEY(`company_id`) REFERENCES `company`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
		"FOREIGN KEY(`client_id`) REFERENCES `client`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
		"FOREIGN KEY(`role_id`) REFERENCES `role`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
		"FOREIGN KEY(`contact_id`) REFERENCES `contact`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"

	private val ENTRY_INDEXES = listOf(
		"CREATE INDEX IF NOT EXISTS `index_time_entry_company_id` ON `time_entry` (`company_id`)",
		"CREATE INDEX IF NOT EXISTS `index_time_entry_client_id` ON `time_entry` (`client_id`)",
		"CREATE INDEX IF NOT EXISTS `index_time_entry_role_id` ON `time_entry` (`role_id`)",
		"CREATE INDEX IF NOT EXISTS `index_time_entry_status` ON `time_entry` (`status`)",
		"CREATE INDEX IF NOT EXISTS `index_time_entry_contact_id` ON `time_entry` (`contact_id`)",
		"CREATE UNIQUE INDEX IF NOT EXISTS `index_time_entry_uid` ON `time_entry` (`uid`)",
		"CREATE UNIQUE INDEX IF NOT EXISTS `index_time_entry_company_id_external_key` ON `time_entry` (`company_id`, `external_key`)",
		"CREATE INDEX IF NOT EXISTS `index_time_entry_plan_epoch_day` ON `time_entry` (`plan_epoch_day`)",
	)

	/* the DB 21 columns that stay as they are */
	private val KEPT = listOf(
		"id", "uid", "contact_id", "company_id", "client_id", "role_id", "activity", "status", "mode", "locality", "location_kind",
		"notes", "zone_id", "last_confirmed_utc_ms", "unanswered_reminders", "planned_end_utc_ms", "plugin_fields",
		"created_utc_ms", "updated_utc_ms", "needs_review",
	)

	/* the columns of ActivityRow, in this order (see values()) */
	private val ROW_COLUMNS = listOf(
		"uid", "kind", "status", "mode", "closed_utc_ms", "deleted_utc_ms", "company_id", "client_id", "activity", "notes",
		"plugin_fields", "external_key", "due_epoch_day", "due_time_min", "plan_epoch_day", "plan_start_min", "duration_min",
		"plan_reminded_utc_ms", "priority", "absence_kind", "zone_id", "created_utc_ms", "updated_utc_ms",
	)

	fun migrate21To22(db: SyncSchema.Sql, zone: ZoneId) {
		db.exec("UPDATE sync_state SET applying = 1 WHERE id = 1")
		ensureUids(db)
		val legacy = read(db, zone)
		val result = ActivityMigration.migrate(legacy)
		/* what this node changed and did not sync yet: it stays to push (no data loss on the realignment) */
		val dirty = dirty(pending(db), legacy, result)

		/*
		 * The sync triggers go for the time of the migration: those of entry_tag
		 * read time_entry, and SQLite refuses to rename a table while a trigger
		 * names one that is missing; SyncSchema.onCreate puts them all back.
		 */
		for (r in db.rows("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync\\_%' ESCAPE '\\'"))
			db.exec("DROP TRIGGER IF EXISTS `${r[0]}`")
		db.exec("ALTER TABLE `segment` ADD COLUMN `planned` INTEGER NOT NULL DEFAULT 0")
		db.exec(ENTRY_TABLE.format("_new_time_entry"))
		val kept = KEPT.joinToString { "`$it`" }
		db.exec("INSERT INTO `_new_time_entry` ($kept) SELECT $kept FROM `time_entry`")
		db.exec("DROP TABLE `time_entry`")
		db.exec("ALTER TABLE `_new_time_entry` RENAME TO `time_entry`")
		ENTRY_INDEXES.forEach { db.exec(it) }

		val set = ROW_COLUMNS.drop(1).joinToString { "`$it` = ?" }
		val insert = (ROW_COLUMNS + listOf("unanswered_reminders", "needs_review")).joinToString { "`$it`" }
		val marks = (ROW_COLUMNS.map { "?" } + listOf("0", "0")).joinToString()
		for (a in result.activities) {
			val v = values(a)
			if (a.id != null)
				db.exec("UPDATE `time_entry` SET $set WHERE id = ?", *v.drop(1).toTypedArray(), a.id)
			else
				db.exec("INSERT INTO `time_entry` ($insert) VALUES ($marks)", *v.toTypedArray())
		}
		val idOf = HashMap<String, Long>()
		for (r in db.rows("SELECT id, uid FROM `time_entry` WHERE uid IS NOT NULL"))
			idOf[r[1] as String] = (r[0] as Number).toLong()
		for (s in result.segments)
			db.exec(
				"INSERT INTO `segment` (entry_id, kind, start_utc_ms, end_utc_ms, zone_id, source, uid, planned) VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, 0)",
				idOf.getValue(s.entryUid), s.kind.name, s.startUtcMs, s.endUtcMs, s.zoneId, s.uid,
			)
		for (r in result.refs)
			db.exec("INSERT INTO `external_ref` (entry_id, kind, value, uid) VALUES (?, ?, ?, ?)", idOf.getValue(r.entryUid), r.kind.name, r.value, r.uid)

		db.exec("DROP TABLE IF EXISTS `todo`")
		db.exec("DROP TABLE IF EXISTS `absence`")

		val broken = db.rows("PRAGMA foreign_key_check")
		check(broken.isEmpty()) { "migration 21 -> 22: foreign keys broken: $broken" }

		/* what the migration made from scratch: the realignment drops what the server's migration did not make */
		val written = result.activities.filter { it.id == null }.map { "entry" to it.uid } +
			result.segments.map { "segment" to it.uid } + result.refs.map { "externalRef" to it.uid }
		realign(db, dirty, written)
		db.exec("UPDATE sync_state SET applying = 0 WHERE id = 1")
		/* the triggers of the rebuilt table */
		SyncSchema.onCreate(db)
	}

	/*
	 * The next sync realigns with the server (6): a paired node pulls from
	 * the start, the server's version wins for every migrated record, except
	 * those with changes not synced yet ([dirty]: logged again under their
	 * 1.0 type, so the local version wins and is pushed); records the server
	 * never had are pushed too. The wire-2 shadows go, but those of the
	 * dirty records, translated to wire 3 ([baseShadows]): with them the
	 * pull keeps only the fields changed here, and the push sends only those,
	 * so a field edited elsewhere is not overwritten by a stale local value.
	 * A paired node remembers the records it [written] from scratch: those
	 * the server's migration did not make go after the pull (no ghosts).
	 */
	private fun realign(db: SyncSchema.Sql, dirty: List<Change>, written: List<Pair<String, String>>) {
		val bases = baseShadows(dirty) { type, uid ->
			db.rows("SELECT json FROM sync_shadow WHERE type = ? AND uid = ?", type, uid).firstOrNull()?.get(0) as String?
		}
		db.exec("ALTER TABLE `sync_state` ADD COLUMN `realign` INTEGER NOT NULL DEFAULT 0")
		db.exec("UPDATE sync_state SET realign = enabled, cursor = CASE WHEN enabled THEN 0 ELSE cursor END WHERE id = 1")
		db.exec("DELETE FROM sync_change")
		db.exec("DELETE FROM sync_shadow")
		db.exec("DELETE FROM sync_error")
		for (c in dirty)
			db.exec("INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES (?, ?, ?, ?)", c.type, c.uid, c.op, c.time)
		for ((key, json) in bases)
			db.exec("INSERT INTO sync_shadow (type, uid, json) VALUES (?, ?, ?)", key.first, key.second, json.toString())
		db.exec(SyncSchema.REALIGN_TABLE)
		db.exec("DELETE FROM sync_realign")
		if (db.rows("SELECT enabled FROM sync_state WHERE id = 1").firstOrNull()?.get(0).let { (it as Number?)?.toLong() ?: 0L } != 0L)
			for ((type, uid) in written)
				db.exec("INSERT OR IGNORE INTO sync_realign (type, uid) VALUES (?, ?)", type, uid)
	}

	/*
	 * What the server had of each dirty record before the migration, in
	 * wire 3: the base the realignment merges against (SyncStore.pulled and
	 * change compare each field with it). Only what is certain is kept: a
	 * field missing from the base counts as changed here, as before (the
	 * local value is pushed when it differs from the server's).
	 * - an entry: its own shadow, status mapped (6, step 1), todoUid gone,
	 *   kind ACTIVITY, not deleted;
	 * - a TODO activity with the uid of a task to-do: the to-do's shadow,
	 *   fields renamed as the migration maps them (6, step 3);
	 * - a segment: its shadow, not planned; the other types as they are;
	 * - deletions, absence to-dos and legacy absences: none.
	 */
	fun baseShadows(dirty: List<Change>, shadowOf: (String, String) -> String?): Map<Pair<String, String>, JsonObject> {
		fun parse(s: String?): JsonObject? = s?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
		val out = LinkedHashMap<Pair<String, String>, JsonObject>()
		for (c in dirty) {
			if (c.op == "delete")
				continue
			val base = when (c.type) {
				"todo", "absence" -> null
				"entry" -> parse(shadowOf("entry", c.uid))?.let(::entryBase) ?: parse(shadowOf("todo", c.uid))?.let(::todoBase)
				"segment" -> parse(shadowOf("segment", c.uid))?.let { JsonObject(it + ("planned" to JsonPrimitive(false))) }
				else -> parse(shadowOf(c.type, c.uid))
			}
			if (base != null)
				out[c.type to c.uid] = base
		}
		return out
	}

	private fun entryBase(s: JsonObject): JsonObject {
		val status = when (s["status"]?.jsonPrimitive?.contentOrNull) {
			"ACTIVE", "SUSPENDED" -> "RUNNING"
			null -> null
			else -> "CLOSED"
		}
		return JsonObject(
			buildMap {
				putAll(s - "todoUid" - "status")
				status?.let { put("status", JsonPrimitive(it)) }
				put("kind", JsonPrimitive("ACTIVITY"))
				put("deletedUtcMs", JsonNull)
			},
		)
	}

	private fun todoBase(s: JsonObject): JsonObject? {
		if (s["kind"]?.jsonPrimitive?.contentOrNull == "ABSENCE")
			return null
		val done = s["done"]?.jsonPrimitive?.booleanOrNull ?: return null
		return JsonObject(
			buildMap {
				for (k in listOf("companyUid", "clientUid", "externalKey", "notes", "dueDate", "dueTimeMin", "planDate", "planStartMin", "durationMin", "pluginFields", "createdUtcMs", "updatedUtcMs"))
					s[k]?.let { put(k, it) }
				s["title"]?.let { put("activity", it) }
				put("priority", JsonPrimitive(Priority.fromLegacy(s["priority"]?.jsonPrimitive?.intOrNull).value))
				put("kind", JsonPrimitive("ACTIVITY"))
				put("status", JsonPrimitive(if (done) "CLOSED" else "TODO"))
				put("closedUtcMs", if (done) s["doneUtcMs"]?.takeIf { it !is JsonNull } ?: s["updatedUtcMs"] ?: JsonNull else JsonNull)
				s["ignoredUtcMs"]?.let { put("deletedUtcMs", it) }
			},
		)
	}

	/* a pending change of the log: the record's last op, the time of its last change */
	data class Change(val type: String, val uid: String, val op: String, val time: Long)

	private fun pending(db: SyncSchema.Sql): List<Change> = db.rows(
		"SELECT c.type, c.uid, (SELECT op FROM sync_change l WHERE l.type = c.type AND l.uid = c.uid ORDER BY seq DESC LIMIT 1), MAX(c.changed_utc_ms) " +
			"FROM sync_change c GROUP BY c.type, c.uid ORDER BY MIN(c.seq)",
	).map { Change(it[0] as String, it[1] as String, it[2] as String, (it[3] as Number).toLong()) }

	/*
	 * The records that stay dirty after the migration: every pending change,
	 * a to-do's or a legacy absence's under the activity it became (type
	 * entry, its uid) or, absorbed, under the entries it was linked to; and
	 * what the migration derived from a dirty record (absence segments,
	 * references of a dirty activity or of a dirty to-do's entries).
	 */
	fun dirty(pending: List<Change>, d: LegacyData, r: MigrationResult): List<Change> {
		val out = LinkedHashMap<Pair<String, String>, Change>()
		fun add(c: Change) {
			out[c.type to c.uid] = c
		}
		val activities = r.activities.map { it.uid }.toSet()
		val dirtyActivities = HashMap<String, Change>()
		for (c in pending) {
			when (c.type) {
				"todo", "absence" -> {
					val todo = d.todos.firstOrNull { it.uid == c.uid }
					when {
						c.uid in activities -> dirtyActivities[c.uid] = c.copy(type = "entry")
						c.op == "delete" -> add(c.copy(type = "entry"))
						todo != null -> d.entries.filter { it.todoId == todo.id }.forEach { dirtyActivities[it.uid] = c.copy(type = "entry", uid = it.uid, op = "upsert") }
					}
				}
				"entry" -> dirtyActivities[c.uid] = c
				else -> add(c)
			}
		}
		for ((uid, c) in dirtyActivities) {
			add(c)
			if (c.op == "delete")
				continue
			r.segments.filter { it.entryUid == uid }.forEach { add(Change("segment", it.uid, "upsert", c.time)) }
			r.refs.filter { it.entryUid == uid }.forEach { add(Change("externalRef", it.uid, "upsert", c.time)) }
		}
		return out.values.toList()
	}

	/* every entry has had a uid since schema 16; one without gets one, the migration needs it */
	private fun ensureUids(db: SyncSchema.Sql) {
		for (r in db.rows("SELECT id FROM `time_entry` WHERE uid IS NULL OR uid = ''"))
			db.exec("UPDATE `time_entry` SET uid = ? WHERE id = ?", SyncIds.random(), r[0])
		for (r in db.rows("SELECT id FROM `todo` WHERE uid IS NULL OR uid = ''"))
			db.exec("UPDATE `todo` SET uid = ? WHERE id = ?", SyncIds.random(), r[0])
	}

	private fun values(a: ActivityRow): List<Any?> = listOf(
		a.uid, a.kind.name, a.status.name, a.mode?.name, a.closedUtcMs, a.deletedUtcMs, a.companyId, a.clientId, a.activity, a.notes,
		a.pluginFields, a.externalKey, a.dueEpochDay, a.dueTimeMin, a.planEpochDay, a.planStartMin, a.durationMin,
		a.planRemindedUtcMs, a.priority, a.absenceKind?.name, a.zoneId, a.createdUtcMs, a.updatedUtcMs,
	)

	/* The DB 21 rows the migration reads (column names of the Room schema 21). */
	fun read(db: SyncSchema.Sql, zone: ZoneId): LegacyData {
		fun Any?.long(): Long? = (this as Number?)?.toLong()
		fun Any?.int(): Int? = (this as Number?)?.toInt()
		val entries = db.rows(
			"SELECT id, uid, todo_id, status, company_id, client_id, activity, notes, plugin_fields, zone_id, created_utc_ms, updated_utc_ms FROM time_entry",
		).map {
			LegacyEntry(
				it[0].long()!!, it[1] as String, it[2].long(), it[3] as String, it[4].long(), it[5].long(), it[6] as String?, it[7] as String?,
				it[8] as String?, it[9] as String, it[10].long()!!, it[11].long()!!,
			)
		}
		val segments = db.rows("SELECT id, entry_id, kind, start_utc_ms, end_utc_ms FROM segment").map {
			LegacySegment(it[0].long()!!, it[1].long()!!, it[2] as String, it[3].long()!!, it[4].long())
		}
		val refs = db.rows("SELECT entry_id, value FROM external_ref").map { LegacyRef(it[0].long()!!, it[1] as String) }
		val todos = db.rows(
			"SELECT id, uid, kind, title, company_id, client_id, external_key, notes, refs, due_epoch_day, due_time_min, priority, done, " +
				"done_utc_ms, created_utc_ms, updated_utc_ms, plan_epoch_day, plan_start_min, duration_min, plan_reminded_utc_ms, " +
				"ignored_utc_ms, plugin_fields, plan_end_epoch_day FROM todo",
		).map {
			LegacyTodo(
				id = it[0].long()!!, uid = it[1] as String, kind = it[2] as String? ?: "TASK", title = it[3] as String? ?: "",
				companyId = it[4].long(), clientId = it[5].long(), externalKey = it[6] as String?, notes = it[7] as String?,
				refs = it[8] as String? ?: "", dueEpochDay = it[9].long(), dueTimeMin = it[10].int(), priority = it[11].int(),
				done = (it[12].long() ?: 0L) != 0L, doneUtcMs = it[13].long(), createdUtcMs = it[14].long()!!, updatedUtcMs = it[15].long()!!,
				planEpochDay = it[16].long(), planStartMin = it[17].int(), durationMin = it[18].int(), planRemindedUtcMs = it[19].long(),
				ignoredUtcMs = it[20].long(), pluginFields = it[21] as String?, planEndEpochDay = it[22].long(),
			)
		}
		val absences = db.rows("SELECT id, uid, date_epoch_day, kind, time_min FROM absence").map {
			LegacyAbsence(it[0].long()!!, it[1] as String?, it[2].long()!!, it[3] as String, it[4].int())
		}
		fun windows(table: String) = db.rows("SELECT day_of_week, start_min, end_min FROM `$table`").map { DayWindow(it[0].int()!!, it[1].int()!!, it[2].int()!!) }
		return LegacyData(
			entries = entries, segments = segments, refs = refs, todos = todos, absences = absences,
			schedule = windows("work_schedule"), lunchBreaks = windows("lunch_break"),
			daysOff = db.rows("SELECT date_epoch_day FROM day_off").map { LocalDate.ofEpochDay(it[0].long()!!) }.toSet(),
			zone = zone,
		)
	}
}
