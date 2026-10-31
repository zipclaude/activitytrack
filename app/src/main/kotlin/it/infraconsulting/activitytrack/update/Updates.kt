/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.update

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.update.Release
import it.infraconsulting.activitytrack.domain.update.UpdateCheck
import it.infraconsulting.activitytrack.notify.NotificationChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/*
 * Newer releases on GitHub (ActivityTrack Web SYNC.md §12.4): once a day,
 * for an app not installed from a store (the store updates it) and not a
 * debug build; a release found shows in Settings → Updates and, once per
 * version, as a notification that opens its page.
 */
class Updates(private val context: Context) {
	private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
	private val _available = MutableStateFlow(load())
	val available: StateFlow<Release?> = _available.asStateFlow()

	val checkedUtcMs: Long get() = prefs.getLong(CHECKED, 0)

	/* installed by a store, which keeps it up to date */
	val fromStore: Boolean by lazy {
		val installer = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
		installer != null && installer in STORES
	}

	private val version: String by lazy { (context.applicationContext as ActivityTrackApp).container.appVersion }

	/* debug builds go on by adb */
	val applies: Boolean get() = !fromStore && !version.endsWith("-debug")

	/* asks GitHub now; the newer release or null; throws on network errors */
	suspend fun check(betas: Boolean): Release? = withContext(Dispatchers.IO) {
		val c = URL(UpdateCheck.RELEASES).openConnection() as HttpURLConnection
		val body = try {
			c.connectTimeout = 15_000
			c.readTimeout = 30_000
			c.setRequestProperty("Accept", "application/vnd.github+json")
			c.setRequestProperty("User-Agent", "ActivityTrack/$version")
			if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
			c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
		} finally {
			c.disconnect()
		}
		val found = UpdateCheck.newer(body, version, betas)
		prefs.edit().putLong(CHECKED, System.currentTimeMillis())
			.putString(VERSION, found?.version).putString(URL_KEY, found?.url).putBoolean(BETA, found?.beta == true).apply()
		_available.value = found
		found
	}

	/* a notification the first time a version is found */
	fun notifyOnce(release: Release) {
		if (prefs.getString(NOTIFIED, null) == release.version) return
		prefs.edit().putString(NOTIFIED, release.version).apply()
		val open = PendingIntent.getActivity(
			context, 0, Intent(Intent.ACTION_VIEW, Uri.parse(release.url)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val text = context.getString(if (release.beta) R.string.update_text_beta else R.string.update_text, release.version)
		val n = Notification.Builder(context, NotificationChannels.UPDATES)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.update_title))
			.setContentText(text)
			.setContentIntent(open)
			.setAutoCancel(true)
			.build()
		context.getSystemService(NotificationManager::class.java).notify(NOTICE, n)
	}

	private fun load(): Release? {
		val v = prefs.getString(VERSION, null) ?: return null
		/* updated meanwhile: nothing to offer */
		val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("")
		if (it.infraconsulting.activitytrack.domain.update.Versions.compare(v, installed) <= 0) return null
		return Release(v, prefs.getString(URL_KEY, null).orEmpty(), prefs.getBoolean(BETA, false))
	}

	companion object {
		private const val CHECKED = "checked"
		private const val VERSION = "version"
		private const val URL_KEY = "url"
		private const val BETA = "beta"
		private const val NOTIFIED = "notified"
		private const val NOTICE = 7401
		private const val DAILY = "update-daily"
		private const val NOW = "update-now"

		/* installers that update the app themselves */
		private val STORES = setOf(
			"com.android.vending", "org.fdroid.fdroid", "org.fdroid.basic", "com.aurora.store", "dev.imranr.obtainium",
			"com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.amazon.venezia",
		)

		private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

		/* the daily check follows the setting (a policy may fix it) */
		fun schedule(context: Context, on: Boolean) {
			val wm = WorkManager.getInstance(context)
			if (on) wm.enqueueUniquePeriodicWork(
				DAILY, ExistingPeriodicWorkPolicy.KEEP,
				PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).setConstraints(network).build(),
			) else wm.cancelUniqueWork(DAILY)
		}

		/* "Check now" */
		fun now(context: Context) {
			WorkManager.getInstance(context).enqueueUniqueWork(
				NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(network).build(),
			)
		}
	}
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val app = applicationContext as ActivityTrackApp
		val settings = app.container.settings.settings.first()
		if (!settings.updateCheck || !app.updates.applies) return Result.success()
		return try {
			app.updates.check(settings.updateBetas)?.let { app.updates.notifyOnce(it) }
			Result.success()
		} catch (e: java.io.IOException) {
			Result.retry()
		}
	}
}
