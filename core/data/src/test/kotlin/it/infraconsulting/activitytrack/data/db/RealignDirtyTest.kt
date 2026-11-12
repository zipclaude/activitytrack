/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import it.infraconsulting.activitytrack.data.db.ActivityModelMigration.Change
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityRow
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.LegacyData
import it.infraconsulting.activitytrack.domain.activity.LegacyEntry
import it.infraconsulting.activitytrack.domain.activity.LegacyTodo
import it.infraconsulting.activitytrack.domain.activity.MigrationResult
import it.infraconsulting.activitytrack.domain.activity.NewRef
import it.infraconsulting.activitytrack.domain.model.ExternalRefKind
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/* The records left to push after the migration (ActivityModelMigration.dirty): parents before their children */
class RealignDirtyTest {
	private val zone = ZoneId.of("Europe/Rome")

	private fun row(uid: String, id: Long?) =
		ActivityRow(id, uid, ActivityKind.ACTIVITY, ActivityStatus.TODO, zoneId = zone.id, createdUtcMs = 1, updatedUtcMs = 1)

	@Test
	fun entriesKeepTheirPlaceAndDerivedRecordsFollowTheirActivity() {
		val legacy = LegacyData(
			entries = listOf(LegacyEntry(1, "e-1", null, "ACTIVE", zoneId = zone.id, createdUtcMs = 1, updatedUtcMs = 1)),
			todos = listOf(LegacyTodo(7, "t-2", title = "Patch", createdUtcMs = 1, updatedUtcMs = 1)),
			zone = zone,
		)
		val result = MigrationResult(
			listOf(row("e-1", 1), row("t-2", null)),
			emptyList(),
			listOf(NewRef("t-2", "r-2", ExternalRefKind.TICKET, "INC1")),
		)
		val pending = listOf(
			Change("client", "c-1", "upsert", 1),
			Change("entry", "e-1", "upsert", 2),
			Change("segment", "s-1", "upsert", 3),
			Change("todo", "t-2", "upsert", 4),
			Change("expense", "x-1", "upsert", 5),
		)
		assertEquals(
			listOf("client/c-1", "entry/e-1", "segment/s-1", "entry/t-2", "expense/x-1", "externalRef/r-2"),
			ActivityModelMigration.dirty(pending, legacy, result).map { "${it.type}/${it.uid}" },
		)
	}
}
