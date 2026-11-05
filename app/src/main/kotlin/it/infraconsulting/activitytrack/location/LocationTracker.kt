/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import it.infraconsulting.activitytrack.domain.geo.Fix
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.geo.SamplingPlan
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/*
 * Fix sources while an entry is ACTIVE (DESIGN §6.2): periodic updates at the
 * [SamplingPlan] interval (5 min working, 1 min travelling by default; issue
 * #155), an immediate fix on significant motion, and one-shot high-accuracy
 * fixes asked by the service (TRAVEL start/stop).
 * [onFix] gets the segment to tag for one-shot fixes, null otherwise.
 */
class LocationTracker(context: Context, private val onFix: (Fix, String?, Long?) -> Unit) {
	private val appContext = context.applicationContext
	private val lm = appContext.getSystemService(LocationManager::class.java)
	private val sensors = appContext.getSystemService(SensorManager::class.java)
	private val motionSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
	private var running = false
	private var plan: SamplingPlan? = null

	private val listener = LocationListener { deliver(it, null) }

	/* One-shot sensor: re-armed after every trigger. */
	private val motion = object : TriggerEventListener() {
		override fun onTrigger(event: TriggerEvent?) {
			if (!running)
				return
			current(null, highAccuracy = false)
			armMotion()
		}
	}

	/* Starts the periodic updates, or moves a running tracker to the new [plan]. */
	@SuppressLint("MissingPermission")
	fun start(plan: SamplingPlan) {
		if (!hasPermission(appContext))
			return
		if (running) {
			if (plan != this.plan) {
				lm.removeUpdates(listener)
				request(plan)
			}
			return
		}
		running = true
		request(plan)
		armMotion()
	}

	@SuppressLint("MissingPermission")
	private fun request(plan: SamplingPlan) {
		this.plan = plan
		val request = LocationRequest.Builder(plan.intervalMs)
			.setQuality(if (plan.highAccuracy) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
			.setMinUpdateIntervalMillis(minOf(MIN_INTERVAL_MS, plan.intervalMs))
			.build()
		lm.requestLocationUpdates(provider(), request, executor, listener)
	}

	fun stop() {
		if (!running)
			return
		running = false
		plan = null
		lm.removeUpdates(listener)
		motionSensor?.let { sensors.cancelTriggerSensor(motion, it) }
	}

	/* A single fix now; [segmentId] tags TRAVEL start/stop fixes. */
	@SuppressLint("MissingPermission")
	fun current(segmentId: Long?, highAccuracy: Boolean) {
		if (!hasPermission(appContext))
			return
		/* the quality, not the provider, asks for satellites: fused still answers indoors */
		val provider = provider()
		val request = LocationRequest.Builder(0)
			.setQuality(if (highAccuracy) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
			.setDurationMillis(CURRENT_TIMEOUT_MS)
			.build()
		lm.getCurrentLocation(provider, request, null, executor) { it?.let { loc -> deliver(loc, segmentId) } }
	}

	private fun armMotion() {
		motionSensor?.let { sensors.requestTriggerSensor(motion, it) }
	}

	/* Fused (API 31) when the device has it, else satellite, else network. */
	private fun provider(): String = when {
		lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
		lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
		else -> LocationManager.NETWORK_PROVIDER
	}

	private fun deliver(loc: Location, segmentId: Long?) {
		val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else UNKNOWN_ACCURACY_M
		onFix(Fix(loc.time, GeoPoint(loc.latitude, loc.longitude), acc), loc.provider, segmentId)
	}

	companion object {
		/* one delivery thread for the process: the service (and its tracker) may be recreated */
		private val executor: Executor = Executors.newSingleThreadExecutor()
		private const val MIN_INTERVAL_MS = 60_000L
		private const val CURRENT_TIMEOUT_MS = 60_000L
		private const val UNKNOWN_ACCURACY_M = 100.0

		fun hasPermission(context: Context): Boolean =
			context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
				context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
	}
}
