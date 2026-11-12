/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityPresetEntity
import it.infraconsulting.activitytrack.domain.settings.OrderedList
import it.infraconsulting.activitytrack.domain.settings.ListOrder
import it.infraconsulting.activitytrack.domain.settings.ListOrders
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.CallDirection
import it.infraconsulting.activitytrack.data.db.CallLogEntity
import it.infraconsulting.activitytrack.data.db.ContactEntity
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.WorkScheduleEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.model.QuickAction
import it.infraconsulting.activitytrack.domain.model.ExternalRefKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.ReturnChoice
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class TestClock(var now: Instant) : Clock() {
	override fun getZone(): ZoneId = ZoneOffset.UTC
	override fun withZone(zone: ZoneId?): Clock = this
	override fun instant(): Instant = now
	fun advance(minutes: Long) {
		now = now.plusSeconds(minutes * 60)
	}
}

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
	private lateinit var db: ActivityTrackDatabase
	private lateinit var clock: TestClock
	private lateinit var master: MasterDataRepository
	private lateinit var entries: EntryRepository
	private var acme = 0L
	private var other = 0L
	private var client = 0L

	@Before
	fun setUp() = runBlocking<Unit> {
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		db = Room.inMemoryDatabaseBuilder(ctx, ActivityTrackDatabase::class.java).build()
		clock = TestClock(Instant.parse("2026-10-14T08:00:00Z"))
		master = MasterDataRepository(db)
		entries = EntryRepository(db, clock)
		acme = master.saveCompany(CompanyEntity(name = " Acme "))
		other = master.saveCompany(CompanyEntity(name = "Other"))
		client = master.saveClient(ClientEntity(companyId = acme, name = "Bank"))
	}

	@After
	fun tearDown() = db.close()

	/* the 0.9 names of the states the tests were written with: ACTIVE, SUSPENDED (running), CLOSED, TODO */
	private suspend fun state(id: Long): String {
		val e = db.entryDao().entry(id)!!
		return when (e.status) {
			ActivityStatus.RUNNING -> if (e.mode != null) "ACTIVE" else "SUSPENDED"
			else -> e.status.name
		}
	}

	private fun reason(block: suspend () -> Unit): Reason =
		assertFailsWith<ValidationException> { runBlocking { block() } }.reason

	@Test
	fun masterDataValidation() = runBlocking<Unit> {
		assertEquals("Acme", db.masterDataDao().company(acme)?.name)
		assertEquals(Reason.BLANK_NAME, reason { master.saveCompany(CompanyEntity(name = "  ")) })
		assertEquals(Reason.DUPLICATE_NAME, reason { master.saveCompany(CompanyEntity(name = "Acme")) })
		assertEquals(Reason.IN_USE, reason { master.deleteCompany(CompanyEntity(id = acme, name = "Acme")) })

		master.saveSchedule(WorkScheduleEntity(dayOfWeek = 1, startMin = 540, endMin = 780))
		assertEquals(
			Reason.OVERLAPPING_INTERVALS,
			reason { master.saveSchedule(WorkScheduleEntity(dayOfWeek = 1, startMin = 720, endMin = 1080)) },
		)
		master.saveSchedule(WorkScheduleEntity(dayOfWeek = 1, startMin = 840, endMin = 1080))
		assertEquals(
			Reason.INVALID_TIME_RANGE,
			reason { master.saveSchedule(WorkScheduleEntity(dayOfWeek = 2, startMin = 600, endMin = 600)) },
		)
	}

	@Test
	fun lunchBreakReplacesPerDay() = runBlocking<Unit> {
		master.setLunchBreak(1, 720, 780)
		master.setLunchBreak(1, 750, 840)
		assertEquals(listOf(750 to 840), master.lunchBreaks.first().map { it.startMin to it.endMin })
	}

	/* the creation dialog's extras (ACTIVITY-MODEL.md 3): notes, plugin fields, references, segments before the start */
	@Test
	fun startKeepsTheDialogsExtras() = runBlocking<Unit> {
		val now = clock.millis()
		val id = entries.start(
			StartRequest(
				SegmentKind.WORK, client, notes = " asked by phone ", pluginFields = "{\"k\":\"v\"}",
				earlier = listOf(SegmentInput(SegmentKind.TRAVEL, now - 3_600_000, now - 1_800_000)),
				externalRefs = listOf("TCK-1", " ", "TCK-2"),
			),
		)
		val e = db.entryDao().entry(id)!!
		assertEquals("asked by phone", e.notes)
		assertEquals("{\"k\":\"v\"}", e.pluginFields)
		assertEquals(listOf("TCK-1", "TCK-2"), entries.externalRefValues(id))
		assertEquals(listOf(SegmentKind.TRAVEL, SegmentKind.WORK), db.entryDao().segments(id).map { it.kind })
		/* a segment after the start is refused */
		assertEquals(
			Reason.INVALID_SEGMENT,
			reason { entries.start(StartRequest(SegmentKind.WORK, client, earlier = listOf(SegmentInput(SegmentKind.PAUSE, now + 3_600_000, now + 7_200_000)))) },
		)
	}

	@Test
	fun clientCompanyLockedOnceUsed() = runBlocking<Unit> {
		entries.start(StartRequest(SegmentKind.WORK, client))
		assertEquals(
			Reason.CLIENT_COMPANY_LOCKED,
			reason { master.saveClient(ClientEntity(id = client, companyId = other, name = "Bank")) },
		)
		/* renaming is fine */
		master.saveClient(ClientEntity(id = client, companyId = acme, name = "Bank SpA"))
	}

	@Test
	fun startDerivesCompanyAndRejectsMismatch() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		assertEquals(acme, db.entryDao().entry(id)?.companyId)
		assertEquals(Reason.CLIENT_COMPANY_MISMATCH, reason { entries.start(StartRequest(SegmentKind.WORK, client, other)) })
		assertEquals(Reason.CLIENT_REQUIRED, reason { entries.start(StartRequest(SegmentKind.WORK, null)) })
		/* travel without destination, company only */
		val t = entries.start(StartRequest(SegmentKind.TRAVEL, null, other))
		assertNull(db.entryDao().entry(t)?.clientId)
	}

	@Test
	fun lifecycleKeepsSegmentsConsistent() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.TRAVEL, client))
		clock.advance(30)
		entries.quick(id, QuickAction.WORK)
		clock.advance(60)
		entries.quick(id, QuickAction.PAUSE)
		clock.advance(15)
		entries.quick(id, QuickAction.WORK)
		clock.advance(45)
		entries.suspend(id)
		clock.advance(60)
		entries.quick(id, QuickAction.WORK)
		clock.advance(10)
		entries.stop(id)

		val segs = db.entryDao().segments(id)
		assertEquals(
			listOf(SegmentKind.TRAVEL, SegmentKind.WORK, SegmentKind.PAUSE, SegmentKind.WORK, SegmentKind.WORK),
			segs.map { it.kind },
		)
		assertEquals(listOf(30L, 60L, 15L, 45L, 10L), segs.map { (it.endUtcMs!! - it.startUtcMs) / 60_000 })
		assertEquals("CLOSED", state(id))

		assertEquals(Reason.NOT_ALLOWED, reason { entries.quick(id, QuickAction.PAUSE) })
	}

	@Test
	fun stopClosesWhenDetailsAreComplete() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client, activity = "Patch"))
		clock.advance(30)
		entries.stop(id)
		assertEquals("CLOSED", state(id))
	}

	@Test
	fun endChosenAtStart() = runBlocking<Unit> {
		val t0 = clock.millis()
		clock.advance(120)
		/* past end: recorded as stopped, closed since client and activity are there */
		val past = entries.start(StartRequest(SegmentKind.WORK, client, activity = "Call", startUtcMs = t0, endUtcMs = t0 + 3_600_000))
		assertEquals("CLOSED", state(past))
		assertEquals(t0 + 3_600_000, db.entryDao().segments(past).single().endUtcMs)
		/* future end: running, planned end kept until asked */
		val open = entries.start(StartRequest(SegmentKind.WORK, client, endUtcMs = clock.millis() + 1_800_000))
		assertEquals(clock.millis() + 1_800_000, db.entryDao().entry(open)?.plannedEndUtcMs)
		entries.clearPlannedEnd(open)
		assertNull(db.entryDao().entry(open)?.plannedEndUtcMs)
	}

	@Test
	fun manualSegmentsMustNotOverlap() = runBlocking<Unit> {
		val start = clock.millis()
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(120)
		val h = 3_600_000L
		entries.addManualSegment(id, SegmentKind.TRAVEL, start - h, start, kmDeclared = 42.0)
		assertEquals(
			Reason.OVERLAPPING_SEGMENTS,
			reason { entries.addManualSegment(id, SegmentKind.TRAVEL, start - h / 2, start + h / 2) },
		)
		assertEquals(
			Reason.INVALID_SEGMENT,
			reason { entries.addManualSegment(id, SegmentKind.WORK, start - 2 * h, start - h) },
		)
		assertEquals(2, db.entryDao().segments(id).size)
	}

	/* locked times (setting or policy editableTimes): no real segment typed by hand, an absence's days still */
	@Test
	fun lockedTimesRefuseManualSegments() = runBlocking<Unit> {
		val locked = EntryRepository(db, clock, times = { TimeRules(editableTimes = false) })
		val start = clock.millis()
		val id = locked.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(120)
		locked.stop(id)
		val h = 3_600_000L
		for (k in listOf(SegmentKind.WORK, SegmentKind.TRAVEL, SegmentKind.PAUSE))
			assertEquals(Reason.NOT_ALLOWED, reason { locked.addManualSegment(id, k, start - 2 * h, start - h) })
		assertEquals(1, db.entryDao().segments(id).size)
	}

	@Test
	fun backwardsClockNeverOverlaps() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(-10)
		entries.quick(id, QuickAction.PAUSE)
		val segs = db.entryDao().segments(id)
		assertEquals(segs[0].startUtcMs, segs[0].endUtcMs)
		assertEquals(segs[0].endUtcMs, segs[1].startUtcMs)
	}

	@Test
	fun seededLunchBreaks() = runBlocking<Unit> {
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		val seeded = Room.inMemoryDatabaseBuilder(ctx, ActivityTrackDatabase::class.java)
			.addCallback(ActivityTrackDatabase.Seed)
			.build()
		try {
			val rows = seeded.masterDataDao().observeLunchBreaks().first()
			assertEquals((1..5).toList(), rows.map { it.dayOfWeek })
			assertEquals(setOf(750 to 840), rows.map { it.startMin to it.endMin }.toSet())
		} finally {
			seeded.close()
		}
	}

	@Test
	fun startHandlesConcurrentEntriesAtomically() = runBlocking<Unit> {
		val a = entries.start(StartRequest(SegmentKind.WORK, client))
		val b = entries.start(StartRequest(SegmentKind.TRAVEL, null, other))
		clock.advance(20)
		val c = entries.start(
			StartRequest(SegmentKind.WORK, client),
			mapOf(a to OnRunning.SUSPEND, b to OnRunning.STOP),
		)
		val dao = db.entryDao()
		assertEquals("SUSPENDED", state(a))
		assertEquals("CLOSED", state(b))
		assertEquals("ACTIVE", state(c))

		/* an illegal command for another entry rolls back the new start too */
		val before = dao.observeOpenEntries().first().size
		assertEquals(Reason.NOT_ALLOWED, reason { entries.start(StartRequest(SegmentKind.WORK, client), mapOf(a to OnRunning.SUSPEND)) })
		assertEquals(before, dao.observeOpenEntries().first().size)

		/* a is the only suspended entry; b was stopped */
		assertEquals(a, entries.suspendedToResume(b)?.id)
		assertNull(entries.suspendedToResume(a))
	}

	@Test
	fun resumeSuspendsTheOthersWithoutConcurrentEntries() = runBlocking<Unit> {
		var concurrent = true
		val repo = EntryRepository(db, clock, concurrent = { concurrent })
		val dao = db.entryDao()
		val a = repo.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(10)
		repo.suspend(a)
		val b = repo.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(10)
		/* allowed: both run */
		repo.quick(a, QuickAction.WORK)
		assertEquals("ACTIVE", state(b))
		repo.suspend(a)
		clock.advance(10)
		/* not allowed (issue #132): resuming a suspends b; a pause is suspended too (coordinator, 1.0) */
		concurrent = false
		repo.quick(a, QuickAction.WORK)
		assertEquals("ACTIVE", state(a))
		assertEquals("SUSPENDED", state(b))
		repo.quick(a, QuickAction.PAUSE)
		clock.advance(10)
		repo.quick(b, QuickAction.TRAVEL)
		assertEquals("ACTIVE", state(b))
		assertEquals("SUSPENDED", state(a))
	}

	@Test
	fun clientDefaultsWithinThirtyDays() = runBlocking<Unit> {
		val role = master.saveRole(it.infraconsulting.activitytrack.data.db.RoleEntity(name = "Consultant"))
		val id = entries.start(StartRequest(SegmentKind.WORK, client, roleId = role, activity = "Firewall review"))
		assertEquals(ClientDefaults("Firewall review", role), entries.clientDefaults(client))
		clock.advance(31L * 24 * 60)
		assertNull(entries.clientDefaults(client))
		entries.stop(id)
	}

	@Test
	fun proposalUsesTodaysCompany() = runBlocking<Unit> {
		assertNull(entries.startProposal(emptyList()).companyId)
		entries.start(StartRequest(SegmentKind.WORK, client))
		assertEquals(acme, entries.startProposal(emptyList()).companyId)
		/* next day: no company yet */
		clock.advance(24 * 60)
		assertNull(entries.startProposal(emptyList()).companyId)
	}

	@Test
	fun stopAtLastConfirmationBackdates() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(30)
		entries.confirm(id)
		val confirmed = clock.millis()
		clock.advance(45)
		entries.markReminded(id)
		entries.markReminded(id)
		assertEquals(2, db.entryDao().entry(id)?.unansweredReminders)
		entries.stopAtLastConfirmation(id)
		val e = db.entryDao().entry(id)!!
		assertEquals("CLOSED", state(e.id))
		assertEquals(confirmed, db.entryDao().segments(id).single().endUtcMs)
	}

	@Test
	fun pauseReminderMovesBase() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		entries.quick(id, QuickAction.PAUSE)
		clock.advance(20)
		entries.markPauseReminded(id)
		val e = db.entryDao().entry(id)!!
		assertEquals(clock.millis(), e.lastConfirmedUtcMs)
		assertEquals(1, e.unansweredReminders)
	}

	@Test
	fun reportDaysWithCarryOver() = runBlocking<Unit> {
		/* clock is UTC; 22:00 -> 04:30 next day stays on the start day (carry-over 06:00) */
		clock.now = Instant.parse("2026-10-14T22:00:00Z")
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.now = Instant.parse("2026-10-15T04:30:00Z")
		entries.stop(id)
		val reports = ReportRepository(db, clock)
		val d14 = java.time.LocalDate.of(2026, 10, 14)
		val days = reports.days(d14, d14.plusDays(1), ZoneOffset.UTC, it.infraconsulting.activitytrack.domain.rounding.ReportRounding.DEFAULT, 360)
		assertEquals(listOf(d14), days.map { it.day })
		assertEquals(java.time.Duration.ofMinutes(390), days.single().solar)
	}

	@Test
	fun externalRefsAndManualSegmentDeletion() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		entries.addExternalRef(id, " INC0012345 ")
		entries.addExternalRef(id, "https://jira.example.com/browse/OPS-1")
		assertEquals(Reason.BLANK_VALUE, reason { entries.addExternalRef(id, "  ") })
		val refs = entries.externalRefs(id).first()
		assertEquals(
			listOf(ExternalRefKind.TICKET to "INC0012345", ExternalRefKind.URL to "https://jira.example.com/browse/OPS-1"),
			refs.map { it.kind to it.value },
		)
		entries.removeExternalRef(id, refs[0].id)
		assertEquals(1, entries.externalRefs(id).first().size)

		val start = clock.millis()
		clock.advance(60)
		val manual = entries.addManualSegment(id, SegmentKind.TRAVEL, start - 3_600_000, start)
		val tracked = db.entryDao().segments(id).first { it.id != manual }
		entries.deleteSegment(manual)
		assertEquals(listOf(tracked.id), db.entryDao().segments(id).map { it.id })
		/* the last segment stays */
		assertEquals(Reason.LAST_SEGMENT, reason { entries.deleteSegment(tracked.id) })
	}

	@Test
	fun exportRowsCarryNamesRefsAndKm() = runBlocking<Unit> {
		val start = clock.millis()
		val id = entries.start(StartRequest(SegmentKind.WORK, client, activity = "Audit"))
		entries.addExternalRef(id, "OPS-7")
		clock.advance(120)
		entries.stop(id)
		entries.addManualSegment(id, SegmentKind.TRAVEL, start - 1_800_000, start, kmDeclared = 12.5)
		val day = java.time.LocalDate.of(2026, 10, 14)
		val rows = ReportRepository(db, clock).exportRows(
			day, day, ZoneOffset.UTC, it.infraconsulting.activitytrack.domain.rounding.ReportRounding.DEFAULT, 360,
		)
		val r = rows.single()
		assertEquals(listOf("Acme", "Bank", "Audit"), listOf(r.company, r.client, r.activity))
		assertEquals(listOf("OPS-7"), r.externalRefs)
		assertEquals(12.5, r.km)
		assertEquals(java.time.Duration.ofMinutes(120), r.work)
		assertEquals(java.time.Duration.ofMinutes(30), r.travel)
	}

	@Test
	fun backupZipHasDatabaseAndManifest() = runBlocking<Unit> {
		entries.start(StartRequest(SegmentKind.WORK, client))
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		val out = java.io.ByteArrayOutputStream()
		val m = it.infraconsulting.activitytrack.data.storage.DatabaseBackup(db, ctx.cacheDir)
			.writeZip("test", clock.instant(), out)
		assertEquals(ActivityTrackDatabase.VERSION, m.dbVersion)
		val names = ArrayList<String>()
		var header = ByteArray(0)
		java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { zip ->
			while (true) {
				val e = zip.nextEntry ?: break
				names += e.name
				if (e.name == ActivityTrackDatabase.FILE_NAME)
					header = zip.readBytes().copyOf(15)
			}
		}
		assertEquals(listOf(ActivityTrackDatabase.FILE_NAME, "manifest.json"), names)
		assertEquals("SQLite format 3", String(header, Charsets.US_ASCII))
	}

	@Test
	fun clientLastUse() = runBlocking<Unit> {
		entries.start(StartRequest(SegmentKind.WORK, client))
		assertEquals(listOf(client to clock.millis()), entries.clientLastUse.first().map { it.clientId to it.lastMs })
	}

	@Test
	fun chosenStartAndEndTimes() = runBlocking<Unit> {
		val t0 = clock.millis()
		clock.advance(60)
		val id = entries.start(StartRequest(SegmentKind.WORK, client, startUtcMs = t0))
		assertEquals(t0, db.entryDao().segments(id).single().startUtcMs)
		assertEquals(Reason.FUTURE_TIME, reason { entries.start(StartRequest(SegmentKind.WORK, client, startUtcMs = clock.millis() + 60_000)) })
		assertEquals(Reason.BEFORE_SEGMENT, reason { entries.stop(id, t0 - 60_000) })
		/* a stop may lie up to 60 minutes ahead (4.1), not beyond */
		assertEquals(Reason.FUTURE_TIME, reason { entries.stop(id, clock.millis() + 61 * 60_000) })
		entries.stop(id, t0 + 1_800_000)
		assertEquals(t0 + 1_800_000, db.entryDao().segments(id).single().endUtcMs)
	}

	@Test
	fun listOrders() = runBlocking<Unit> {
		val orders = MutableStateFlow(ListOrders())
		val m = MasterDataRepository(db, orders, clock)
		val zed = m.saveCompany(CompanyEntity(name = "zed"))
		/* alphabetical, ignoring case */
		assertEquals(listOf(acme, other, zed), m.companies.first().map { it.id })
		/* last use: the company of the newest entry first */
		entries.start(StartRequest(SegmentKind.TRAVEL, null, companyId = other))
		orders.value = ListOrders(companies = ListOrder.LAST_USE)
		assertEquals(listOf(other, acme, zed), m.companies.first().map { it.id })
		/* manual: the dragged order, a new company after it */
		m.reorder(OrderedList.COMPANIES, listOf(zed, acme, other))
		orders.value = ListOrders(companies = ListOrder.MANUAL)
		val added = m.saveCompany(CompanyEntity(name = "Beta"))
		assertEquals(listOf(zed, acme, other, added), m.companies.first().map { it.id })
		/* editing keeps the place */
		m.saveCompany(db.masterDataDao().company(acme)!!.copy(name = "Acme2"))
		assertEquals(listOf(zed, acme, other, added), m.companies.first().map { it.id })
	}

	@Test
	fun presetLastUse() = runBlocking<Unit> {
		val orders = MutableStateFlow(ListOrders(presets = ListOrder.LAST_USE))
		val m = MasterDataRepository(db, orders, clock)
		val a = m.savePreset(ActivityPresetEntity(text = "Alpha"))
		val b = m.savePreset(ActivityPresetEntity(text = "Bravo"))
		assertEquals(listOf(a, b), m.presets.first().map { it.id })
		m.touchPreset(b)
		assertEquals(listOf(b, a), m.presets.first().map { it.id })
	}

	@Test
	fun backdatedCommandsAreClamped() = runBlocking<Unit> {
		val t0 = clock.millis()
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(30)
		/* departure detected 10 min after the start */
		entries.quick(id, QuickAction.TRAVEL, t0 + 10 * 60_000)
		/* an arrival before the travel start is clamped to it; one in the future to now */
		clock.advance(30)
		entries.quick(id, QuickAction.WORK, t0)
		entries.quick(id, QuickAction.PAUSE, clock.millis() + 3_600_000)
		val segs = db.entryDao().segments(id)
		assertEquals(listOf(SegmentKind.WORK, SegmentKind.TRAVEL, SegmentKind.WORK, SegmentKind.PAUSE), segs.map { it.kind })
		assertEquals(t0 + 10 * 60_000, segs[0].endUtcMs)
		assertEquals(segs[1].startUtcMs, segs[1].endUtcMs)
		assertEquals(clock.millis(), segs[3].startUtcMs)
	}

	@Test
	fun arrivalAssignsClientToTravellingEntry() = runBlocking<Unit> {
		val t0 = clock.millis()
		val id = entries.start(StartRequest(SegmentKind.TRAVEL, null))
		clock.advance(40)
		entries.arriveAtClient(id, client, t0 + 25 * 60_000)
		val e = db.entryDao().entry(id)!!
		assertEquals(client, e.clientId)
		assertEquals(acme, e.companyId)
		assertEquals(SegmentKind.WORK, e.mode)
		assertEquals(t0 + 25 * 60_000, db.entryDao().segments(id).last().startUtcMs)
	}

	@Test
	fun samplesAreTaggedWithTheOpenSegment() = runBlocking<Unit> {
		val locations = LocationRepository(db)
		val a = entries.start(StartRequest(SegmentKind.WORK, client))
		val fix = it.infraconsulting.activitytrack.domain.geo.Fix(
			clock.millis(), it.infraconsulting.activitytrack.domain.geo.GeoPoint(45.4, 12.3), 8.0,
		)
		locations.record(fix, "fused", nowMs = fix.atMs)
		val seg = db.entryDao().segments(a).single()
		assertEquals(listOf(fix), locations.fixesOfSegment(seg.id))
		/* a stale cached fix is dropped */
		locations.record(fix.copy(atMs = fix.atMs - 600_000), "fused", nowMs = fix.atMs)
		assertEquals(1, locations.fixesOfSegment(seg.id).size)
		/* a stopped entry gets no periodic samples, but an explicit segment still does */
		entries.stop(a)
		locations.record(fix.copy(atMs = fix.atMs + 1), "fused", nowMs = fix.atMs + 1)
		assertEquals(1, locations.fixesOfSegment(seg.id).size)
		locations.record(fix.copy(atMs = fix.atMs + 2), "gps", seg.id, nowMs = fix.atMs + 2)
		assertEquals(2, locations.fixesOfSegment(seg.id).size)
	}

	@Test
	fun absencesAndEntriesHandleDays() = runBlocking<Unit> {
		val zone = ZoneOffset.UTC
		val today = java.time.LocalDate.now(clock)
		val tomorrow = today.plusDays(1)
		master.saveSchedule(WorkScheduleEntity(dayOfWeek = tomorrow.dayOfWeek.value, startMin = 9 * 60, endMin = 18 * 60))
		assertEquals(emptySet(), entries.handledDays(today, tomorrow, zone))
		/* late entry at 10:00: a LEAVE from 9:00 (ACTIVITY-MODEL.md 3.1) */
		entries.recordLateEntry(tomorrow, 10 * 60)
		val absence = entries.absences.first().single()
		assertEquals(it.infraconsulting.activitytrack.domain.activity.AbsenceKind.LEAVE, absence.absenceKind)
		assertEquals(listOf(SegmentKind.ABSENCE), db.entryDao().segments(absence.id).map { it.kind })
		assertEquals(setOf(tomorrow), entries.handledDays(today, tomorrow, zone))
		entries.start(StartRequest(SegmentKind.WORK, client))
		assertEquals(setOf(today, tomorrow), entries.handledDays(today, tomorrow, zone))
		/* a day without working hours: nothing to record */
		assertEquals(Reason.INVALID_DATE, reason { entries.createAbsence(AbsenceRequest(fromEpochDay = today.plusDays(2).toEpochDay())) })
	}

	@Test
	fun backdatedTravelTakesTheLaterSamples() = runBlocking<Unit> {
		val locations = LocationRepository(db)
		val t0 = clock.millis()
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		val p = it.infraconsulting.activitytrack.domain.geo.GeoPoint(45.4, 12.3)
		for (m in listOf(0L, 5L, 10L, 15L)) {
			clock.now = Instant.ofEpochMilli(t0 + m * 60_000)
			locations.record(it.infraconsulting.activitytrack.domain.geo.Fix(clock.millis(), p, 8.0), "fused", nowMs = clock.millis())
		}
		entries.quick(id, QuickAction.TRAVEL, t0 + 10 * 60_000)
		val (work, travel) = db.entryDao().segments(id)
		assertEquals(2, locations.fixesOfSegment(work.id).size)
		assertEquals(2, locations.fixesOfSegment(travel.id).size)
	}

	@Test
	fun expensesNeedAmountAndDescription() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.TRAVEL, client))
		assertEquals(Reason.INVALID_AMOUNT, reason { entries.addExpense(id, 0, "fuel") })
		assertEquals(Reason.BLANK_VALUE, reason { entries.addExpense(id, 1250, "  ") })
		val e = entries.addExpense(id, 1250, " fuel ")
		val row = entries.expenses(id).first().single()
		assertEquals(e, row.id)
		assertEquals("fuel", row.description)
		/* tied to the open TRAVEL segment */
		assertEquals(db.entryDao().segments(id).single().id, row.segmentId)
	}

	@Test
	fun backupIsStagedForRestore() = runBlocking<Unit> {
		entries.start(StartRequest(SegmentKind.WORK, client))
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		val backup = it.infraconsulting.activitytrack.data.storage.DatabaseBackup(db, ctx.cacheDir)
		val out = java.io.ByteArrayOutputStream()
		backup.writeZip("test", clock.instant(), out)
		val staged = backup.stage(out.toByteArray().inputStream(), ActivityTrackDatabase.VERSION)
		assertEquals(ActivityTrackDatabase.VERSION, staged.dbVersion)
		kotlin.test.assertTrue(staged.file.length() > 0)
		/* a zip without database is refused */
		val empty = java.io.ByteArrayOutputStream()
		java.util.zip.ZipOutputStream(empty).use { z -> z.putNextEntry(java.util.zip.ZipEntry("manifest.json")); z.write("{}".toByteArray()); z.closeEntry() }
		assertFailsWith<it.infraconsulting.activitytrack.data.storage.DatabaseBackup.InvalidBackup> {
			backup.stage(empty.toByteArray().inputStream(), ActivityTrackDatabase.VERSION)
		}
	}

	@Test
	fun backupLimitsAndConcurrentSnapshots() = runBlocking<Unit> {
		entries.start(StartRequest(SegmentKind.WORK, client))
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		val backup = it.infraconsulting.activitytrack.data.storage.DatabaseBackup(db, ctx.cacheDir)
		/* two backups at once (daily and before a policy): both complete */
		val zips = kotlinx.coroutines.coroutineScope {
			(1..2).map { async(kotlinx.coroutines.Dispatchers.IO) { java.io.ByteArrayOutputStream().also { backup.writeZip("test", clock.instant(), it) }.toByteArray() } }.awaitAll()
		}
		for (z in zips)
			assertEquals(ActivityTrackDatabase.VERSION, backup.stage(z.inputStream(), ActivityTrackDatabase.VERSION).dbVersion)
		kotlin.test.assertTrue(ctx.cacheDir.listFiles()!!.none { it.name.startsWith("snapshot") })
		/* a manifest over 64 KiB is refused */
		val big = java.io.ByteArrayOutputStream()
		java.util.zip.ZipOutputStream(big).use { z -> z.putNextEntry(java.util.zip.ZipEntry("manifest.json")); z.write(ByteArray(70_000) { ' '.code.toByte() }); z.closeEntry() }
		val m = assertFailsWith<it.infraconsulting.activitytrack.data.storage.DatabaseBackup.InvalidBackup> {
			backup.stage(big.toByteArray().inputStream(), ActivityTrackDatabase.VERSION)
		}
		kotlin.test.assertTrue(m.message!!.contains("manifest.json"))
		/* a database over the cap is refused, nothing left in the cache */
		val d = assertFailsWith<it.infraconsulting.activitytrack.data.storage.DatabaseBackup.InvalidBackup> {
			backup.stage(zips[0].inputStream(), ActivityTrackDatabase.VERSION, maxDbBytes = 1024)
		}
		kotlin.test.assertTrue(d.message!!.contains("larger"))
		kotlin.test.assertFalse(java.io.File(ctx.cacheDir, "restore.db").exists())
	}

	@Test
	fun underAMinuteIsAnError() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(30)
		val t0 = clock.millis()
		/* a manual segment of 59 s */
		assertEquals(Reason.TOO_SHORT, reason { entries.addManualSegment(id, SegmentKind.PAUSE, t0 - 120_000, t0 - 61_000) })
		/* 30 min recorded: not discardable */
		assertEquals(false, entries.isDiscardable(id))
	}

	@Test
	fun aShortEntryIsDiscardedUnlessSomethingWasAdded() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		assertEquals(true, entries.isDiscardable(id))
		entries.discard(id)
		/* born running: to the bin, restorable */
		kotlin.test.assertNotNull(db.entryDao().entry(id)!!.deletedUtcMs)

		/* a to-do started by mistake goes back to TODO, its description kept */
		val todo = entries.saveTodo(null, TodoRequest(acme, client, activity = "Patch the server"))
		clock.advance(10)
		entries.startTodo(todo, SegmentKind.WORK)
		assertEquals(true, entries.isDiscardable(todo))
		entries.discard(todo)
		val back = db.entryDao().entry(todo)!!
		assertEquals(ActivityStatus.TODO, back.status)
		assertNull(back.deletedUtcMs)
		assertEquals("Patch the server", back.activity)
		assertEquals(emptyList(), db.entryDao().segments(todo))

		val kept = entries.start(StartRequest(SegmentKind.WORK, client))
		entries.addExpense(kept, 1250, "parking")
		assertEquals(false, entries.isDiscardable(kept))
		assertEquals(Reason.TOO_SHORT, reason { entries.discard(kept) })
	}

	@Test
	fun anEditedSegmentTakesTheNewTimes() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		val t0 = clock.millis()
		clock.advance(120)
		entries.stop(id)
		val s = db.entryDao().segments(id).single()
		entries.updateSegment(s.id, t0 + 300_000, t0 + 7_000_000)
		val c = db.entryDao().segment(s.id)!!
		assertEquals(t0 + 300_000, c.startUtcMs)
		assertEquals(t0 + 7_000_000, c.endUtcMs)
		assertEquals(SegmentSource.TRACKED, c.source)
		assertEquals(Reason.TOO_SHORT, reason { entries.updateSegment(s.id, t0, t0 + 30_000) })
	}

	@Test
	fun tagsAreNormalizedAndReplaced() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		entries.setTags(id, listOf("#Urgente", "lavoro remoto", "urgente"))
		assertEquals(listOf("lavoro-remoto", "urgente"), entries.tags(id).first())
		entries.setTags(id, listOf("remoto"))
		assertEquals(listOf("remoto"), entries.tags(id).first())
		assertEquals(mapOf(id to listOf("remoto")), entries.tagsOf(listOf(id)))
	}

	/* issue #133: a tag of the master data renamed takes the entries with it; deleted, they keep the name */
	@Test
	fun aRenamedTagFollowsOnTheEntries() = runBlocking<Unit> {
		val a = entries.start(StartRequest(SegmentKind.WORK, client))
		entries.setTags(a, listOf("urgente", "remoto"))
		val tagId = master.saveTag(it.infraconsulting.activitytrack.data.db.TagEntity(name = "#Urgente", color = 0xD64545))
		val tag = master.tags.first().single()
		assertEquals("urgente", tag.name)
		assertEquals(Reason.DUPLICATE_NAME, reason { master.saveTag(it.infraconsulting.activitytrack.data.db.TagEntity(name = "urgente")) })
		master.saveTag(tag.copy(name = "remoto"))
		assertEquals(listOf("remoto"), entries.tags(a).first())
		master.saveTag(tag.copy(id = tagId, name = "critico"))
		assertEquals(listOf("critico"), entries.tags(a).first())
		master.deleteTag(master.tags.first().single())
		assertEquals(listOf("critico"), entries.tags(a).first())
	}

	/* issue #28: contacts, the call log, the pause a call makes */
	@Test
	fun contactsAndCalls() = runBlocking<Unit> {
		val calls = CallRepository(db)
		assertEquals(Reason.BLANK_NAME, reason { calls.saveContact(ContactEntity(clientId = client, name = " "), listOf("333 1234567")) })
		/* a contact may have no number (issue #44, e-mail only): too short ones are dropped */
		val bare = calls.saveContact(ContactEntity(clientId = client, name = "Bianchi"), listOf("", "12"))
		assertEquals(emptyList(), db.callDao().allContacts().first { it.contact.id == bare }.phones)
		val id = calls.saveContact(ContactEntity(clientId = client, name = " Rossi "), listOf("+39 333 123 4567", "0461 123456"))
		assertEquals(id, calls.contactOf("3331234567")?.contact?.id)
		assertEquals("Rossi", calls.contactOf("+390461123456")?.contact?.name)
		assertNull(calls.contactOf("3339999999"))
		val call = CallLogEntity(contactId = id, clientId = client, direction = CallDirection.INCOMING, startUtcMs = 1_000, endUtcMs = 61_000)
		assertEquals(true, calls.record(call))
		assertEquals(false, calls.record(call))
		assertEquals(listOf("Rossi"), calls.callsOf(client).first().map { it.contactName })
		calls.deleteContact(calls.contactOf("3331234567")!!.contact)
		assertNull(calls.contactOf("3331234567"))
	}

	@Test
	fun callPauseSplitsTheOpenWork() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		val t0 = clock.now.toEpochMilli()
		clock.advance(30)
		val from = t0 + 10 * 60_000
		val to = t0 + 15 * 60_000
		assertEquals(false, entries.insertCallPause(id, from, clock.now.toEpochMilli() + 60_000, "Telefonata Bank"))
		assertEquals(true, entries.insertCallPause(id, from, to, "Telefonata Bank"))
		val segs = entries.segments(id).first()
		assertEquals(listOf(SegmentKind.WORK, SegmentKind.PAUSE, SegmentKind.WORK), segs.map { it.kind })
		assertEquals(listOf(t0 to from, from to to, to to null), segs.map { it.startUtcMs to it.endUtcMs })
		assertEquals("Telefonata Bank", segs[1].note)
		entries.setSegmentNote(segs[1].id, "  ")
		assertNull(entries.segments(id).first()[1].note)
		/* the open work began after the call: nothing */
		assertEquals(false, entries.insertCallPause(id, from, to, "x"))
	}

	/* issue #40: a segment saved over others of the entry wins once confirmed */
	@Test
	fun overlapResolvedOnConfirm() = runBlocking<Unit> {
		val t0 = clock.now.toEpochMilli()
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(60)
		entries.stop(id)
		val m = { min: Int -> t0 + min * 60_000L }
		assertEquals(1, entries.segmentConflicts(id, null, m(20), m(30)).size)
		assertEquals(Reason.OVERLAPPING_SEGMENTS, reason { entries.addManualSegment(id, SegmentKind.PAUSE, m(20), m(30)) })
		entries.addManualSegment(id, SegmentKind.PAUSE, m(20), m(30), resolve = true)
		var segs = entries.segments(id).first()
		assertEquals(listOf(SegmentKind.WORK, SegmentKind.PAUSE, SegmentKind.WORK), segs.map { it.kind })
		assertEquals(listOf(m(0) to m(20), m(20) to m(30), m(30) to m(60)), segs.map { it.startUtcMs to it.endUtcMs })
		/* the pause stretched to 10–40: the previous work is shortened, the next one starts later */
		entries.updateSegment(segs[1].id, m(10), m(40), resolve = true)
		segs = entries.segments(id).first()
		assertEquals(listOf(m(0) to m(10), m(10) to m(40), m(40) to m(60)), segs.map { it.startUtcMs to it.endUtcMs })
	}

	/* issue #57: the running segment moves its start; a pause it now covers is shortened once confirmed */
	@Test
	fun runningSegmentStartMoves() = runBlocking<Unit> {
		val t0 = clock.now.toEpochMilli()
		val m = { min: Int -> t0 + min * 60_000L }
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(30)
		entries.quick(id, QuickAction.PAUSE)
		clock.advance(20)
		entries.quick(id, QuickAction.WORK)
		clock.advance(10)
		val running = entries.segments(id).first().last()
		assertNull(running.endUtcMs)
		assertEquals(Reason.INVALID_SEGMENT, reason { entries.moveRunningStart(running.id, m(61)) })
		assertEquals(1, entries.segmentConflicts(id, running.id, m(40), EntryRepository.RUNNING_END).size)
		assertEquals(Reason.OVERLAPPING_SEGMENTS, reason { entries.moveRunningStart(running.id, m(40)) })
		entries.moveRunningStart(running.id, m(40), resolve = true)
		val segs = entries.segments(id).first()
		assertEquals(listOf(SegmentKind.WORK, SegmentKind.PAUSE, SegmentKind.WORK), segs.map { it.kind })
		assertEquals(listOf(m(0) to m(30), m(30) to m(40), m(40) to null), segs.map { it.startUtcMs to it.endUtcMs })
	}

	/* issue #48: deleting an entry a plugin sent keeps its remote id for the next sync */
	@Test
	fun deletedEntryKeepsItsRemoteIds() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(30)
		entries.stop(id)
		val plugins = db.pluginDao()
		plugins.recordSent(it.infraconsulting.activitytrack.data.db.RemoteRecordEntity(id, "antares", "2026-10-23#0", "r-1", "h", clock.millis()))
		assertEquals(true, entries.deletion(id).sent)
		entries.delete(id)
		assertEquals(listOf("r-1"), plugins.deleted(acme, "antares").map { it.remoteId })
		plugins.forgetDeleted("antares", "r-1")
		assertEquals(emptyList<Any>(), plugins.deleted(acme, "antares"))
	}

	/* issue #40 and 1.0: deleting moves to the bin; for good only from there and without attachments */
	@Test
	fun deleteRestorePurge() = runBlocking<Unit> {
		val now = clock.now.toEpochMilli()
		val id = entries.start(StartRequest(SegmentKind.WORK, client, activity = "Ticket"))
		clock.advance(30)
		entries.stop(id)
		val att = db.entryDao().insertAttachment(
			it.infraconsulting.activitytrack.data.db.AttachmentEntity(
				entryId = id, kind = it.infraconsulting.activitytrack.data.db.AttachmentKind.PHOTO, relPath = "x.jpg", mime = "image/jpeg", createdUtcMs = now,
			),
		)
		assertEquals(Reason.NOT_DELETED, reason { entries.purge(id) })
		entries.delete(id)
		assertEquals(clock.millis(), entries.find(id)?.deletedUtcMs)
		assertEquals(listOf(id), entries.deleted.first().map { it.id })
		assertEquals(Reason.NOT_ALLOWED, reason { entries.reopen(id) })
		entries.restore(id)
		assertNull(entries.find(id)?.deletedUtcMs)
		entries.delete(id)
		assertEquals(Reason.HAS_ATTACHMENTS, reason { entries.purge(id) })
		db.entryDao().deleteAttachment(att)
		entries.purge(id)
		assertNull(entries.find(id))
	}

	/* ACTIVITY-MODEL.md 3: a TODO with planned segments; the start drops them; tick closes another */
	@Test
	fun todoStartAndTick() = runBlocking<Unit> {
		val h = 3_600_000L
		val t0 = clock.millis()
		val todo = entries.saveTodo(
			null,
			TodoRequest(acme, client, "Patch", priority = 4, planned = listOf(SegmentInput(SegmentKind.WORK, t0 + h, t0 + 2 * h))),
		)
		assertEquals("TODO", state(todo))
		assertEquals(listOf(true), db.entryDao().segments(todo).map { it.planned })
		/* the planned segment never counts */
		assertEquals(0L, entries.recordedMs(todo))
		assertEquals(Reason.BLANK_VALUE, reason { entries.saveTodo(null, TodoRequest(acme, activity = " ")) })
		entries.startTodo(todo, SegmentKind.WORK)
		assertEquals("ACTIVE", state(todo))
		assertEquals(listOf(false), db.entryDao().segments(todo).map { it.planned })
		val other = entries.saveTodo(null, TodoRequest(acme, activity = "Call back"))
		entries.tick(other)
		assertEquals("CLOSED", state(other))
		assertEquals(clock.millis(), entries.find(other)?.closedUtcMs)
		assertEquals(Reason.NOT_ALLOWED, reason { entries.tick(todo) })
	}

	/* a plugin import closing a planned TODO (PluginSync): the tick transition, so later commands still pass the invariants */
	@Test
	fun aRemoteDoneTicksOffWithThePlannedSegments() = runBlocking<Unit> {
		val t0 = clock.millis()
		val todo = entries.saveTodo(
			null,
			TodoRequest(acme, client, "Patch", externalKey = "T-1", planned = listOf(SegmentInput(SegmentKind.WORK, t0 + 3_600_000, t0 + 7_200_000))),
		)
		val (ticked, deletePlanned) = remoteTick(entries.find(todo)!!, clock.millis())!!
		db.pluginDao().updateActivity(ticked, deletePlanned)
		assertEquals("CLOSED", state(todo))
		assertEquals(emptyList(), db.entryDao().segments(todo))
		/* every command verifies the invariants: no InvariantViolationException */
		entries.setActivity(todo, "Patched")
	}

	/* the Home "Chiuse" tab: a ticked task has no segment end, it is listed by when it was closed; untick takes it out */
	@Test
	fun tickedTasksAreListedByClosingTime() = runBlocking<Unit> {
		val from = clock.millis() - 1_000
		val first = entries.saveTodo(null, TodoRequest(acme, activity = "First"))
		val second = entries.saveTodo(null, TodoRequest(acme, activity = "Second"))
		val untouched = entries.saveTodo(null, TodoRequest(acme, activity = "Untouched"))
		entries.tick(first)
		clock.advance(60)
		entries.tick(second)
		assertEquals(listOf(second, first), entries.tickedSince(from, 10).first().map { it.id })
		assertEquals(emptyList(), entries.tickedSince(clock.millis() + 1, 10).first())
		assertEquals(listOf(second), entries.tickedSince(from, 1).first().map { it.id })
		entries.untick(second)
		assertEquals(listOf(first), entries.tickedSince(from, 10).first().map { it.id })
		assertEquals("TODO", state(untouched))
	}

	/* 4.1, 4.2: rounded stored times and closing up to 60 minutes ahead, only with editable times */
	@Test
	fun roundedTimesAndFutureClose() = runBlocking<Unit> {
		var rules = TimeRules(editableTimes = true, roundStoredTimes = true)
		val repo = EntryRepository(db, clock, times = { rules })
		clock.now = Instant.parse("2026-10-14T08:07:00Z")
		val id = repo.start(StartRequest(SegmentKind.WORK, client, activity = "x"))
		/* a start rounds down to the work quantum (15) */
		assertEquals(Instant.parse("2026-10-14T08:00:00Z").toEpochMilli(), db.entryDao().segments(id).single().startUtcMs)
		clock.now = Instant.parse("2026-10-14T09:31:00Z")
		repo.stop(id)
		/* a stop rounds up */
		assertEquals(Instant.parse("2026-10-14T09:45:00Z").toEpochMilli(), db.entryDao().segments(id).single().endUtcMs)
		/* a typed end up to 60 minutes ahead */
		val b = repo.start(StartRequest(SegmentKind.WORK, client, activity = "y", startUtcMs = clock.millis()))
		assertEquals(Reason.FUTURE_TIME, reason { repo.stop(b, clock.millis() + 61 * 60_000) })
		repo.stop(b, clock.millis() + 30 * 60_000)
		/* another start before that end pulls it back to the new start */
		clock.advance(10)
		val c = repo.start(StartRequest(SegmentKind.WORK, client, activity = "z", startUtcMs = clock.millis()))
		assertEquals(clock.millis(), db.entryDao().segments(b).single().endUtcMs)
		/* without editable times neither rounding nor future ends */
		rules = TimeRules(editableTimes = false, roundStoredTimes = true)
		assertEquals(Reason.FUTURE_TIME, reason { repo.stop(c, clock.millis() + 60_000) })
	}

	/* travel from work, "yes", with rounded times: the rounded switch may lie ahead of now; both activities share it */
	@Test
	fun travelFromWorkWithRoundedTimesAhead() = runBlocking<Unit> {
		val repo = EntryRepository(db, clock, times = { TimeRules(editableTimes = true, roundStoredTimes = true) })
		clock.now = Instant.parse("2026-10-14T08:00:00Z")
		val id = repo.start(StartRequest(SegmentKind.WORK, client, activity = "x"))
		/* nearest multiple of 5 (travel) to 09:03 is 09:05 */
		clock.now = Instant.parse("2026-10-14T09:03:00Z")
		val travel = repo.travelFromWork(id, workDone = true)
		val at = Instant.parse("2026-10-14T09:05:00Z").toEpochMilli()
		assertEquals(at, db.entryDao().segments(id).single().endUtcMs)
		assertEquals(at, db.entryDao().segments(travel).single().startUtcMs)
		assertEquals("CLOSED", state(id))
		assertEquals("ACTIVE", state(travel))
	}

	/* 3.2: stop with the return trip, as long as the outbound one, samples swapped */
	@Test
	fun stopWithReturnTrip() = runBlocking<Unit> {
		val locations = LocationRepository(db)
		val t0 = clock.millis()
		val id = entries.start(StartRequest(SegmentKind.TRAVEL, client))
		locations.record(it.infraconsulting.activitytrack.domain.geo.Fix(t0, it.infraconsulting.activitytrack.domain.geo.GeoPoint(45.0, 11.0), 8.0), "fused", nowMs = t0)
		clock.advance(40)
		locations.record(it.infraconsulting.activitytrack.domain.geo.Fix(clock.millis(), it.infraconsulting.activitytrack.domain.geo.GeoPoint(45.5, 11.5), 8.0), "fused", nowMs = clock.millis())
		entries.switchTo(id, SegmentKind.WORK)
		clock.advance(120)
		assertEquals(ReturnChoice.entries.toList(), entries.returnChoices(id))
		val ret = entries.stopWithReturn(id)
		val segs = db.entryDao().segments(id)
		assertEquals(listOf(SegmentKind.TRAVEL, SegmentKind.WORK, SegmentKind.TRAVEL), segs.map { it.kind })
		assertEquals(40L * 60_000, segs[2].endUtcMs!! - segs[2].startUtcMs)
		assertEquals(listOf(45.5, 45.0), locations.fixesOfSegment(ret).map { it.point.lat })
		assertEquals("CLOSED", state(id))
	}

	/* 3: travel asked during work: "work done?" yes = a new anonymous travel, no = a switch */
	@Test
	fun travelFromWork() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(30)
		val travel = entries.travelFromWork(id, workDone = true)
		assertEquals("CLOSED", state(id))
		assertEquals("ACTIVE", state(travel))
		assertNull(entries.find(travel)?.clientId)
		val again = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(30)
		assertEquals(again, entries.travelFromWork(again, workDone = false))
		assertEquals(SegmentKind.TRAVEL, entries.find(again)?.mode)
	}

	/* issue #41: consumables; #44: contacts without numbers, e-mail, the entry's contact */
	@Test
	fun materialsAndEntryContact() = runBlocking<Unit> {
		val materials = MaterialRepository(db, clock)
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		assertEquals(Reason.BLANK_NAME, reason { materials.saveMaterial(it.infraconsulting.activitytrack.data.db.MaterialEntity(name = " ")) })
		val cable = materials.saveMaterial(it.infraconsulting.activitytrack.data.db.MaterialEntity(name = "Cavo", unit = "m", unitCostCents = 150))
		val line = { d: String, q: Double?, c: Long? ->
			it.infraconsulting.activitytrack.data.db.EntryMaterialEntity(entryId = id, materialId = cable, description = d, quantity = q, unitCostCents = c, createdUtcMs = 0)
		}
		assertEquals(Reason.BLANK_VALUE, reason { materials.saveLine(line("", 1.0, 150)) })
		assertEquals(Reason.INVALID_AMOUNT, reason { materials.saveLine(line("Cavo", 0.0, 150)) })
		materials.saveLine(line("Cavo", 2.5, 150))
		materials.saveLine(line("Fascette", null, null))
		val lines = materials.lines(id).first()
		assertEquals(listOf(375L, null), lines.map { it.totalCents })
		/* deleting the master data keeps the recorded lines */
		materials.deleteMaterial(cable)
		assertEquals(2, materials.lines(id).first().size)

		val calls = CallRepository(db)
		assertEquals(Reason.INVALID_VALUE, reason { calls.saveContact(ContactEntity(clientId = client, name = "Rossi", email = "nope"), emptyList()) })
		val rossi = calls.saveContact(ContactEntity(clientId = client, name = "Rossi", email = "rossi@example.com"), emptyList())
		entries.updateDetails(id, EntryDetails(acme, client, null, "Lavoro", null, null, null, rossi))
		assertEquals(rossi, entries.find(id)?.contactId)
		/* a contact of another client does not stick */
		val bank2 = master.saveClient(ClientEntity(companyId = acme, name = "Other bank"))
		entries.updateDetails(id, EntryDetails(acme, bank2, null, "Lavoro", null, null, null, rossi))
		assertNull(entries.find(id)?.contactId)
		/* deleting the entry sends it to the bin with its lines; purging deletes them */
		entries.delete(id)
		assertEquals(2, materials.lines(id).first().size)
		entries.purge(id)
		assertEquals(0, materials.lines(id).first().size)
	}
}
