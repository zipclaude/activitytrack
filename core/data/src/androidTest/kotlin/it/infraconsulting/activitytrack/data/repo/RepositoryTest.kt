/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.WorkScheduleEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.ExternalRefKind
import it.infraconsulting.activitytrack.domain.model.IllegalTransitionException
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
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
		entries.apply(id, EntryCommand.WORK)
		clock.advance(60)
		entries.apply(id, EntryCommand.PAUSE)
		clock.advance(15)
		entries.apply(id, EntryCommand.WORK)
		clock.advance(45)
		entries.apply(id, EntryCommand.SUSPEND)
		clock.advance(60)
		entries.apply(id, EntryCommand.WORK)
		clock.advance(10)
		entries.apply(id, EntryCommand.STOP)

		val segs = db.entryDao().segments(id)
		assertEquals(
			listOf(SegmentKind.TRAVEL, SegmentKind.WORK, SegmentKind.PAUSE, SegmentKind.WORK, SegmentKind.WORK),
			segs.map { it.kind },
		)
		assertEquals(listOf(30L, 60L, 15L, 45L, 10L), segs.map { (it.endUtcMs!! - it.startUtcMs) / 60_000 })
		assertEquals(EntryStatus.TO_COMPLETE, db.entryDao().entry(id)?.status)

		assertFailsWith<IllegalTransitionException> { runBlocking { entries.apply(id, EntryCommand.PAUSE) } }
	}

	@Test
	fun completionRequiresActivity() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(60)
		entries.apply(id, EntryCommand.STOP)
		val details = EntryDetails(acme, client, null, "  ", null, null, null)
		assertEquals(Reason.COMPLETION_INCOMPLETE, reason { entries.complete(id, details) })
		assertNull(entries.lastActivity(client))
		/* the failed completion rolled back */
		assertEquals(EntryStatus.TO_COMPLETE, db.entryDao().entry(id)?.status)
		entries.complete(id, details.copy(activity = "Patch Tuesday"))
		assertEquals(EntryStatus.CLOSED, db.entryDao().entry(id)?.status)
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

	@Test
	fun backwardsClockNeverOverlaps() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(-10)
		entries.apply(id, EntryCommand.PAUSE)
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
			mapOf(a to EntryCommand.SUSPEND, b to EntryCommand.STOP),
		)
		val dao = db.entryDao()
		assertEquals(EntryStatus.SUSPENDED, dao.entry(a)?.status)
		assertEquals(EntryStatus.TO_COMPLETE, dao.entry(b)?.status)
		assertEquals(EntryStatus.ACTIVE, dao.entry(c)?.status)

		/* an illegal command for another entry rolls back the new start too */
		val before = dao.observeOpenEntries().first().size
		assertFailsWith<IllegalTransitionException> {
			runBlocking { entries.start(StartRequest(SegmentKind.WORK, client), mapOf(a to EntryCommand.SUSPEND)) }
		}
		assertEquals(before, dao.observeOpenEntries().first().size)

		/* a is the only suspended entry; b was stopped */
		assertEquals(a, entries.suspendedToResume(b)?.id)
		assertNull(entries.suspendedToResume(a))
	}

	@Test
	fun clientDefaultsWithinThirtyDays() = runBlocking<Unit> {
		val role = master.saveRole(it.infraconsulting.activitytrack.data.db.RoleEntity(name = "Consultant"))
		val id = entries.start(StartRequest(SegmentKind.WORK, client, roleId = role, activity = "Firewall review"))
		assertEquals(ClientDefaults("Firewall review", role), entries.clientDefaults(client))
		clock.advance(31L * 24 * 60)
		assertNull(entries.clientDefaults(client))
		entries.apply(id, EntryCommand.STOP)
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
		assertEquals(EntryStatus.TO_COMPLETE, e.status)
		assertEquals(confirmed, db.entryDao().segments(id).single().endUtcMs)
	}

	@Test
	fun pauseReminderMovesBase() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		entries.apply(id, EntryCommand.PAUSE)
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
		entries.apply(id, EntryCommand.STOP)
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
		entries.apply(id, EntryCommand.STOP)
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
	fun backdatedCommandsAreClamped() = runBlocking<Unit> {
		val t0 = clock.millis()
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		clock.advance(30)
		/* departure detected 10 min after the start */
		entries.apply(id, EntryCommand.TRAVEL, t0 + 10 * 60_000)
		/* an arrival before the travel start is clamped to it; one in the future to now */
		clock.advance(30)
		entries.apply(id, EntryCommand.WORK, t0)
		entries.apply(id, EntryCommand.PAUSE, clock.millis() + 3_600_000)
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
		entries.apply(a, EntryCommand.STOP)
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
		assertEquals(emptySet(), entries.handledDays(today, tomorrow, zone))
		entries.recordAbsence(tomorrow, it.infraconsulting.activitytrack.data.db.AbsenceKind.LATE_ENTRY, 10 * 60)
		/* recording the same kind again replaces it */
		entries.recordAbsence(tomorrow, it.infraconsulting.activitytrack.data.db.AbsenceKind.LATE_ENTRY, 11 * 60)
		assertEquals(1, db.entryDao().absencesBetween(tomorrow.toEpochDay(), tomorrow.toEpochDay()).size)
		assertEquals(setOf(tomorrow), entries.handledDays(today, tomorrow, zone))
		entries.start(StartRequest(SegmentKind.WORK, client))
		assertEquals(setOf(today, tomorrow), entries.handledDays(today, tomorrow, zone))
		assertEquals(
			Reason.INVALID_TIME,
			reason { entries.recordAbsence(today, it.infraconsulting.activitytrack.data.db.AbsenceKind.FULL_DAY, 600) },
		)
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
		entries.apply(id, EntryCommand.TRAVEL, t0 + 10 * 60_000)
		val (work, travel) = db.entryDao().segments(id)
		assertEquals(2, locations.fixesOfSegment(work.id).size)
		assertEquals(2, locations.fixesOfSegment(travel.id).size)
	}

	@Test
	fun completionWithClientOnlyAfterConfirmation() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		entries.apply(id, EntryCommand.STOP)
		val d = EntryDetails(acme, client, null, null, null, null, null)
		assertEquals(Reason.COMPLETION_INCOMPLETE, reason { entries.complete(id, d) })
		entries.complete(id, d, allowEmpty = true)
		assertEquals(EntryStatus.CLOSED, db.entryDao().entry(id)!!.status)
		assertNull(db.entryDao().entry(id)!!.activity)
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
		assertNull(db.entryDao().entry(id))

		val kept = entries.start(StartRequest(SegmentKind.WORK, client))
		entries.addExpense(kept, 1250, "parking")
		assertEquals(false, entries.isDiscardable(kept))
		assertEquals(Reason.TOO_SHORT, reason { entries.discard(kept) })
	}

	@Test
	fun aCorrectedSegmentKeepsItsTrackedTimes() = runBlocking<Unit> {
		val id = entries.start(StartRequest(SegmentKind.WORK, client))
		val t0 = clock.millis()
		clock.advance(120)
		entries.apply(id, EntryCommand.STOP)
		val s = db.entryDao().segments(id).single()
		entries.updateSegment(s.id, t0 + 300_000, t0 + 7_000_000)
		val c = db.entryDao().segment(s.id)!!
		assertEquals(SegmentSource.CORRECTED, c.source)
		assertEquals(t0, c.origStartUtcMs)
		assertEquals(t0 + 7_200_000, c.origEndUtcMs)
		/* a second edit keeps the first originals */
		entries.updateSegment(s.id, t0 + 600_000, t0 + 7_000_000)
		assertEquals(t0, db.entryDao().segment(s.id)!!.origStartUtcMs)
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
}
