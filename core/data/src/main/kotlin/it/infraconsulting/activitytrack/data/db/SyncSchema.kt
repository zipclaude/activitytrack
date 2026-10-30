/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import it.infraconsulting.activitytrack.domain.sync.SyncIds

/*
 * Sync identities and the change log (issue #111). Plain SQL behind a tiny
 * interface, so the JVM tests run the very statements Room runs.
 *
 * Triggers on every synced table append to sync_change while sync_state
 * says enabled and not applying; every write path (screens, widgets,
 * imports, plugins, restore) is caught without touching the repositories.
 * They are created with IF NOT EXISTS at every open too: a later migration
 * that rebuilds a table drops its triggers, the next open puts them back.
 */
object SyncSchema {
	interface Sql {
		fun exec(sql: String, vararg args: Any?)
		fun rows(sql: String, vararg args: Any?): List<List<Any?>>
	}

	/* table -> record type of the wire schema (ActivityTrack Web, SYNC.md §1) */
	val TYPES: Map<String, String> = linkedMapOf(
		"company" to "company",
		"company_site" to "companySite",
		"client" to "client",
		"client_site" to "clientSite",
		"contact" to "contact",
		"role" to "role",
		"tag" to "tag",
		"activity_preset" to "preset",
		"material" to "material",
		"place" to "place",
		"todo" to "todo",
		"time_entry" to "entry",
		"segment" to "segment",
		"attachment" to "attachment",
		"expense" to "expense",
		"entry_material" to "entryMaterial",
		"external_ref" to "externalRef",
		"absence" to "absence",
		"day_off" to "dayOff",
		"work_schedule" to "workSchedule",
		"lunch_break" to "lunchBreak",
		"remote_item" to "remoteItem",
	)

	/* rows folded into a parent record: table -> (parent table, column pointing to the parent's id) */
	val FOLDED: Map<String, Pair<String, String>> = linkedMapOf(
		"contact_phone" to ("contact" to "contact_id"),
		"entry_tag" to ("time_entry" to "entry_id"),
	)

	/* tables that got their uid in schema 16 (todo and time_entry had one) */
	/* tables added after schema 16: Room creates them with their uid */
	private val AFTER_16 = setOf("tag")

	private val NEW_UID = TYPES.keys - setOf("todo", "time_entry") - AFTER_16

	private const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
	private const val RECORDING = "(SELECT enabled AND NOT applying FROM sync_state WHERE id = 1)"

	/* a random version 4 UUID, evaluated per row */
	private const val UUID4 = "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
		"substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || " +
		"substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

	/* as Room creates them (schema 16) */
	private val TABLES = listOf(
		"CREATE TABLE IF NOT EXISTS `sync_change` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `uid` TEXT NOT NULL, `op` TEXT NOT NULL, `changed_utc_ms` INTEGER NOT NULL)",
		"CREATE INDEX IF NOT EXISTS `index_sync_change_type_uid` ON `sync_change` (`type`, `uid`)",
		"CREATE TABLE IF NOT EXISTS `sync_shadow` (`type` TEXT NOT NULL, `uid` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`type`, `uid`))",
		"CREATE TABLE IF NOT EXISTS `sync_state` (`id` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `applying` INTEGER NOT NULL, `server_url` TEXT, `cursor` INTEGER NOT NULL, `clock_offset_ms` INTEGER NOT NULL, `last_sync_utc_ms` INTEGER, `last_error` TEXT, PRIMARY KEY(`id`))",
	)

	private const val STATE_ROW = "INSERT OR IGNORE INTO sync_state (id, enabled, applying, cursor, clock_offset_ms) VALUES (1, 0, 0, 0, 0)"

	fun triggers(present: Set<String>? = null): List<String> = buildList {
		for ((table, type) in TYPES) {
			if (present != null && table !in present) continue
			add(log(table, "insert", "INSERT", "INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES ('$type', NEW.uid, 'upsert', $NOW)"))
			/* an update that also changes the uid is logged by keep_uid, under the uid kept */
			add(
				log(
					table, "update", "UPDATE",
					"INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES ('$type', coalesce(nullif(OLD.uid, ''), NEW.uid), 'upsert', $NOW)",
					"NEW.uid IS OLD.uid OR coalesce(OLD.uid, '') = ''",
				),
			)
			add(log(table, "delete", "DELETE", "INSERT INTO sync_change (type, uid, op, changed_utc_ms) VALUES ('$type', OLD.uid, 'delete', $NOW)"))
			/*
			 * A row rebuilt from scratch and saved over the old one (a new
			 * entity object with the old id) would take a new random uid:
			 * the old one is put back, unless the sync itself is writing.
			 */
			add(
				"CREATE TRIGGER IF NOT EXISTS `sync_${table}_keep_uid` AFTER UPDATE OF uid ON `$table` " +
					"WHEN coalesce(OLD.uid, '') != '' AND NEW.uid IS NOT OLD.uid " +
					"AND NOT coalesce((SELECT applying FROM sync_state WHERE id = 1), 0) " +
					"BEGIN UPDATE `$table` SET uid = OLD.uid WHERE rowid = NEW.rowid; " +
					"INSERT INTO sync_change (type, uid, op, changed_utc_ms) SELECT '$type', OLD.uid, 'upsert', $NOW WHERE $RECORDING; END",
			)
		}
		for ((table, parent) in FOLDED) {
			val (parentTable, column) = parent
			if (present != null && (table !in present || parentTable !in present)) continue
			val type = TYPES.getValue(parentTable)
			fun touch(row: String) = "INSERT INTO sync_change (type, uid, op, changed_utc_ms) " +
				"SELECT '$type', uid, 'upsert', $NOW FROM `$parentTable` WHERE id = $row.$column;"
			add(log(table, "insert", "INSERT", touch("NEW")))
			add(log(table, "update", "UPDATE", touch("OLD") + " " + touch("NEW")))
			add(log(table, "delete", "DELETE", touch("OLD")))
		}
	}

	private fun log(table: String, name: String, event: String, body: String, also: String? = null): String {
		val statement = body.trimEnd(';')
		val condition = if (also == null) RECORDING else "$RECORDING AND ($also)"
		return "CREATE TRIGGER IF NOT EXISTS `sync_${table}_$name` AFTER $event ON `$table` WHEN $condition BEGIN $statement; END"
	}

	/* a new database: Room made the tables */
	fun onCreate(db: Sql) {
		db.exec(STATE_ROW)
		/* a migration from an older schema runs before the newer tables exist: opening adds their triggers */
		val present = db.rows("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }.toSet()
		triggers(present).forEach { db.exec(it) }
	}

	/* every open: the triggers a rebuilt table lost come back */
	fun onOpen(db: Sql) {
		onCreate(db)
	}

	/* 15 -> 16 */
	fun migrate15To16(db: Sql) {
		for (table in NEW_UID)
			db.exec("ALTER TABLE `$table` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
		for (table in NEW_UID - setOf("day_off", "remote_item"))
			db.exec("UPDATE `$table` SET uid = $UUID4")
		for (row in db.rows("SELECT date_epoch_day FROM day_off")) {
			val day = (row[0] as Number).toLong()
			db.exec("UPDATE day_off SET uid = ? WHERE date_epoch_day = ?", SyncIds.dayOff(day), day)
		}
		/* after the companies got theirs */
		for (row in db.rows("SELECT r.rowid, c.uid, r.plugin_id, r.list, r.parent_id, r.id FROM remote_item r JOIN company c ON c.id = r.company_id")) {
			val uid = SyncIds.remoteItem(row[1] as String, row[2] as String, row[3] as String, row[4] as String, row[5] as String)
			db.exec("UPDATE remote_item SET uid = ? WHERE rowid = ?", uid, row[0])
		}
		/* entries of schema 3 have 32 hex digits, imported ones any key: canonical UUIDs */
		for (table in listOf("time_entry", "todo")) {
			val rows = db.rows("SELECT id, uid FROM `$table`")
			val taken = HashSet<String>()
			val changes = ArrayList<Pair<Any?, String>>()
			for (row in rows) {
				val old = row[1] as String?
				var uid = old?.let(SyncIds::canonical) ?: SyncIds.random()
				if (!taken.add(uid)) {
					uid = SyncIds.random()
					taken.add(uid)
				}
				if (uid != old) changes += row[0] to uid
			}
			/* through temporary values, so no step collides with a row not yet rewritten */
			for ((id, _) in changes) db.exec("UPDATE `$table` SET uid = ? WHERE id = ?", "tmp-$id", id)
			for ((id, uid) in changes) db.exec("UPDATE `$table` SET uid = ? WHERE id = ?", uid, id)
		}
		for (table in NEW_UID)
			db.exec("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uid` ON `$table` (`uid`)")
		TABLES.forEach { db.exec(it) }
		onCreate(db)
	}
}
