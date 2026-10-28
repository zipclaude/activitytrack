/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.app.Application
import it.infraconsulting.activitytrack.backup.BackupWorker
import it.infraconsulting.activitytrack.calendar.CalendarSync
import it.infraconsulting.activitytrack.connect.SecretStore
import it.infraconsulting.activitytrack.location.TrackingMonitor
import it.infraconsulting.activitytrack.notify.NotificationChannels
import it.infraconsulting.activitytrack.notify.NotificationSync
import it.infraconsulting.activitytrack.quick.ClientShortcuts
import it.infraconsulting.activitytrack.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ActivityTrackApp : Application() {
	lateinit var container: AppContainer
		private set

	/* Process-wide scope for observers that live as long as the process. */
	val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	lateinit var notificationSync: NotificationSync
		private set
	lateinit var reminders: ReminderScheduler
		private set
	lateinit var tracking: TrackingMonitor
		private set

	lateinit var calendarSync: CalendarSync
		private set

	/* connector secrets, keystore-encrypted (M4) */
	val secrets: SecretStore by lazy { SecretStore(this) }

	/* pairing with ActivityTrack Web (issue #113) */
	val syncAccount by lazy { it.infraconsulting.activitytrack.sync.SyncAccount(this, secrets, container.sync, container.userAgent) }

	/* integration plugins of the data folder and their OAuth logins (issue #48) */
	val plugins by lazy { it.infraconsulting.activitytrack.connect.plugin.PluginRegistry(this) }
	val oauth by lazy { it.infraconsulting.activitytrack.connect.plugin.OAuthPkce(this, secrets) }

	/* plugins: send on closing, periodic download */
	lateinit var pluginWatcher: it.infraconsulting.activitytrack.connect.plugin.PluginWatcher
		private set

	override fun onCreate() {
		super.onCreate()
		container = AppContainer(this, appScope)
		NotificationChannels.register(this)
		notificationSync = NotificationSync(this, container, appScope).also { it.start() }
		reminders = ReminderScheduler(this, container).also { it.start(appScope) }
		tracking = TrackingMonitor(this, container)
		ClientShortcuts(this, container).start(appScope)
		it.infraconsulting.activitytrack.quick.QuickWidgets.start(this, container, appScope)
		calendarSync = CalendarSync(this, container).also { it.start(appScope) }
		pluginWatcher = it.infraconsulting.activitytrack.connect.plugin.PluginWatcher(this).also { it.start(appScope) }
		/* a UI language forced by the policy (ui.language, issue #48) */
		appScope.launch {
			container.policies.current.map { it.ui.language }.distinctUntilChanged().collect { lang ->
				if (lang != null) {
					val lm = getSystemService(android.app.LocaleManager::class.java)
					if (lm.applicationLocales.toLanguageTags() != lang)
						lm.applicationLocales = android.os.LocaleList.forLanguageTags(lang)
				}
			}
		}
		/* a new policy.json in the data folder (issue #48) */
		appScope.launch { it.infraconsulting.activitytrack.policy.PolicyLoader.check(this@ActivityTrackApp) }
		/* the backup follows its frequency and hour (issue #44) */
		appScope.launch {
			container.settings.settings.map { it.backupEveryHours to it.backupHour }.distinctUntilChanged()
				.collect { (every, hour) -> BackupWorker.schedule(this@ActivityTrackApp, every, hour) }
		}
	}
}
