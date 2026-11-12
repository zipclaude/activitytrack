/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import android.content.Context
import android.database.Cursor
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import it.infraconsulting.activitytrack.domain.todo.AbsenceSpan

@Database(
	entities = [
		CompanyEntity::class,
		CompanySiteEntity::class,
		ClientEntity::class,
		ClientSiteEntity::class,
		PlaceEntity::class,
		RoleEntity::class,
		TagEntity::class,
		ActivityPresetEntity::class,
		TimeEntryEntity::class,
		SegmentEntity::class,
		LocationSampleEntity::class,
		AttachmentEntity::class,
		ExpenseEntity::class,
		ExternalRefEntity::class,
		WorkScheduleEntity::class,
		LunchBreakEntity::class,
		DayOffEntity::class,
		EntryTagEntity::class,
		ContactEntity::class,
		ContactPhoneEntity::class,
		CallLogEntity::class,
		MaterialEntity::class,
		EntryMaterialEntity::class,
		PolicyRecordEntity::class,
		RemoteItemEntity::class,
		RemoteRecordEntity::class,
		RemoteDeletedEntity::class,
		SyncChangeEntity::class,
		SyncShadowEntity::class,
		SyncStateEntity::class,
		SyncErrorEntity::class,
	],
	version = ActivityTrackDatabase.VERSION,
	exportSchema = true,
	autoMigrations = [
		AutoMigration(from = 1, to = 2),	/* day_off */
		AutoMigration(from = 2, to = 3, spec = ActivityTrackDatabase.FillEntryUids::class),	/* M4 interchange */
		AutoMigration(from = 3, to = 4),	/* M9 planned to-dos */
		AutoMigration(from = 4, to = 5),	/* M10 ignored to-dos */
		AutoMigration(from = 5, to = 6),	/* M12 company and client colours */
		AutoMigration(from = 6, to = 7),	/* M15 entry tags */
		AutoMigration(from = 7, to = 8),	/* M16 list order */
		AutoMigration(from = 8, to = 9),	/* M22 planned end */
		AutoMigration(from = 9, to = 10),	/* issue #28 contacts, calls, pause notes */
		AutoMigration(from = 10, to = 11),	/* issue #41 materials; #44 contact e-mail, entry contact */
		AutoMigration(from = 12, to = 13),	/* issue #48: clients and to-dos without a company */
		AutoMigration(from = 11, to = 12),	/* issue #48 policy history; plugin lists and sent records, entry and to-do plugin fields, contact key */
		AutoMigration(from = 13, to = 14),	/* issue #48: remote records of deleted entries */
		AutoMigration(from = 14, to = 15, spec = ActivityTrackDatabase.FillDoneTimes::class),	/* issue #89: when a to-do was done */
		/* 15 -> 16 is MIGRATION_15_16: uids filled before their unique indexes, triggers (issue #111) */
		AutoMigration(from = 16, to = 17),	/* issue #116: attachment file on the server */
		AutoMigration(from = 17, to = 18),	/* issue #115: central master data */
		AutoMigration(from = 18, to = 19),	/* issue #119: entries to review, sync errors */
		AutoMigration(from = 19, to = 20),	/* issue #133: tags of the master data */
		AutoMigration(from = 20, to = 21, spec = ActivityTrackDatabase.AbsencesBecomeTodos::class),	/* issue #158: absences are to-dos */
		/* 21 -> 22 is MIGRATION_21_22: activity model 1.0, the to-do becomes a state of the activity */
	],
)
abstract class ActivityTrackDatabase : RoomDatabase() {
	abstract fun entryDao(): EntryDao
	abstract fun masterDataDao(): MasterDataDao
	abstract fun locationDao(): LocationDao
	abstract fun interchangeDao(): InterchangeDao
	abstract fun callDao(): CallDao
	abstract fun materialDao(): MaterialDao
	abstract fun policyDao(): PolicyDao
	abstract fun pluginDao(): PluginDao
	abstract fun syncDao(): SyncDao

	companion object {
		const val FILE_NAME = "activitytrack.db"

		/* bump together with an AutoMigration or Migration, and commit the new schema */
		const val VERSION = 22

		/* The database lives in app-private storage; daily backups go to the user data directory. */
		fun build(context: Context): ActivityTrackDatabase =
			Room.databaseBuilder(context.applicationContext, ActivityTrackDatabase::class.java, FILE_NAME)
				.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
				.addMigrations(MIGRATION_15_16, MIGRATION_21_22)
				.addCallback(Seed)
				.build()

		/* issue #111: sync identities on every synced table, the change log and its triggers */
		val MIGRATION_15_16 = object : Migration(15, 16) {
			override fun migrate(db: SupportSQLiteDatabase) = SyncSchema.migrate15To16(SupportSql(db))
		}

		/*
		 * Activity model 1.0 (docs/ACTIVITY-MODEL.md 6): the to-dos and absences
		 * become activities through the shared migration function, in the
		 * device's zone; also what a restored older backup goes through.
		 */
		val MIGRATION_21_22 = object : Migration(21, 22) {
			override fun migrate(db: SupportSQLiteDatabase) = ActivityModelMigration.migrate21To22(SupportSql(db), java.time.ZoneId.systemDefault())
		}
	}

	internal class SupportSql(private val db: SupportSQLiteDatabase) : SyncSchema.Sql {
		override fun exec(sql: String, vararg args: Any?) {
			if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
		}

		override fun rows(sql: String, vararg args: Any?): List<List<Any?>> = db.query(sql, args).use { c ->
			buildList {
				while (c.moveToNext())
					add(
						(0 until c.columnCount).map { i ->
							when (c.getType(i)) {
								Cursor.FIELD_TYPE_NULL -> null
								Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
								Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
								else -> c.getString(i)
							}
						},
					)
			}
		}
	}

	/* 2 -> 3: existing entries get a uid (32 random hex digits; new ones get a UUID). */
	class FillEntryUids : AutoMigrationSpec {
		override fun onPostMigrate(db: SupportSQLiteDatabase) {
			db.execSQL("UPDATE time_entry SET uid = lower(hex(randomblob(16))) WHERE uid IS NULL")
		}
	}

	/* 14 -> 15: the done to-dos take their last update as the time they were done (the best guess). */
	class FillDoneTimes : AutoMigrationSpec {
		override fun onPostMigrate(db: SupportSQLiteDatabase) {
			db.execSQL("UPDATE todo SET done_utc_ms = updated_utc_ms WHERE done != 0")
		}
	}

	/*
	 * 20 -> 21 (issue #158): the absences recorded so far become to-dos of kind ABSENCE, done, on
	 * their day: a full day stays a whole day (shown over the working hours of the day, or all of
	 * it), a late entry runs from the start of the day's working hours to the time, an early exit
	 * from the time to the end of the working hours. The sync triggers log the deletions and the
	 * new to-dos. Absences that arrive later from an older app stay in their table.
	 */
	class AbsencesBecomeTodos : AutoMigrationSpec {
		override fun onPostMigrate(db: SupportSQLiteDatabase) = Legacy.absencesBecomeTodos(SupportSql(db), System.currentTimeMillis())
	}

	object Legacy {
		/* the body of AbsencesBecomeTodos on plain SQL, so the JVM tests run it (20 -> 21 -> 22) */
		fun absencesBecomeTodos(db: SyncSchema.Sql, now: Long) {
			val intervals = HashMap<Int, MutableList<Pair<Int, Int>>>()
			for (r in db.rows("SELECT day_of_week, start_min, end_min FROM work_schedule"))
				intervals.getOrPut((r[0] as Number).toInt()) { mutableListOf() } += (r[1] as Number).toInt() to (r[2] as Number).toInt()
			for (r in db.rows("SELECT id, date_epoch_day, kind, time_min FROM absence")) {
				val id = r[0]
				val day = (r[1] as Number).toLong()
				val weekday = java.time.LocalDate.ofEpochDay(day).dayOfWeek.value
				val from = intervals[weekday]?.minOf { it.first } ?: 0
				val to = intervals[weekday]?.maxOf { it.second } ?: 1440
				val t = (r[3] as Number?)?.toInt()
				/* null: the time is outside the working hours, nothing was absent */
				val span = AbsenceSpan.Kind.of(r[2] as String)?.let { AbsenceSpan.of(it, t, from, to) }
				if (span != null)
					db.exec(
						"INSERT INTO todo (uid, title, refs, done, done_utc_ms, created_utc_ms, updated_utc_ms, plan_epoch_day, plan_start_min, duration_min, kind) " +
							"VALUES (?, '', '', 1, ?, ?, ?, ?, ?, ?, 'ABSENCE')",
						SyncIds.random(), now, now, now, day, span.startMin, span.durationMin,
					)
				db.exec("DELETE FROM absence WHERE id = ?", id)
			}
		}
	}

	/*
	 * Default lunch break Monday to Friday 12:30-14:00 (SPEC §5), editable
	 * afterwards; the sync state row and triggers (issue #111).
	 */
	object Seed : RoomDatabase.Callback() {
		override fun onCreate(db: SupportSQLiteDatabase) {
			SyncSchema.onCreate(SupportSql(db))
			for (day in 1..5)
				db.execSQL("INSERT INTO lunch_break (day_of_week, start_min, end_min, uid) VALUES ($day, 750, 840, '${SyncIds.random()}')")
		}

		override fun onOpen(db: SupportSQLiteDatabase) {
			SyncSchema.onOpen(SupportSql(db))
		}
	}
}
