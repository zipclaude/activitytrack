/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.location

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.domain.geo.KmEstimate
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONException

/*
 * Network enrichment of recorded samples (DESIGN §6.3), never blocking the
 * tracking itself:
 * - km of closed TRAVEL segments: OSRM road distance per leg when enabled,
 *   else (or where no route exists) straight line times the factor;
 * - locality proposal for entries without one: Nominatim at the first WORK
 *   sample. Network failures retry the whole pass later.
 */
class GeoEnrichWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val c = (applicationContext as ActivityTrackApp).container
		val s = c.settings.current()
		val locations = c.locations
		try {
			for (seg in locations.travelWithoutKm()) {
				val fixes = locations.fixesOfSegment(seg.id)
				val road = if (s.osrm.enabled) c.osrm.legsM(s.osrm.endpoint, fixes.map { it.point }) else null
				locations.setKmEstimated(seg.id, KmEstimate.km(fixes, s.kmFactor, road ?: List(fixes.size - 1) { null }))
			}
			if (s.nominatim.enabled) {
				for (entryId in locations.entriesWithoutLocality()) {
					val p = locations.localityPoint(entryId) ?: continue
					c.nominatim.locality(s.nominatim.endpoint, p)
						?.let { locations.proposeLocality(entryId, it, System.currentTimeMillis()) }
				}
			}
		} catch (e: IOException) {
			Log.w(TAG, "network", e)
			return Result.retry()
		} catch (e: JSONException) {
			Log.w(TAG, "unexpected answer", e)
			return Result.retry()
		}
		return Result.success()
	}

	companion object {
		private const val TAG = "GeoEnrichWorker"
		private const val NAME = "geo-enrich"

		/*
		 * After a travel closes or tracking stops; the delay lets the stop fix
		 * arrive. A pass already queued or running is followed by this one.
		 */
		fun enqueue(context: Context) {
			val request = OneTimeWorkRequestBuilder<GeoEnrichWorker>()
				.setInitialDelay(2, TimeUnit.MINUTES)
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
		}
	}
}
