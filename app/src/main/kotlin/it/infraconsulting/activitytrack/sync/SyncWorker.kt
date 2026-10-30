/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
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
import it.infraconsulting.activitytrack.data.sync.SyncEngine
import it.infraconsulting.activitytrack.data.sync.SyncFailed
import it.infraconsulting.activitytrack.data.sync.SyncRevoked
import it.infraconsulting.activitytrack.data.sync.SyncTransport
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/*
 * One sync with ActivityTrack Web (issue #114) in WorkManager: network
 * required, retried with backoff on network errors; a revoked phone stops.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val app = applicationContext as ActivityTrackApp
		val account = app.syncAccount
		val http = account.http() ?: return Result.success()
		val token = account.token() ?: return Result.success()
		if (account.revoked.value) return Result.success()
		/* the plugin holder may have moved (issue #118) */
		account.head()
		if (account.revoked.value) return Result.success()
		val transport = object : SyncTransport {
			override suspend fun send(method: String, path: String, body: String?): SyncTransport.Reply {
				val r = http.send(method, "api/v1/$path", body, token)
				return SyncTransport.Reply(r.status, r.body)
			}

			override suspend fun upload(path: String, bytes: ByteArray, mime: String, sha256: String): SyncTransport.Reply {
				val (status, body) = http.exchange("PUT", "api/v1/$path", bytes, mime, token, mapOf("X-Sha256" to sha256))
				return SyncTransport.Reply(status, body.toString(Charsets.UTF_8))
			}

			override suspend fun download(path: String): Pair<Int, ByteArray?> {
				val (status, body) = http.exchange("GET", "api/v1/$path", null, null, token)
				return status to body.takeIf { status == 200 }
			}
		}
		/* files come down on Wi-Fi (unmetered), or when an attachment was opened */
		val connectivity = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
		val downloads = inputData.getBoolean(FILES, false) || !connectivity.isActiveNetworkMetered
		return try {
			val r = SyncEngine(app.container.sync, transport, files = AttachmentFiles(app), downloads = downloads).sync()
			Log.i(TAG, "sync: ${r.pushed} pushed, ${r.pulled} pulled, ${r.errors.size} errors")
			r.errors.take(5).forEach { Log.w(TAG, it) }
			Result.success()
		} catch (e: SyncRevoked) {
			account.markRevoked()
			Result.success()
		} catch (e: IOException) {
			app.container.sync.run { it.failed("network: ${e.message}") }
			Result.retry()
		} catch (e: SyncFailed) {
			Result.retry()
		}
	}

	companion object {
		private const val TAG = "SyncWorker"
		private const val NOW = "sync-now"
		private const val PERIODIC = "sync-periodic"
		private const val FILES = "files"

		private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

		/* soon, once: after a change, at the app's start, on "Sync now" */
		fun now(context: Context, delaySec: Long = 0, files: Boolean = false) {
			val request = OneTimeWorkRequestBuilder<SyncWorker>()
				.setConstraints(network)
				.setInputData(androidx.work.workDataOf(FILES to files))
				.setInitialDelay(delaySec, TimeUnit.SECONDS)
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
				.build()
			/* a sync already waiting covers this change too */
			WorkManager.getInstance(context).enqueueUniqueWork(NOW, if (delaySec == 0L) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
		}

		/*
		 * While paired: every 15 minutes (WorkManager's minimum) and a few
		 * seconds after any local change; nothing once unpaired or revoked.
		 */
		@OptIn(FlowPreview::class)
		fun start(app: ActivityTrackApp, scope: CoroutineScope) {
			scope.launch {
				combine(app.syncAccount.info, app.syncAccount.revoked) { info, revoked -> info != null && !revoked }
					.distinctUntilChanged()
					.collect { active ->
						val wm = WorkManager.getInstance(app)
						if (active) {
							wm.enqueueUniquePeriodicWork(
								PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
								PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
							)
							now(app)
						} else {
							wm.cancelUniqueWork(PERIODIC)
							wm.cancelUniqueWork(NOW)
						}
					}
			}
			scope.launch {
				app.container.sync.observePending().debounce(3_000).filter { it > 0 && app.syncAccount.info.value != null }
					.collect { now(app, 5) }
			}
		}
	}
}
