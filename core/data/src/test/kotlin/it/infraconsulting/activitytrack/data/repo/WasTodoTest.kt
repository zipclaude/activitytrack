/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/* "Discard" (EntryRepository.discard): a TODO started by mistake goes back to TODO, one born running to the bin */
class WasTodoTest {
	private fun entry(created: Long) =
		TimeEntryEntity(companyId = 1, clientId = 1, status = ActivityStatus.RUNNING, mode = SegmentKind.WORK, zoneId = "UTC", createdUtcMs = created, updatedUtcMs = created)

	private fun seg(start: Long, planned: Boolean = false) = SegmentEntity(entryId = 1, kind = SegmentKind.WORK, startUtcMs = start, zoneId = "UTC", planned = planned)

	@Test
	fun bornRunningIsCreatedAtOrAfterItsFirstStart() {
		/* "Lavoro · Adesso": created at the start */
		assertFalse(wasTodo(entry(1_000), listOf(seg(1_000))))
		/* "Già iniziata" or a start rounded down: before the creation */
		assertFalse(wasTodo(entry(1_000), listOf(seg(400))))
	}

	@Test
	fun aTodoWasCreatedBeforeItStarted() {
		assertTrue(wasTodo(entry(1_000), listOf(seg(600_000))))
		/* a planned segment left over does not count as a start */
		assertTrue(wasTodo(entry(1_000), listOf(seg(500, planned = true), seg(600_000))))
	}

	@Test
	fun theFieldsOfATodoTellEvenWhenTheStartWasBackdated() {
		val e = entry(1_000)
		val early = listOf(seg(400))
		assertTrue(wasTodo(e.copy(planEpochDay = 20_000), early))
		assertTrue(wasTodo(e.copy(dueEpochDay = 20_000), early))
		assertTrue(wasTodo(e.copy(priority = 2), early))
		assertTrue(wasTodo(e.copy(externalKey = "T-1"), early))
	}
}
