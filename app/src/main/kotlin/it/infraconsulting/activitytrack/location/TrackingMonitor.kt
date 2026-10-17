/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.location

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.domain.geo.Movement
import it.infraconsulting.activitytrack.domain.geo.Stationarity
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.start.Arrival
import it.infraconsulting.activitytrack.domain.start.ArrivalAdvice
import it.infraconsulting.activitytrack.notify.NotificationIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Evaluates the samples after every fix (SPEC §5): movement away from the
 * anchor during WORK, a stop during TRAVEL. Each departure or arrival is
 * notified once; "ignore" restarts the movement anchor from that moment.
 * The per-segment state lives in preferences and is pruned to the open
 * segments at every pass.
 */
class TrackingMonitor(private val context: Context, private val container: AppContainer) {
	private val prefs = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)
	private val mutex = Mutex()

	suspend fun evaluate() = mutex.withLock {
		val settings = container.settings.current()
		val open = container.locations.activeOpenSegments()
		prune(open.map { it.id }.toSet())
		for (seg in open) {
			when (seg.kind) {
				SegmentKind.WORK -> checkMovement(seg, settings.movementThresholdM.toDouble())
				SegmentKind.TRAVEL -> checkArrival(seg, settings.stationaryRadiusM.toDouble(), settings.stationaryMin * 60_000L)
				SegmentKind.PAUSE -> {}
			}
		}
	}

	/* "Ignore": movement is measured again from a new anchor taken after now. */
	fun ignoreMovement(segmentId: Long, nowMs: Long) {
		prefs.edit { putLong(IGNORED + segmentId, nowMs) }
	}

	private suspend fun checkMovement(seg: SegmentEntity, thresholdM: Double) {
		val since = prefs.getLong(IGNORED + seg.id, Long.MIN_VALUE)
		val fixes = container.locations.fixesOfSegment(seg.id).filter { it.atMs > since }
		val departure = Movement.departure(fixes, thresholdM) ?: return
		if (prefs.getLong(MOVED + seg.id, Long.MIN_VALUE) == departure)
			return
		prefs.edit { putLong(MOVED + seg.id, departure) }
		notify(seg.entryId, NotificationIds.MOVEMENT) { title ->
			TrackingNotifications.movement(context, seg.entryId, title, seg.id, departure)
		}
	}

	private suspend fun checkArrival(seg: SegmentEntity, radiusM: Double, windowMs: Long) {
		val fixes = container.locations.fixesOfSegment(seg.id)
		val arrival = Stationarity.arrival(fixes, radiusM, windowMs) ?: return
		if (prefs.getLong(ARRIVED + seg.id, Long.MIN_VALUE) == arrival)
			return
		prefs.edit { putLong(ARRIVED + seg.id, arrival) }
		val entry = container.entries.find(seg.entryId) ?: return
		val stop = fixes.first { it.atMs == arrival }.point
		val proposal = ArrivalAdvice.propose(stop, container.masterData.knownSites(), entry.clientId)
		val clientName = (proposal as? Arrival.AtClient)
			?.let { container.masterData.client(it.clientId)?.name }
		notify(seg.entryId, NotificationIds.ARRIVAL) { title ->
			TrackingNotifications.arrival(context, seg.entryId, title, proposal, clientName, arrival)
		}
	}

	private suspend fun notify(entryId: Long, slot: Int, build: (String) -> android.app.Notification) {
		val nm = context.getSystemService(NotificationManager::class.java)
		if (!nm.areNotificationsEnabled())
			return
		nm.notify(NotificationIds.of(entryId, slot), build(title(entryId)))
	}

	/* Client name, else company name, else the app name (as the ongoing notification). */
	private suspend fun title(entryId: Long): String {
		val e = container.entries.find(entryId)
		return e?.clientId?.let { container.masterData.client(it)?.name }
			?: e?.companyId?.let { container.masterData.company(it)?.name }
			?: context.getString(R.string.app_name)
	}

	private fun prune(open: Set<Long>) {
		val stale = prefs.all.keys.filter { key ->
			val id = key.substringAfter('_').toLongOrNull()
			id == null || id !in open
		}
		if (stale.isNotEmpty())
			prefs.edit { stale.forEach { remove(it) } }
	}

	private companion object {
		const val IGNORED = "ignored_"
		const val MOVED = "moved_"
		const val ARRIVED = "arrived_"
	}
}
