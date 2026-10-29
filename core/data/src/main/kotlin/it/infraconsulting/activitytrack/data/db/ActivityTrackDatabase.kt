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

@Database(
	entities = [
		CompanyEntity::class,
		CompanySiteEntity::class,
		ClientEntity::class,
		ClientSiteEntity::class,
		PlaceEntity::class,
		RoleEntity::class,
		ActivityPresetEntity::class,
		TimeEntryEntity::class,
		SegmentEntity::class,
		LocationSampleEntity::class,
		AttachmentEntity::class,
		ExpenseEntity::class,
		ExternalRefEntity::class,
		AbsenceEntity::class,
		WorkScheduleEntity::class,
		LunchBreakEntity::class,
		DayOffEntity::class,
		TodoEntity::class,
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
	],
)
abstract class ActivityTrackDatabase : RoomDatabase() {
	abstract fun entryDao(): EntryDao
	abstract fun masterDataDao(): MasterDataDao
	abstract fun locationDao(): LocationDao
	abstract fun todoDao(): TodoDao
	abstract fun interchangeDao(): InterchangeDao
	abstract fun callDao(): CallDao
	abstract fun materialDao(): MaterialDao
	abstract fun policyDao(): PolicyDao
	abstract fun pluginDao(): PluginDao
	abstract fun syncDao(): SyncDao

	companion object {
		const val FILE_NAME = "activitytrack.db"

		/* bump together with an AutoMigration or Migration, and commit the new schema */
		const val VERSION = 17

		/* The database lives in app-private storage; daily backups go to the user data directory. */
		fun build(context: Context): ActivityTrackDatabase =
			Room.databaseBuilder(context.applicationContext, ActivityTrackDatabase::class.java, FILE_NAME)
				.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
				.addMigrations(MIGRATION_15_16)
				.addCallback(Seed)
				.build()

		/* issue #111: sync identities on every synced table, the change log and its triggers */
		val MIGRATION_15_16 = object : Migration(15, 16) {
			override fun migrate(db: SupportSQLiteDatabase) = SyncSchema.migrate15To16(SupportSql(db))
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
