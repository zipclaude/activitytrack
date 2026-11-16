/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.ui.entry

import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.canUntick
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.LocationKind
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

	private val closed = TimeEntryEntity(uid = "u", companyId = null, clientId = null, status = ActivityStatus.CLOSED, zoneId = "UTC", createdUtcMs = 0, updatedUtcMs = 0, closedUtcMs = 3_600_000)
	private val travel = SegmentEntity(entryId = 0, kind = SegmentKind.TRAVEL, startUtcMs = 0, endUtcMs = 3_600_000, zoneId = "UTC")

	@Test
	fun completeOfferedForTravelOnly() {
		/* a closed activity of travel only: reopen in work or travel, Completa, then Elimina (issue #189) */
		val cmds = detailCommands(closed, listOf(travel), travel = true, canDelete = true)
		assertEquals(
			listOf(DetailCommand.Reopen(SegmentKind.WORK), DetailCommand.Reopen(SegmentKind.TRAVEL), DetailCommand.Complete, DetailCommand.Delete),
			cmds,
		)
		/* declared complete: "Annulla completamento" instead */
		val done = detailCommands(closed.copy(completedUtcMs = 3_700_000), listOf(travel), travel = true, canDelete = false)
		assertTrue(DetailCommand.Uncomplete in done)
		assertFalse(DetailCommand.Complete in done)
		/* with work there is nothing to declare: a client completes it */
		val work = travel.copy(kind = SegmentKind.WORK)
		assertFalse(DetailCommand.Complete in detailCommands(closed, listOf(travel, work), travel = true, canDelete = true))
		/* a deleted one offers Ripristina only */
		assertEquals(listOf(DetailCommand.Restore), detailCommands(closed.copy(deletedUtcMs = 1), listOf(travel), travel = true, canDelete = true))
		/* without the travel feature no "reopen with travel" */
		assertFalse(DetailCommand.Reopen(SegmentKind.TRAVEL) in detailCommands(closed, listOf(travel), travel = false, canDelete = true))
	}

	@Test
	fun attachmentRowShowsTheDescription() {
		/* issue #185: the description over the time, none when blank */
		assertEquals("Contatore\n10:30 · 2026-11-14", attachmentSubtitle(" Contatore ", "10:30 · 2026-11-14"))
		assertEquals("10:30 · 2026-11-14", attachmentSubtitle("  ", "10:30 · 2026-11-14"))
		assertEquals("10:30 · 2026-11-14", attachmentSubtitle(null, "10:30 · 2026-11-14"))
	}

	@Test
	fun actionSlotsEqualAndWrapped() {
		/* issue #187: a 360 dp phone's card (~320 dp) holds five 64 dp slots, a narrow one four; never fewer than three */
		assertEquals(5, actionsPerRow(320f, 64f))
		assertEquals(4, actionsPerRow(300f, 64f))
		assertEquals(3, actionsPerRow(150f, 64f))
		assertEquals(5, actionsPerRow(800f, 64f))
		assertEquals(listOf(0..3), actionRows(4, 5))
		/* more than a row holds: a second row, same slot width, what is left */
		assertEquals(listOf(0..3, 4..4), actionRows(5, 4))
		assertEquals(emptyList(), actionRows(0, 4))
	}

	@Test
	fun segmentSwipeDeletesWhatTheDialogWould() {
		/* issue #183: closed and editable; a planned one and an absence day always; never the running one */
		val work = SegmentEntity(entryId = 0, kind = SegmentKind.WORK, startUtcMs = 0, endUtcMs = 60_000, zoneId = "UTC")
		assertTrue(segmentDeletable(work, editableTimes = true))
		assertFalse(segmentDeletable(work, editableTimes = false))
		assertTrue(segmentDeletable(work.copy(planned = true), editableTimes = false))
		assertTrue(segmentDeletable(work.copy(kind = SegmentKind.ABSENCE), editableTimes = false))
		assertFalse(segmentDeletable(work.copy(endUtcMs = null), editableTimes = true))
	}

	@Test
	fun soleClientFilledWithItsCompany() {
		/* issue #182: a company with one client (not archived) fills the empty client field */
		val c = it.infraconsulting.activitytrack.data.db.ClientEntity(id = 1, companyId = 10, name = "Solo")
		val clients = listOf(
			c,
			c.copy(id = 2, companyId = 20, name = "A"), c.copy(id = 3, companyId = 20, name = "B"),
			c.copy(id = 4, companyId = 30, name = "Attivo"), c.copy(id = 5, companyId = 30, name = "Archiviato", archived = true),
		)
		assertEquals(1L, clientAfterCompany(10, null, clients))
		/* two clients: the field stays empty */
		assertNull(clientAfterCompany(20, null, clients))
		/* an archived one does not count */
		assertEquals(4L, clientAfterCompany(30, null, clients))
		/* a client of another company goes, and the sole one comes */
		assertEquals(1L, clientAfterCompany(10, 2, clients))
		/* a client of that company stays */
		assertEquals(3L, clientAfterCompany(20, 3, clients))
		assertEquals(5L, clientAfterCompany(30, 5, clients))
		/* "none" as company keeps the client */
		assertEquals(2L, clientAfterCompany(null, 2, clients))
		assertNull(clientAfterCompany(40, null, clients))
	}

	@Test
	fun placeListAndKind() {
		/* issue #184: client sites, company sites, "Casa" last; blanks and repeats left out */
		val options = placeOptions(listOf("Stabilimento Nord", " ", "Sede"), listOf("Sede", "Ufficio Milano"), "Casa")
		assertEquals(
			listOf(
				PlaceOption("Stabilimento Nord", LocationKind.CLIENT), PlaceOption("Sede", LocationKind.CLIENT),
				PlaceOption("Ufficio Milano", LocationKind.COMPANY), PlaceOption("Casa", LocationKind.HOME),
			),
			options,
		)
		/* the kind follows the text: a label of the list (case and accents ignored), else OTHER, empty none */
		assertEquals(LocationKind.CLIENT, placeKindOf("stabilimento nord", options))
		assertEquals(LocationKind.COMPANY, placeKindOf(" Ufficio Milano ", options))
		assertEquals(LocationKind.HOME, placeKindOf("casa", options))
		assertEquals(LocationKind.OTHER, placeKindOf("Brescia", options))
		assertNull(placeKindOf("  ", options))
		/* the drop-down: everything while empty or on a label, else the matches */
		assertEquals(options, placeMatches("", options))
		assertEquals(options, placeMatches("Sede", options))
		assertEquals(listOf(PlaceOption("Ufficio Milano", LocationKind.COMPANY)), placeMatches("mil", options))
	}

	@Test
	fun proposedSiteCarriesItsKind() {
		val p = GeoPoint(45.0, 9.0)
		val client = NamedSite("Cantiere", GeoPoint(45.0005, 9.0), 100, LocationKind.CLIENT)
		val home = NamedSite("Casa", GeoPoint(45.0, 9.0), 100, LocationKind.HOME)
		assertEquals(client, SiteNames.containingSite(p, listOf(listOf(client), listOf(home))))
		assertEquals(LocationKind.HOME, SiteNames.containingSite(p, listOf(listOf(client.copy(radiusM = 10)), listOf(home)))?.kind)
	}

	@Test
	fun routeBesideThePlace() {
		/* issue #191: an activity with samples, the location feature, its map or (track export and export) */
		assertTrue(routeShown(ActivityKind.ACTIVITY, hasSamples = true, location = true, map = true, trackExport = false, export = false))
		assertTrue(routeShown(ActivityKind.ACTIVITY, hasSamples = true, location = true, map = false, trackExport = true, export = true))
		assertFalse(routeShown(ActivityKind.ACTIVITY, hasSamples = true, location = true, map = false, trackExport = true, export = false))
		assertFalse(routeShown(ActivityKind.ACTIVITY, hasSamples = false, location = true, map = true, trackExport = true, export = true))
		assertFalse(routeShown(ActivityKind.ACTIVITY, hasSamples = true, location = false, map = true, trackExport = true, export = true))
		assertFalse(routeShown(ActivityKind.ABSENCE, hasSamples = true, location = true, map = true, trackExport = true, export = true))
	}
}
