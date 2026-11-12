/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.interchange

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.ClientSiteEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.DayOffEntity
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.StartRequest
import it.infraconsulting.activitytrack.domain.interchange.FileFormat
import it.infraconsulting.activitytrack.domain.interchange.GenericConnector
import it.infraconsulting.activitytrack.domain.interchange.Interchange
import it.infraconsulting.activitytrack.domain.interchange.XTodo
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class InterchangeTest {
	private lateinit var a: ActivityTrackDatabase
	private lateinit var b: ActivityTrackDatabase
	private val clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC)

	private fun memory() = Room.inMemoryDatabaseBuilder(
		InstrumentationRegistry.getInstrumentation().targetContext,
		ActivityTrackDatabase::class.java,
	).build()

	@Before
	fun setUp() = runBlocking<Unit> {
		a = memory()
		b = memory()
		val m = a.masterDataDao()
		val acme = m.insertCompany(CompanyEntity(name = "Acme", externalCode = "ACME"))
		val other = m.insertCompany(CompanyEntity(name = "Other"))
		val bank = m.insertClient(ClientEntity(companyId = acme, name = "Bank", externalCode = "C-1"))
		/* same client name in two companies */
		m.insertClient(ClientEntity(companyId = other, name = "Bank"))
		m.insertClientSite(ClientSiteEntity(clientId = bank, label = "HQ", lat = 45.46, lon = 9.19, address = "Via Roma 1"))
		val entries = EntryRepository(a, clock)
		/* a TODO activity started (ACTIVITY-MODEL.md 3), another left to do, an absence with times */
		val todo = entries.saveTodo(
			null,
			it.infraconsulting.activitytrack.data.repo.TodoRequest(
				acme, bank, "Patch", dueEpochDay = 20_744, planEpochDay = 20_743, planStartMin = 9 * 60, durationMin = 45, priority = 4, externalKey = "T-9",
			),
		)
		entries.saveTodo(null, it.infraconsulting.activitytrack.data.repo.TodoRequest(acme, bank, "Backup", priority = 2, externalKey = "T-10"))
		val id = entries.start(StartRequest(SegmentKind.WORK, bank, activity = "Patched", todoId = todo))
		entries.addExpense(id, 1250, "Parking")
		entries.addExternalRef(id, "INC1")
		entries.setTags(id, listOf("remoto"))
		entries.apply(id, EntryCommand.STOP)
		entries.createAbsence(it.infraconsulting.activitytrack.data.repo.AbsenceRequest(fromEpochDay = 20_745, startMin = 9 * 60, endMin = 630))
		m.upsertDayOff(DayOffEntity(20_800, "Patrono"))
	}

	@After
	fun tearDown() {
		a.close()
		b.close()
	}

	private suspend fun export(db: ActivityTrackDatabase): Interchange =
		Exporter(db).build(ExportFilter(siteCoordinates = true, zone = ZoneId.of("UTC")), "test", clock.instant())

	/* through the generic XML file, as a real transfer would */
	private fun viaXml(data: Interchange): Interchange {
		val out = ByteArrayOutputStream()
		GenericConnector.write(FileFormat.XML, data, out)
		return GenericConnector.read(FileFormat.XML, out.toByteArray().inputStream())
	}

	@Test
	fun roundTripIsLosslessAndIdempotent() = runBlocking<Unit> {
		val first = viaXml(export(a))
		val report = Importer(b, clock).import(first)
		assertTrue(report.skipped.isEmpty(), "skipped: ${report.skipped}")
		assertEquals(2, report.inserted["companies"])
		assertEquals(2, report.inserted["clients"])
		assertEquals(3, report.inserted["entries"])
		assertEquals(export(a).copy(createdUtc = null), export(b).copy(createdUtc = null))

		val again = Importer(b, clock).import(first)
		assertEquals(0, again.total)
		assertTrue(again.conflicts.isEmpty(), "conflicts: ${again.conflicts}")
	}

	@Test
	fun tagFilterKeepsOnlyTaggedEntries() = runBlocking<Unit> {
		val tagged = Exporter(a).build(ExportFilter(zone = ZoneId.of("UTC"), tag = "remoto"), "test", clock.instant())
		assertEquals(listOf("remoto"), tagged.entries.single().tags)
		val none = Exporter(a).build(ExportFilter(zone = ZoneId.of("UTC"), tag = "altro"), "test", clock.instant())
		assertTrue(none.entries.isEmpty())
		/* the other sections are not filtered */
		assertEquals(2, none.companies.size)
	}

	@Test
	fun appDataWinsAndConflictsAreReported() = runBlocking<Unit> {
		Importer(b, clock).import(export(a))
		val changed = export(a).let { x -> x.copy(entries = x.entries.map { if (it.externalKey == "T-10") it.copy(activity = "Backup everything") else it }) }
		val report = Importer(b, clock).import(changed)
		assertEquals(listOf("entries"), report.conflicts.map { it.section })
		assertEquals("Backup", b.interchangeDao().allEntries().single { it.externalKey == "T-10" }.activity)
	}

	@Test
	fun partialImportAndUnknownReferences() = runBlocking<Unit> {
		/* a version 1 file: its to-do goes through the migration and arrives as an activity */
		val report = Importer(b, clock).import(Interchange(version = 1, todos = listOf(XTodo("T-1", "NOPE", title = "Orphan"))))
		assertEquals(0, report.total)
		assertEquals("unknown company NOPE", report.skipped.single().detail)
	}
}
