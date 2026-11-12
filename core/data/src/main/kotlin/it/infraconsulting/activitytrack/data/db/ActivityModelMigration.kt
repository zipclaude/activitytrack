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
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import java.time.LocalDate
import java.time.ZoneId

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
 * 5. the sync is told to realign (6): its log and shadows are of wire 2,
 *    the server migrated its own copy with the same function, so the next
 *    sync pulls everything (the server's version wins) and pushes only what
 *    the server lacks; the triggers the rebuild dropped come back.
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
		val result = ActivityMigration.migrate(read(db, zone))

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

		realign(db)
		db.exec("UPDATE sync_state SET applying = 0 WHERE id = 1")
		/* the triggers of the rebuilt table */
		SyncSchema.onCreate(db)
	}

	/*
	 * The next sync realigns with the server (6): a paired node pulls from
	 * the start and pushes what the server lacks; what was logged before
	 * belongs to wire 2 and goes.
	 */
	private fun realign(db: SyncSchema.Sql) {
		db.exec("ALTER TABLE `sync_state` ADD COLUMN `realign` INTEGER NOT NULL DEFAULT 0")
		db.exec("UPDATE sync_state SET realign = enabled, cursor = CASE WHEN enabled THEN 0 ELSE cursor END WHERE id = 1")
		db.exec("DELETE FROM sync_change")
		db.exec("DELETE FROM sync_shadow")
		db.exec("DELETE FROM sync_error")
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
