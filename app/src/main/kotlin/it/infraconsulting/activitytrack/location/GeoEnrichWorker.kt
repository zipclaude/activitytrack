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
 * - km of closed TRAVEL segments ([KmEstimate]): the straight line between
 *   fixes at most 2 min apart, the OSRM road distance (when enabled) or the
 *   straight line times the factor where they are farther apart; stray
 *   fixes are left out. Once, after the update that changed this (0.9.7),
 *   the km of the trips still holding their fixes are computed again;
 * - locality proposal for entries without one: Nominatim at the first WORK
 *   sample. Network failures retry the whole pass later.
 */
class GeoEnrichWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val c = (applicationContext as ActivityTrackApp).container
		val s = c.settings.current()
		val locations = c.locations
		try {
			val again = !prefs.getBoolean(KM_AGAIN, false)
			for (seg in if (again) locations.travelWithFixes() else locations.travelWithoutKm()) {
				val fixes = KmEstimate.plausible(locations.fixesOfSegment(seg.id))
				val road = HashMap<Int, Double>()
				if (s.osrm.enabled)
					for (run in KmEstimate.roadRuns(fixes)) {
						val legs = c.osrm.legsM(s.osrm.endpoint, fixes.subList(run.first, run.last + 2).map { it.point }) ?: continue
						for ((k, leg) in legs.withIndex())
							road[run.first + k] = leg
					}
				locations.setKmEstimated(seg.id, KmEstimate.km(fixes, s.kmFactor, road))
			}
			if (again)
				prefs.edit().putBoolean(KM_AGAIN, true).apply()
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

	private val prefs get() = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	companion object {
		private const val TAG = "GeoEnrichWorker"
		private const val NAME = "geo-enrich"
		private const val PREFS = "geo"
		private const val KM_AGAIN = "km_again_0_9_7"

		/* the one-off pass of the update is still due: the app asks for it when it starts */
		fun kmAgainDue(context: Context) = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KM_AGAIN, false)

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
