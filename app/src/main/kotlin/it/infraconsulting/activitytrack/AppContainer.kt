/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

import kotlinx.coroutines.flow.map
import android.content.Context
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.geo.Nominatim
import it.infraconsulting.activitytrack.data.geo.Osrm
import it.infraconsulting.activitytrack.data.interchange.Exporter
import it.infraconsulting.activitytrack.data.interchange.Importer
import it.infraconsulting.activitytrack.data.repo.AttachmentRepository
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.LocationRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.ReportRepository
import it.infraconsulting.activitytrack.data.repo.TodoRepository
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.data.storage.DatabaseBackup
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/*
 * Manual dependency injection (decision A15): one instance per process, owned
 * by [ActivityTrackApp]. Components are created lazily on first use.
 */
class AppContainer(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
	private val appContext = context.applicationContext

	val clock: Clock = DefaultZoneClock

	val database: ActivityTrackDatabase by lazy { ActivityTrackDatabase.build(appContext) }
	val masterData: MasterDataRepository by lazy { MasterDataRepository(database, settings.settings.map { it.listOrders }, clock) }
	val entries: EntryRepository by lazy { EntryRepository(database, clock) { policies.current.value.behaviours } }
	val calls: it.infraconsulting.activitytrack.data.repo.CallRepository by lazy { it.infraconsulting.activitytrack.data.repo.CallRepository(database) }
	val materials: it.infraconsulting.activitytrack.data.repo.MaterialRepository by lazy { it.infraconsulting.activitytrack.data.repo.MaterialRepository(database, clock) }
	val reports: ReportRepository by lazy { ReportRepository(database, clock) }
	val settings: SettingsRepository by lazy { SettingsRepository(appContext, policies.current) }
	/* policy.json (issue #48): the policy in force and its history */
	val pluginData: it.infraconsulting.activitytrack.data.repo.PluginRepository by lazy { it.infraconsulting.activitytrack.data.repo.PluginRepository(database) }
	val policies: it.infraconsulting.activitytrack.data.repo.PolicyRepository by lazy { it.infraconsulting.activitytrack.data.repo.PolicyRepository(database, clock, scope) }
	val userDataDir: UserDataDir by lazy { UserDataDir(appContext) }
	val backup: DatabaseBackup by lazy { DatabaseBackup(database, appContext.cacheDir) }
	val locations: LocationRepository by lazy { LocationRepository(database) }
	val sync: it.infraconsulting.activitytrack.data.repo.SyncRepository by lazy { it.infraconsulting.activitytrack.data.repo.SyncRepository(database) }
	val attachments: AttachmentRepository by lazy { AttachmentRepository(database, userDataDir, clock) }

	/* Nominatim policy: identify the application (no personal contact). */
	val userAgent: String by lazy {
		val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
		"ActivityTrack/$version (+https://github.com/zipclaude/activitytrack)"
	}
	val nominatim: Nominatim by lazy { Nominatim(userAgent) }
	val exporter: Exporter by lazy { Exporter(database) }
	val todos: TodoRepository by lazy { TodoRepository(database, clock) }
	val importer: Importer by lazy { Importer(database, clock) }

	/* "ActivityTrack/<version>" written into interchange files */
	val generator: String by lazy { userAgent.substringBefore(' ') }
	val osrm: Osrm by lazy { Osrm(userAgent) }

	/* System clock that follows time-zone changes (Clock.systemDefaultZone() captures the zone once). */
	private object DefaultZoneClock : Clock() {
		override fun getZone(): ZoneId = ZoneId.systemDefault()
		override fun withZone(zone: ZoneId): Clock = system(zone)
		override fun instant(): Instant = Instant.now()
	}
}
