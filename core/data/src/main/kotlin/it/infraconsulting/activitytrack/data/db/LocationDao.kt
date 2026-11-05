/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/*
 * Samples a clean-up must keep (issue #155): those of an entry still ACTIVE
 * (the movement checks read them) and of a closed tracked TRAVEL whose km
 * the GeoEnrichWorker has yet to compute. COALESCE: a sample without segment
 * has a NULL in the IN, which would make the whole NOT unknown, never true.
 */
private const val KEPT =
	"(entry_id IN (SELECT id FROM time_entry WHERE status = 'ACTIVE') OR COALESCE(segment_id IN (" +
		"SELECT id FROM segment WHERE kind = 'TRAVEL' AND source = 'TRACKED' AND end_utc_ms IS NOT NULL " +
		"AND km_estimated IS NULL AND (SELECT COUNT(*) FROM location_sample WHERE segment_id = segment.id) >= 2), 0))"

/* What an entry of the track map or export shows besides its points. */
data class TrackEntryRow(
	@ColumnInfo(name = "entry_id") val entryId: Long,
	@ColumnInfo(name = "zone_id") val zoneId: String,
	val activity: String?,
	val locality: String?,
	@ColumnInfo(name = "company_id") val companyId: Long?,
	@ColumnInfo(name = "client_id") val clientId: Long?,
	val company: String?,
	val client: String?,
	val role: String?,
)

data class EntryTagRow(
	@ColumnInfo(name = "entry_id") val entryId: Long,
	val tag: String,
)

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

	/* The track of a period or of one entry (issue #155), in time order. */
	@Query("SELECT * FROM location_sample WHERE ts_utc_ms >= :fromMs AND ts_utc_ms < :toMs ORDER BY entry_id, ts_utc_ms")
	suspend fun samplesBetween(fromMs: Long, toMs: Long): List<LocationSampleEntity>

	@Query("SELECT * FROM location_sample WHERE entry_id = :entryId ORDER BY ts_utc_ms")
	suspend fun samplesOfEntry(entryId: Long): List<LocationSampleEntity>

	@Query(
		"SELECT time_entry.id AS entry_id, time_entry.zone_id AS zone_id, time_entry.activity AS activity, " +
			"time_entry.locality AS locality, time_entry.company_id AS company_id, time_entry.client_id AS client_id, " +
			"company.name AS company, client.name AS client, role.name AS role FROM time_entry " +
			"LEFT JOIN company ON company.id = time_entry.company_id LEFT JOIN client ON client.id = time_entry.client_id " +
			"LEFT JOIN role ON role.id = time_entry.role_id WHERE time_entry.id IN (:ids)",
	)
	suspend fun trackEntries(ids: List<Long>): List<TrackEntryRow>

	@Query("SELECT * FROM segment WHERE entry_id IN (:ids) ORDER BY start_utc_ms")
	suspend fun segmentsOf(ids: List<Long>): List<SegmentEntity>

	@Query("SELECT entry_id, tag FROM entry_tag WHERE entry_id IN (:ids) ORDER BY tag")
	suspend fun tagsOf(ids: List<Long>): List<EntryTagRow>

	@Query("SELECT COUNT(*) FROM location_sample")
	suspend fun sampleCount(): Int

	/* The clean-up (issue #155); each returns the rows deleted, [KEPT] ones excepted. */
	@Query("DELETE FROM location_sample WHERE NOT $KEPT")
	suspend fun deleteAllSamples(): Int

	@Query("DELETE FROM location_sample WHERE ts_utc_ms < :beforeMs AND NOT $KEPT")
	suspend fun deleteSamplesBefore(beforeMs: Long): Int

	@Query("DELETE FROM location_sample WHERE ts_utc_ms >= :fromMs AND ts_utc_ms < :toMs AND NOT $KEPT")
	suspend fun deleteSamplesBetween(fromMs: Long, toMs: Long): Int

	@Query("DELETE FROM location_sample WHERE id IN (:ids) AND NOT $KEPT")
	suspend fun deleteSamples(ids: List<Long>): Int
}
