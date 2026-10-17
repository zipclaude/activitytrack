/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.location

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.notify.EntryInfo
import it.infraconsulting.activitytrack.notify.EntryNotifications
import it.infraconsulting.activitytrack.notify.NotificationChannels
import it.infraconsulting.activitytrack.notify.NotificationIds
import kotlinx.coroutines.launch

/*
 * Foreground service of type location (A9, DESIGN §6.2), running while any
 * entry is ACTIVE, PAUSE included (owner decision 2026-10-17). Its
 * notification is the ongoing notification of one ACTIVE entry, so no extra
 * notification is shown and that one cannot be swiped away meanwhile.
 *
 * NotificationSync drives it through [sync]: the first ACTIVE entry starts
 * it, later changes reach the running instance in process (no repeated
 * background starts), and no ACTIVE entry stops it.
 */
class LocationService : Service() {
	private lateinit var tracker: LocationTracker
	private var fgsEntry: Long? = null
	private var openTravel: Set<Long> = emptySet()

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		val app = application as ActivityTrackApp
		/* the process scope: the stop fix of a travel may arrive after the service ended */
		tracker = LocationTracker(this) { fix, provider, segmentId ->
			app.appScope.launch { app.container.locations.record(fix, provider, segmentId) }
		}
		instance = this
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		return if (update()) START_STICKY else START_NOT_STICKY
	}

	override fun onDestroy() {
		instance = null
		tracker.stop()
		super.onDestroy()
	}

	/* Brings the service in line with [latest]; false when it stopped itself. */
	private fun update(): Boolean {
		val active = latest.filter { it.entry.status == EntryStatus.ACTIVE }
		val chosen = active.firstOrNull { it.id == fgsEntry } ?: active.firstOrNull()
		val id = chosen?.let { NotificationIds.of(it.id, NotificationIds.ONGOING) } ?: PLACEHOLDER_ID
		/* startForeground is due after startForegroundService even when there is nothing to track any more */
		val notification = chosen?.let { EntryNotifications.ongoing(this, it) } ?: placeholder()
		try {
			ServiceCompat.startForeground(this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
		} catch (e: Exception) {
			/* SecurityException: no while-in-use location access (started from the background) */
			Log.w(TAG, "cannot start in foreground", e)
			stop()
			return false
		}
		trackTravel(latest.flatMap { it.segments })
		if (chosen == null) {
			stop()
			return false
		}
		/* the previous entry left ACTIVE: its notification lost the service and must go */
		fgsEntry?.let { old -> if (old != chosen.id) cancelOngoing(old) }
		fgsEntry = chosen.id
		tracker.start()
		return true
	}

	/* High-accuracy fixes when a TRAVEL segment opens or closes (km and track ends). */
	private fun trackTravel(segments: List<SegmentEntity>) {
		val now = segments.filter { it.kind == SegmentKind.TRAVEL && it.endUtcMs == null }.map { it.id }.toSet()
		for (seg in now - openTravel)
			tracker.current(seg, highAccuracy = true)
		for (seg in openTravel - now)
			tracker.current(seg, highAccuracy = true)
		openTravel = now
	}

	private fun stop() {
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		fgsEntry?.let { cancelOngoing(it) }
		fgsEntry = null
		stopSelf()
	}

	private fun cancelOngoing(entryId: Long) {
		if (latest.none { it.id == entryId && it.entry.status == EntryStatus.ACTIVE })
			getSystemService(NotificationManager::class.java).cancel(NotificationIds.of(entryId, NotificationIds.ONGOING))
	}

	private fun placeholder(): Notification =
		Notification.Builder(this, NotificationChannels.ONGOING)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(getString(R.string.app_name))
			.build()

	companion object {
		private const val TAG = "LocationService"
		/* entry 0 slot 15: never used by an entry */
		private const val PLACEHOLDER_ID = 15

		private val main = Handler(Looper.getMainLooper())

		@Volatile
		private var latest: List<EntryInfo> = emptyList()

		/* main thread only */
		private var instance: LocationService? = null

		/* Called on every change of the open entries. */
		fun sync(context: Context, infos: List<EntryInfo>) {
			latest = infos
			val app = context.applicationContext
			main.post {
				val running = instance
				if (running != null) {
					running.update()
					return@post
				}
				if (infos.none { it.entry.status == EntryStatus.ACTIVE } || !LocationTracker.hasPermission(app))
					return@post
				try {
					app.startForegroundService(Intent(app, LocationService::class.java))
				} catch (e: Exception) {
					/* ForegroundServiceStartNotAllowedException from the background: tracking resumes on the next start */
					Log.w(TAG, "cannot start", e)
				}
			}
		}
	}
}
