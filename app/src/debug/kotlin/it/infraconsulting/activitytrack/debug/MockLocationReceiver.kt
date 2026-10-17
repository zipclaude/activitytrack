/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import android.util.Log

/*
 * Debug-only fused test provider driven over adb (device tests of M2):
 *   am broadcast -n <pkg>/it.infraconsulting.activitytrack.debug.MockLocationReceiver -a ENABLE
 *   ... -a SET --es lat 45.47 --es lon 9.18 [--es acc 10]
 *   ... -a DISABLE
 * Requires the app selected as mock location app in Developer options.
 */
class MockLocationReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val lm = context.getSystemService(LocationManager::class.java)
		val provider = LocationManager.FUSED_PROVIDER
		try {
			when (intent.action) {
				"ENABLE" -> {
					lm.addTestProvider(
						provider,
						ProviderProperties.Builder()
							.setHasAltitudeSupport(true)
							.setHasSpeedSupport(true)
							.setHasBearingSupport(true)
							.setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
							.setAccuracy(ProviderProperties.ACCURACY_FINE)
							.build(),
					)
					lm.setTestProviderEnabled(provider, true)
				}
				"SET" -> {
					val loc = Location(provider).apply {
						latitude = intent.getStringExtra("lat")!!.toDouble()
						longitude = intent.getStringExtra("lon")!!.toDouble()
						accuracy = intent.getStringExtra("acc")?.toFloat() ?: 10f
						time = System.currentTimeMillis()
						elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
					}
					lm.setTestProviderLocation(provider, loc)
				}
				"DISABLE" -> lm.removeTestProvider(provider)
			}
			Log.i(TAG, "${intent.action} ok")
		} catch (e: Exception) {
			/* SecurityException until selected as mock location app */
			Log.w(TAG, "${intent.action} failed", e)
		}
	}

	private companion object {
		const val TAG = "MockLocation"
	}
}
