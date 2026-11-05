/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.LocationSampleEntity
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.domain.geo.Fix
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.track.TrackEntry
import it.infraconsulting.activitytrack.domain.track.TrackPoint
import it.infraconsulting.activitytrack.domain.track.TrackSegment

/* What the track map and export keep of the entries (issue #155). */
data class TrackFilter(
	val companyId: Long? = null,
	val clientId: Long? = null,
	val tag: String? = null,
	val travelOnly: Boolean = false,
)

/*
 * Location samples (SPEC §6; they leave the phone only with the explicit
 * export of the track, issue #155). A periodic fix is stored once
 * per ACTIVE entry, tagged with the entry's open segment; TRAVEL start/stop
 * fixes go to the given segment only, since at stop time it is already closed.
 */
class LocationRepository(db: ActivityTrackDatabase) {
	private val dao = db.locationDao()
	private val entries = db.entryDao()

	suspend fun record(fix: Fix, provider: String?, segmentId: Long? = null, nowMs: Long = System.currentTimeMillis()) {
		/* a cached fix handed out at (re)start keeps its old time: it would look like a move */
		if (nowMs - fix.atMs > MAX_AGE_MS)
			return
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

	suspend fun activeOpenSegments(): List<SegmentEntity> = dao.activeOpenSegments()

	suspend fun fixesOfSegment(segmentId: Long): List<Fix> =
		dao.samplesOfSegment(segmentId).map {
			Fix(it.tsUtcMs, GeoPoint(it.lat, it.lon), it.accuracyM?.toDouble() ?: UNKNOWN_ACCURACY_M)
		}

	suspend fun travelWithoutKm(): List<SegmentEntity> = dao.travelWithoutKm()

	suspend fun setKmEstimated(segmentId: Long, km: Double) = dao.setKmEstimated(segmentId, km)

	suspend fun entriesWithoutLocality(): List<Long> = dao.entriesWithoutLocality()

	suspend fun localityPoint(entryId: Long): GeoPoint? = dao.localitySample(entryId)?.let { GeoPoint(it.lat, it.lon) }

	suspend fun proposeLocality(entryId: Long, locality: String, nowMs: Long) = dao.proposeLocality(entryId, locality, nowMs)

	/* The track of the samples recorded in [fromMs, toMs), by entry and segment, oldest first. */
	suspend fun track(fromMs: Long, toMs: Long, filter: TrackFilter = TrackFilter()): List<TrackEntry> =
		build(dao.samplesBetween(fromMs, toMs), filter)

	suspend fun trackOfEntry(entryId: Long): List<TrackEntry> =
		build(dao.samplesOfEntry(entryId), TrackFilter())

	suspend fun sampleCount(): Int = dao.sampleCount()

	/* The clean-up: each returns the samples deleted; those a running entry or a km still to be computed need stay. */
	suspend fun deleteAllSamples(): Int = dao.deleteAllSamples()

	suspend fun deleteSamplesBefore(beforeMs: Long): Int = dao.deleteSamplesBefore(beforeMs)

	suspend fun deleteSamplesBetween(fromMs: Long, toMs: Long): Int = dao.deleteSamplesBetween(fromMs, toMs)

	/* Exactly the samples of [entries], after they were exported. */
	suspend fun deleteSamplesOf(entries: List<TrackEntry>): Int =
		entries.flatMap { e -> e.points.map { it.id } }.filter { it != 0L }.chunked(DELETE_CHUNK).sumOf { dao.deleteSamples(it) }

	private suspend fun build(samples: List<LocationSampleEntity>, filter: TrackFilter): List<TrackEntry> {
		if (samples.isEmpty())
			return emptyList()
		val ids = samples.map { it.entryId }.distinct()
		val rows = dao.trackEntries(ids).associateBy { it.entryId }
		val segments = dao.segmentsOf(ids).groupBy { it.entryId }
		val tags = dao.tagsOf(ids).groupBy({ it.entryId }, { it.tag })
		val out = ArrayList<TrackEntry>()
		for ((entryId, mine) in samples.groupBy { it.entryId }) {
			val row = rows[entryId] ?: continue
			if (filter.companyId != null && row.companyId != filter.companyId)
				continue
			if (filter.clientId != null && row.clientId != filter.clientId)
				continue
			val entryTags = tags[entryId].orEmpty()
			if (filter.tag != null && filter.tag !in entryTags)
				continue
			val bySegment = mine.groupBy { it.segmentId }
			val ofEntry = segments[entryId].orEmpty()
			val known = ofEntry.filter { it.id in bySegment }.map { s ->
				TrackSegment(s.kind, s.startUtcMs, s.endUtcMs, s.kmEstimated, s.kmDeclared, bySegment.getValue(s.id).map(::point))
			}
			/* samples of a segment deleted since stay in a segment of unknown kind */
			val knownIds = ofEntry.map { it.id }.toSet()
			val orphans = mine.filter { it.segmentId == null || it.segmentId !in knownIds }
			val all = known + listOfNotNull(
				orphans.takeIf { it.isNotEmpty() }?.let { TrackSegment(null, null, null, null, null, it.map(::point)) },
			)
			val kept = if (filter.travelOnly) all.filter { it.kind == SegmentKind.TRAVEL } else all
			if (kept.isEmpty())
				continue
			out += TrackEntry(entryId, row.zoneId, row.activity, row.company, row.client, row.role, row.locality, entryTags, kept)
		}
		return out.sortedBy { it.startUtcMs }
	}

	private fun point(s: LocationSampleEntity) = TrackPoint(s.tsUtcMs, s.lat, s.lon, s.accuracyM?.toDouble(), s.id)

	private companion object {
		/* well below SQLite's limit of variables per statement */
		const val DELETE_CHUNK = 500

		/* rare provider fixes without accuracy count as mediocre, not as exact */
		const val UNKNOWN_ACCURACY_M = 100.0
		/* fresh fixes are seconds old; the periodic interval is 5 min */
		const val MAX_AGE_MS = 2 * 60_000L
	}
}
