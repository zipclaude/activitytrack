/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.canUntick
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryDetailTest {
	private val zone = ZoneId.of("Europe/Rome")
	private fun at(s: String) = Instant.parse(s)
	private fun ms(s: String) = at(s).toEpochMilli()

	@Test
	fun stopEndTypedAsTimeOfDay() {
		/* 10:00 local (09:00Z): the nearest instant at the time typed; 09:30 and 10:45 are today */
		val now = at("2026-11-12T09:00:00Z")
		val today = LocalDate.of(2026, 11, 12)
		assertEquals(ms("2026-11-12T08:30:00Z"), EntryViewModel.endOn(9 * 60 + 30, today, zone, now, editableTimes = true))
		assertEquals(ms("2026-11-12T09:45:00Z"), EntryViewModel.endOn(10 * 60 + 45, today, zone, now, editableTimes = true))
		/* with the 24-hour limit (issue #179) 11:30 is accepted; "09:00" is an hour ago, not 23 hours ahead */
		assertEquals(ms("2026-11-12T10:30:00Z"), EntryViewModel.endOn(11 * 60 + 30, today, zone, now, editableTimes = true))
		assertEquals(ms("2026-11-12T08:00:00Z"), EntryViewModel.endOn(9 * 60, today, zone, now, editableTimes = true))
		/* 21:00 is 11 hours ahead, nearer than yesterday's 13 hours ago; 22:00 equally far: the earlier */
		assertEquals(ms("2026-11-12T20:00:00Z"), EntryViewModel.endOn(21 * 60, today, zone, now, editableTimes = true))
		assertEquals(ms("2026-11-11T21:00:00Z"), EntryViewModel.endOn(22 * 60, today, zone, now, editableTimes = true))
		/* without editable times nothing ahead: the nearest is not exchanged for yesterday's */
		assertNull(EntryViewModel.endOn(10 * 60 + 45, today, zone, now, editableTimes = false))
	}

	@Test
	fun stopEndAcrossMidnight() {
		/* 23:50 local: "00:20" is tomorrow; at 00:10 "23:50" is yesterday */
		val late = at("2026-11-12T22:50:00Z")
		assertEquals(ms("2026-11-12T23:20:00Z"), EntryViewModel.endOn(20, LocalDate.of(2026, 11, 12), zone, late, editableTimes = true))
		val early = at("2026-11-12T23:10:00Z")
		assertEquals(ms("2026-11-12T22:50:00Z"), EntryViewModel.endOn(23 * 60 + 50, LocalDate.of(2026, 11, 13), zone, early, editableTimes = false))
	}

	@Test
	fun siteNameByPreference() {
		val p = GeoPoint(45.0, 9.0)
		val near = NamedSite("Sede cliente", GeoPoint(45.0005, 9.0), 100)
		val far = NamedSite("Lontano", GeoPoint(45.1, 9.0), 100)
		val office = NamedSite("Ufficio", GeoPoint(45.0, 9.0001), 200)
		assertEquals("Sede cliente", SiteNames.containing(p, listOf(listOf(far, near), listOf(office))))
		assertEquals("Ufficio", SiteNames.containing(p, listOf(listOf(far), listOf(office))))
		assertNull(SiteNames.containing(p, listOf(listOf(far))))
		/* an unnamed site is skipped */
		assertEquals("Ufficio", SiteNames.containing(p, listOf(listOf(near.copy(label = " ")), listOf(office))))
	}

	@Test
	fun untickOnlyWithoutRealSegments() {
		val closed = TimeEntryEntity(uid = "u", companyId = null, clientId = null, status = ActivityStatus.CLOSED, zoneId = "UTC", createdUtcMs = 0, updatedUtcMs = 0)
		val seg = SegmentEntity(entryId = 0, kind = SegmentKind.WORK, startUtcMs = 0, endUtcMs = 60_000, zoneId = "UTC")
		assertTrue(canUntick(closed, emptyList()))
		assertTrue(canUntick(closed, listOf(seg.copy(planned = true))))
		assertFalse(canUntick(closed, listOf(seg)))
		assertFalse(canUntick(closed.copy(status = ActivityStatus.TODO), emptyList()))
		assertFalse(canUntick(closed.copy(deletedUtcMs = 1), emptyList()))
		assertFalse(canUntick(closed.copy(kind = ActivityKind.ABSENCE), emptyList()))
	}
}
