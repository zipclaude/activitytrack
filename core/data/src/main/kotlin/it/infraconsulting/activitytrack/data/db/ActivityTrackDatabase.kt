/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
	],
	version = 1,
	exportSchema = true,
)
abstract class ActivityTrackDatabase : RoomDatabase() {
	abstract fun entryDao(): EntryDao

	companion object {
		const val FILE_NAME = "activitytrack.db"

		/* The database lives in app-private storage; daily backups go to the user data directory. */
		fun build(context: Context): ActivityTrackDatabase =
			Room.databaseBuilder(context.applicationContext, ActivityTrackDatabase::class.java, FILE_NAME)
				.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
				.build()
	}
}
