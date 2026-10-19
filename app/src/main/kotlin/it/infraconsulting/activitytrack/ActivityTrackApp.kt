/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

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

	/* Antares login and tokens (public OAuth client, PKCE) */
	val antares: it.infraconsulting.activitytrack.connect.antares.AntaresAuth by lazy {
		it.infraconsulting.activitytrack.connect.antares.AntaresAuth(this, secrets)
	}

	override fun onCreate() {
		super.onCreate()
		container = AppContainer(this)
		NotificationChannels.register(this)
		notificationSync = NotificationSync(this, container, appScope).also { it.start() }
		reminders = ReminderScheduler(this, container).also { it.start(appScope) }
		tracking = TrackingMonitor(this, container)
		ClientShortcuts(this, container).start(appScope)
		calendarSync = CalendarSync(this, container).also { it.start(appScope) }
		BackupWorker.schedule(this)
	}
}
