/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

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
		RemotePortfolioEntity::class,
		RemoteActivityTypeEntity::class,
		RemoteActivityEntity::class,
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
		AutoMigration(from = 10, to = 11),	/* M24 Antares */
	],
)
abstract class ActivityTrackDatabase : RoomDatabase() {
	abstract fun entryDao(): EntryDao
	abstract fun masterDataDao(): MasterDataDao
	abstract fun locationDao(): LocationDao
	abstract fun todoDao(): TodoDao
	abstract fun interchangeDao(): InterchangeDao
	abstract fun callDao(): CallDao
	abstract fun remoteDao(): RemoteDao

	companion object {
		const val FILE_NAME = "activitytrack.db"

		/* bump together with an AutoMigration or Migration, and commit the new schema */
		const val VERSION = 11

		/* The database lives in app-private storage; daily backups go to the user data directory. */
		fun build(context: Context): ActivityTrackDatabase =
			Room.databaseBuilder(context.applicationContext, ActivityTrackDatabase::class.java, FILE_NAME)
				.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
				.addCallback(Seed)
				.build()
	}

	/* 2 -> 3: existing entries get a uid (32 random hex digits; new ones get a UUID). */
	class FillEntryUids : AutoMigrationSpec {
		override fun onPostMigrate(db: SupportSQLiteDatabase) {
			db.execSQL("UPDATE time_entry SET uid = lower(hex(randomblob(16))) WHERE uid IS NULL")
		}
	}

	/* Default lunch break Monday to Friday 12:30-14:00 (SPEC §5), editable afterwards. */
	object Seed : RoomDatabase.Callback() {
		override fun onCreate(db: SupportSQLiteDatabase) {
			for (day in 1..5)
				db.execSQL("INSERT INTO lunch_break (day_of_week, start_min, end_min) VALUES ($day, 750, 840)")
		}
	}
}
