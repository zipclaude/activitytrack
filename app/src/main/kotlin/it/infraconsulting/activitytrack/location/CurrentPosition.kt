/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.SystemClock
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/*
 * One position for the UI (start proposal, "use current position"): a
 * recent last-known fix when available, else a fresh one within a timeout.
 * Null without permission, providers or a fix in time.
 */
object CurrentPosition {
	private const val MAX_AGE_MS = 2 * 60_000L
	private const val TIMEOUT_MS = 10_000L
	private val executor = Executors.newSingleThreadExecutor()

	@SuppressLint("MissingPermission")
	suspend fun get(context: Context, highAccuracy: Boolean = false): GeoPoint? {
		if (!LocationTracker.hasPermission(context))
			return null
		val lm = context.getSystemService(LocationManager::class.java)
		val providers = lm.getProviders(true)
		if (!highAccuracy) {
			providers.mapNotNull { lm.getLastKnownLocation(it) }
				.filter { ageMs(it) <= MAX_AGE_MS }
				.minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
				?.let { return GeoPoint(it.latitude, it.longitude) }
		}
		val provider = when {
			highAccuracy && LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
			LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
			LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
			LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
			else -> return null
		}
		val request = LocationRequest.Builder(0)
			.setQuality(if (highAccuracy) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
			.setDurationMillis(TIMEOUT_MS)
			.build()
		return withTimeoutOrNull(TIMEOUT_MS) {
			suspendCancellableCoroutine { cont ->
				val cancel = CancellationSignal()
				cont.invokeOnCancellation { cancel.cancel() }
				lm.getCurrentLocation(provider, request, cancel, executor) { loc ->
					if (cont.isActive)
						cont.resume(loc?.let { GeoPoint(it.latitude, it.longitude) })
				}
			}
		}
	}

	private fun ageMs(loc: Location): Long =
		(SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
}
