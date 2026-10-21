/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.backup

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.data.storage.DatabaseBackup
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/*
 * Daily backup (A16, DESIGN §8.2): snapshot zipped into <root>/backup,
 * keeping the newest N. Without a usable data folder it does nothing.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val c = (applicationContext as ActivityTrackApp).container
		val settings = c.settings.current()
		val tree = settings.rootUri?.toUri() ?: return Result.success()
		if (!c.userDataDir.isAccessible(tree))
			return Result.success()

		val now = Instant.now()
		val name = DatabaseBackup.fileName(now, ZoneId.systemDefault())
		val uri = try {
			c.userDataDir.createFile(tree, UserDataDir.Sub.BACKUP, name, "application/zip")
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "cannot create $name", e)
			return Result.retry()
		}
		try {
			c.userDataDir.openOutput(uri).use { c.backup.writeZip(appVersion(), now, it) }
		} catch (e: CancellationException) {
			c.userDataDir.delete(uri)
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "backup failed", e)
			c.userDataDir.delete(uri)
			return Result.retry()
		}

		val files = c.userDataDir.list(tree, UserDataDir.Sub.BACKUP)
		val expired = DatabaseBackup.expired(files.map { it.name }, settings.backupRetention).toSet()
		files.filter { it.name in expired }.forEach { c.userDataDir.delete(it.uri) }
		Log.i(TAG, "backup $name written, ${expired.size} expired removed")
		return Result.success()
	}

	private fun appVersion(): String =
		applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName.orEmpty()

	companion object {
		private const val TAG = "BackupWorker"
		private const val DAILY = "backup-daily"
		private const val NOW = "backup-now"

		/*
		 * Every [everyHours] from the next [hour] (issue #44), battery not low.
		 * A change of frequency or hour replaces the schedule; the same values
		 * leave the running one alone (no new first delay at every app start).
		 */
		fun schedule(context: Context, everyHours: Int, hour: Int) {
			val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
			val key = "$everyHours@$hour"
			val same = prefs.getString("schedule", null) == key
			val delay = it.infraconsulting.activitytrack.domain.backup.BackupSchedule.initialDelay(java.time.ZonedDateTime.now(), everyHours, hour)
			val request = PeriodicWorkRequestBuilder<BackupWorker>(everyHours.toLong(), TimeUnit.HOURS)
				.setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
				.setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
				.build()
			WorkManager.getInstance(context).enqueueUniquePeriodicWork(
				DAILY, if (same) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request,
			)
			prefs.edit().putString("schedule", key).apply()
		}

		fun runNow(context: Context) {
			WorkManager.getInstance(context)
				.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BackupWorker>().build())
		}
	}
}
