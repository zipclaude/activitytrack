/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationDao {
	@Insert
	suspend fun insertSamples(samples: List<LocationSampleEntity>)

	/* Open segment of every ACTIVE entry: a fix is recorded once per running entry. */
	@Query(
		"SELECT segment.* FROM segment JOIN time_entry ON time_entry.id = segment.entry_id " +
			"WHERE time_entry.status = 'ACTIVE' AND segment.end_utc_ms IS NULL",
	)
	suspend fun activeOpenSegments(): List<SegmentEntity>

	@Query("SELECT * FROM location_sample WHERE segment_id = :segmentId ORDER BY ts_utc_ms")
	suspend fun samplesOfSegment(segmentId: Long): List<LocationSampleEntity>

	/* Closed tracked TRAVEL segments with at least two samples and no km yet (GeoEnrichWorker). */
	@Query(
		"SELECT * FROM segment WHERE kind = 'TRAVEL' AND source = 'TRACKED' AND end_utc_ms IS NOT NULL " +
			"AND km_estimated IS NULL AND (SELECT COUNT(*) FROM location_sample WHERE segment_id = segment.id) >= 2",
	)
	suspend fun travelWithoutKm(): List<SegmentEntity>

	@Query("UPDATE segment SET km_estimated = :km WHERE id = :segmentId")
	suspend fun setKmEstimated(segmentId: Long, km: Double)

	/* Entries without locality that have samples (GeoEnrichWorker). */
	@Query(
		"SELECT id FROM time_entry WHERE locality IS NULL " +
			"AND EXISTS (SELECT 1 FROM location_sample WHERE entry_id = time_entry.id)",
	)
	suspend fun entriesWithoutLocality(): List<Long>

	/* Where the work happened: the first sample of a WORK segment, else the first sample. */
	@Query(
		"SELECT location_sample.* FROM location_sample LEFT JOIN segment ON segment.id = location_sample.segment_id " +
			"WHERE location_sample.entry_id = :entryId " +
			"ORDER BY CASE WHEN segment.kind = 'WORK' THEN 0 ELSE 1 END, location_sample.ts_utc_ms LIMIT 1",
	)
	suspend fun localitySample(entryId: Long): LocationSampleEntity?

	/* A proposal only: never overwrites a locality typed by the user meanwhile. */
	@Query("UPDATE time_entry SET locality = :locality, updated_utc_ms = :nowMs WHERE id = :entryId AND locality IS NULL")
	suspend fun proposeLocality(entryId: Long, locality: String, nowMs: Long)
}
