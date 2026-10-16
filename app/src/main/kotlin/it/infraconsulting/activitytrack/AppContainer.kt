/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

import android.content.Context
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/*
 * Manual dependency injection (decision A15): one instance per process, owned
 * by [ActivityTrackApp]. Components are created lazily on first use.
 */
class AppContainer(context: Context) {
	private val appContext = context.applicationContext

	val clock: Clock = DefaultZoneClock

	val database: ActivityTrackDatabase by lazy { ActivityTrackDatabase.build(appContext) }
	val masterData: MasterDataRepository by lazy { MasterDataRepository(database) }
	val entries: EntryRepository by lazy { EntryRepository(database, clock) }
	val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
	val userDataDir: UserDataDir by lazy { UserDataDir(appContext) }

	/* System clock that follows time-zone changes (Clock.systemDefaultZone() captures the zone once). */
	private object DefaultZoneClock : Clock() {
		override fun getZone(): ZoneId = ZoneId.systemDefault()
		override fun withZone(zone: ZoneId): Clock = system(zone)
		override fun instant(): Instant = Instant.now()
	}
}
