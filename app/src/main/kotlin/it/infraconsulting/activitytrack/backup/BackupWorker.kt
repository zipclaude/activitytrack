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

		/* Daily, battery not low; KEEP leaves an existing schedule untouched. */
		fun schedule(context: Context) {
			val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
				.setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
				.build()
			WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
		}

		fun runNow(context: Context) {
			WorkManager.getInstance(context)
				.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BackupWorker>().build())
		}
	}
}
