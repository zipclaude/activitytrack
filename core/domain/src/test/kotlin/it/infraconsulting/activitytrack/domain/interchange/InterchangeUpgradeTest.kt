/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import it.infraconsulting.activitytrack.domain.activity.ActivityMigration
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/* A version 1 interchange file read as version 2 through the migration function (ACTIVITY-MODEL.md 6.2). */
class InterchangeUpgradeTest {
	private val rome = ZoneId.of("Europe/Rome")
	private val week = (1..5).map { DayWindow(it, 540, 1080) }
	private val now = Instant.parse("2026-11-11T09:00:00Z")

	private fun seg(from: String, to: String?) = XSegment("WORK", from, to)

	@Test
	fun todosEntriesAndAbsencesBecomeActivities() {
		val v1 = Interchange(
			version = 1,
			todos = listOf(
				XTodo(key = "T-1", companyKey = "ACME", clientKey = "C-1", title = "Patch", refs = listOf("INC1"), priority = 1, plannedDate = "2026-11-12"),
				XTodo(key = "T-2", companyKey = "ACME", title = "Done task", done = true),
				XTodo(key = "A-1", kind = "ABSENCE", title = "Ferie", plannedDate = "2026-11-13", plannedEndDate = "2026-11-16"),
				XTodo(key = "BAD", title = "x", dueDate = "13/11/2026"),
			),
			entries = listOf(
				XEntry(key = "e-1", companyKey = "ACME", clientKey = "C-1", todoKey = "T-1", status = "CLOSED", zone = "Europe/Rome",
					segments = listOf(seg("2026-11-10T08:00:00Z", "2026-11-10T09:00:00Z"))),
				XEntry(key = "e-2", companyKey = "ACME", clientKey = "C-1", status = "TO_COMPLETE", activity = "Free", zone = "Europe/Rome",
					segments = listOf(seg("2026-11-10T10:00:00Z", "2026-11-10T11:00:00Z"))),
			),
			absences = listOf(XAbsence("2026-11-17", "FULL_DAY")),
		)
		val r = InterchangeUpgrade.toV2(v1, rome, week, now = now)
		val d = r.data
		assertEquals(2, d.version)
		assertTrue(d.todos.isEmpty() && d.absences.isEmpty())
		assertEquals(listOf("BAD"), r.skipped.map { it.first })
		val byKey = d.entries.associateBy { it.key }
		/* T-1 open, its only entry closed: it stays a TODO of its own, the entry points to it */
		val t1 = byKey.getValue(SyncIds.canonical("T-1"))
		assertEquals("TODO", t1.status)
		assertEquals("T-1", t1.externalKey)
		assertEquals("ACME", t1.companyKey)
		assertEquals("C-1", t1.clientKey)
		assertEquals(5, t1.priority)
		assertEquals("2026-11-12", t1.plannedDate)
		assertEquals(listOf("INC1"), t1.refs)
		assertEquals(listOf(t1.key), byKey.getValue("e-1").refs)
		assertEquals(null, byKey.getValue("e-1").todoKey)
		/* statuses */
		assertEquals("CLOSED", byKey.getValue("e-2").status)
		assertEquals("CLOSED", byKey.getValue(SyncIds.canonical("T-2")).status)
		/* the absence to-do: Friday 13 and Monday 16, the weekend skipped */
		val a1 = byKey.getValue(SyncIds.canonical("A-1"))
		assertEquals("ABSENCE", a1.kind)
		assertEquals("HOLIDAY", a1.absenceKind)
		assertEquals(listOf("2026-11-13T08:00:00Z", "2026-11-16T08:00:00Z"), a1.segments.map { it.startUtc })
		assertTrue(a1.segments.all { it.kind == "ABSENCE" && it.source == "MANUAL" })
		/* the legacy absence: one more ABSENCE activity */
		assertEquals(2, d.entries.count { it.kind == "ABSENCE" })
		/* the uids of what was created follow the shared rule */
		assertEquals(ActivityMigration.uid(SyncIds.canonical("A-1"), "abs/2026-11-13/0").length, 36)
	}

	/* the import skips what still runs elsewhere (a segment open); a SUSPENDED entry of version 1 comes in */
	@Test
	fun aSuspendedEntryOfVersion1IsNotRunningElsewhere() {
		val v1 = Interchange(
			version = 1,
			entries = listOf(
				XEntry(key = "s", companyKey = "ACME", clientKey = "C-1", status = "SUSPENDED", zone = "Europe/Rome",
					segments = listOf(seg("2026-11-10T08:00:00Z", "2026-11-10T09:00:00Z"))),
				XEntry(key = "a", companyKey = "ACME", clientKey = "C-1", status = "ACTIVE", zone = "Europe/Rome",
					segments = listOf(seg("2026-11-10T08:00:00Z", null))),
			),
		)
		val byKey = InterchangeUpgrade.toV2(v1, rome, week, now = now).data.entries.associateBy { it.key }
		assertEquals("RUNNING", byKey.getValue("s").status)
		assertEquals(false, byKey.getValue("s").runsElsewhere())
		assertEquals(true, byKey.getValue("a").runsElsewhere())
	}

	@Test
	fun aVersion2FileStaysAsItIs() {
		val v2 = Interchange(entries = listOf(XEntry(key = "k", status = "TODO", zone = "Europe/Rome")))
		assertEquals(v2, InterchangeUpgrade.toV2(v2, rome).data)
	}
}
