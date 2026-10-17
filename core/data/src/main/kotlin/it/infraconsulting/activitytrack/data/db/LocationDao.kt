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
}
