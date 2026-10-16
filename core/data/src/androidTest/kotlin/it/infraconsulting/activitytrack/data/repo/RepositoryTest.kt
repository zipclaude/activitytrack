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
import it.infraconsulting.activitytrack.domain.model.IllegalTransitionException
import it.infraconsulting.activitytrack.domain.model.SegmentKind
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
}
