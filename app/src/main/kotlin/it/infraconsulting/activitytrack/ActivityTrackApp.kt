/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import it.infraconsulting.activitytrack.domain.settings.UiLanguage
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
	val updates by lazy { it.infraconsulting.activitytrack.update.Updates(this) }
	val syncAccount by lazy { it.infraconsulting.activitytrack.sync.SyncAccount(this, secrets, container.sync, container.userAgent, container.appVersion) }

	/* integration plugins of the data folder and their OAuth logins (issue #48) */
	val plugins by lazy { it.infraconsulting.activitytrack.connect.plugin.PluginRegistry(this) }
	/* the required entry fields of the plugins, for the displayed state of the activities (issue #178) */
	val requiredFields by lazy { it.infraconsulting.activitytrack.connect.plugin.RequiredFields(this) }
	val oauth by lazy { it.infraconsulting.activitytrack.connect.plugin.OAuthPkce(this, secrets) }

	/* unsigned policy.json and plugins waiting for the user's confirmation */
	val trust by lazy { it.infraconsulting.activitytrack.policy.TrustPrompts() }

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
		/* the km of the recorded trips are computed again once (0.9.7), when the network allows */
		if (it.infraconsulting.activitytrack.location.GeoEnrichWorker.kmAgainDue(this))
			it.infraconsulting.activitytrack.location.GeoEnrichWorker.enqueue(this)
		/* ActivityTrack Web: periodic and after-change syncs while paired (issue #114) */
		it.infraconsulting.activitytrack.sync.SyncWorker.start(this, appScope)
		/* only admins and managers change central master data (issue #115) */
		appScope.launch {
			syncAccount.info.collect { info ->
				container.sync.canEditCentral = info == null || info.profile in it.infraconsulting.activitytrack.sync.SyncAccount.CENTRAL_EDITORS
			}
		}
		/*
		 * The interface language (issue #196): the policy's ui.language, else the user's choice (Settings, or the
		 * system's per-app language), else the system's; an unsupported system language gives the default English
		 * resources. Applied through the per-app language API, so the system settings show it too.
		 */
		val lm = getSystemService(android.app.LocaleManager::class.java)
		appScope.launch {
			/* a language picked in the system settings before the app had its own setting becomes the user's choice */
			container.settings.adoptLanguage(lm.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language.orEmpty())
			combine(container.settings.settings.map { it.language }, container.policies.current.map { it.ui.language }, ::Pair)
				.map { (user, policy) -> UiLanguage.effective(policy, user).orEmpty() }
				.distinctUntilChanged()
				.collect { tag ->
					if (lm.applicationLocales.toLanguageTags() != tag)
						lm.applicationLocales = android.os.LocaleList.forLanguageTags(tag)
				}
		}
		/* a language picked in the system settings is the user's choice, unless a policy forces one */
		registerReceiver(object : android.content.BroadcastReceiver() {
			override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
				val tag = lm.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language.orEmpty()
				appScope.launch {
					val forced = container.policies.current.value.ui.language?.takeIf { it in UiLanguage.SUPPORTED }
					if (forced != null) {
						/* the policy wins over the system's setting too */
						if (tag != forced)
							lm.applicationLocales = android.os.LocaleList.forLanguageTags(forced)
					} else if (container.settings.current().language != tag)
						container.settings.update { it.copy(language = tag) }
				}
			}
		}, android.content.IntentFilter(android.content.Intent.ACTION_APPLICATION_LOCALE_CHANGED), android.content.Context.RECEIVER_NOT_EXPORTED)
		/* a new policy.json in the data folder (issue #48) */
		appScope.launch { it.infraconsulting.activitytrack.policy.PolicyLoader.check(this@ActivityTrackApp) }
		/* the daily check of newer releases (SYNC.md §12.4), for an app not from a store */
		appScope.launch {
			container.settings.settings.map { it.updateCheck && updates.applies }.distinctUntilChanged()
				.collect { on -> it.infraconsulting.activitytrack.update.Updates.schedule(this@ActivityTrackApp, on) }
		}
		/* the backup follows its frequency and hour (issue #44) */
		appScope.launch {
			container.settings.settings.map { it.backupEveryHours to it.backupHour }.distinctUntilChanged()
				.collect { (every, hour) -> BackupWorker.schedule(this@ActivityTrackApp, every, hour) }
		}
	}
}
