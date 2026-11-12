/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import android.util.Log
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.LocationRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.geo.Geo
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import java.io.IOException
import kotlinx.coroutines.flow.first
import org.json.JSONException

/* A site with its name, as the locality proposal sees it. */
data class NamedSite(val label: String, val point: GeoPoint, val radiusM: Int)

object SiteNames {
	/*
	 * The name of the nearest site of the first group whose radius contains
	 * [p]: the groups come in order of preference (client, company, user).
	 */
	fun containing(p: GeoPoint, groups: List<List<NamedSite>>): String? {
		for (sites in groups) {
			val hit = sites.filter { it.label.isNotBlank() }
				.map { it to Geo.distanceM(p, it.point) }
				.filter { (s, d) -> d <= s.radiusM }
				.minByOrNull { (_, d) -> d }
			if (hit != null)
				return hit.first.label.trim()
		}
		return null
	}
}

/*
 * The locality proposed for an activity without one (ACTIVITY-MODEL.md 8):
 * where the work happened (its first WORK sample, else the first sample;
 * the current position while it runs without samples) named after a site
 * of its client, of its company or of the user's places containing it,
 * else after the municipality there (Nominatim, when on in Settings:
 * [municipality] returns null otherwise).
 */
class LocalityPrefill(
	private val master: MasterDataRepository,
	private val locations: LocationRepository,
	private val municipality: suspend (GeoPoint) -> String?,
	private val currentPosition: suspend () -> GeoPoint?,
) {
	suspend fun propose(e: TimeEntryEntity): String? {
		val p = locations.localityPoint(e.id)
			?: (if (e.status == ActivityStatus.RUNNING) currentPosition() else null)
			?: return null
		val client = e.clientId?.let { id -> master.clientSites(id).first().map { NamedSite(it.label, GeoPoint(it.lat, it.lon), it.radiusM) } }
		val company = e.companyId?.let { id -> master.companySites(id).first().map { NamedSite(it.label, GeoPoint(it.lat, it.lon), it.radiusM) } }
		val user = master.places.first().map { NamedSite(it.label, GeoPoint(it.lat, it.lon), it.radiusM) }
		SiteNames.containing(p, listOfNotNull(client, company, user))?.let { return it }
		return try {
			municipality(p)
		} catch (x: IOException) {
			Log.w(TAG, "municipality", x)
			null
		} catch (x: JSONException) {
			Log.w(TAG, "municipality", x)
			null
		}
	}

	private companion object {
		const val TAG = "LocalityPrefill"
	}
}
