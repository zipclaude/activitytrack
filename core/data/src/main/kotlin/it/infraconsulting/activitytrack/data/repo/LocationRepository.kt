/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.LocationSampleEntity
import it.infraconsulting.activitytrack.domain.geo.Fix
import it.infraconsulting.activitytrack.domain.geo.GeoPoint

/*
 * Location samples (never exported, SPEC §6). A periodic fix is stored once
 * per ACTIVE entry, tagged with the entry's open segment; TRAVEL start/stop
 * fixes go to the given segment only, since at stop time it is already closed.
 */
class LocationRepository(db: ActivityTrackDatabase) {
	private val dao = db.locationDao()
	private val entries = db.entryDao()

	suspend fun record(fix: Fix, provider: String?, segmentId: Long? = null) {
		val targets = if (segmentId != null)
			listOfNotNull(entries.segment(segmentId))
		else
			dao.activeOpenSegments()
		if (targets.isEmpty())
			return
		dao.insertSamples(targets.map {
			LocationSampleEntity(
				entryId = it.entryId,
				segmentId = it.id,
				tsUtcMs = fix.atMs,
				lat = fix.point.lat,
				lon = fix.point.lon,
				accuracyM = fix.accuracyM.toFloat(),
				provider = provider,
			)
		})
	}

	suspend fun fixesOfSegment(segmentId: Long): List<Fix> =
		dao.samplesOfSegment(segmentId).map {
			Fix(it.tsUtcMs, GeoPoint(it.lat, it.lon), it.accuracyM?.toDouble() ?: UNKNOWN_ACCURACY_M)
		}

	private companion object {
		/* rare provider fixes without accuracy count as mediocre, not as exact */
		const val UNKNOWN_ACCURACY_M = 100.0
	}
}
