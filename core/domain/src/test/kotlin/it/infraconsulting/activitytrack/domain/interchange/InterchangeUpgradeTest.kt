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
		val t1 = byKey.getValue(InterchangeUpgrade.todoUid("ACME", "T-1"))
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
		assertEquals("CLOSED", byKey.getValue(InterchangeUpgrade.todoUid("ACME", "T-2")).status)
		/* the absence to-do: Friday 13 and Monday 16, the weekend skipped */
		val a1 = byKey.getValue(InterchangeUpgrade.todoUid(null, "A-1"))
		assertEquals("ABSENCE", a1.kind)
		assertEquals("HOLIDAY", a1.absenceKind)
		assertEquals(listOf("2026-11-13T08:00:00Z", "2026-11-16T08:00:00Z"), a1.segments.map { it.startUtc })
		assertTrue(a1.segments.all { it.kind == "ABSENCE" && it.source == "MANUAL" })
		/* the legacy absence: one more ABSENCE activity */
		assertEquals(2, d.entries.count { it.kind == "ABSENCE" })
		/* the uids of what was created follow the shared rule */
		assertEquals(ActivityMigration.uid(InterchangeUpgrade.todoUid(null, "A-1"), "abs/2026-11-13/0").length, 36)
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

	/* issue #177: a to-do key is unique per company; the same key in two companies gives two activities */
	@Test
	fun theSameToDoKeyInTwoCompaniesGivesTwoActivities() {
		val v1 = Interchange(
			version = 1,
			todos = listOf(
				XTodo(key = "1234", companyKey = "A", title = "Of A", refs = listOf("INC-A")),
				XTodo(key = "1234", companyKey = "B", title = "Of B", refs = listOf("INC-B")),
			),
			entries = listOf(
				XEntry(key = "e-a", companyKey = "A", todoKey = "1234", status = "CLOSED", zone = "Europe/Rome",
					segments = listOf(seg("2026-11-10T08:00:00Z", "2026-11-10T09:00:00Z"))),
				XEntry(key = "e-b", companyKey = "B", todoKey = "1234", status = "CLOSED", zone = "Europe/Rome",
					segments = listOf(seg("2026-11-10T10:00:00Z", "2026-11-10T11:00:00Z"))),
			),
		)
		val r = InterchangeUpgrade.toV2(v1, rome, week, now = now)
		assertTrue(r.skipped.isEmpty())
		val out = r.data.entries
		/* every record keeps an identity of its own once imported */
		assertEquals(out.size, out.map { SyncIds.canonical(it.key) }.toSet().size)
		val todos = out.filter { it.externalKey == "1234" }.associateBy { it.companyKey }
		assertEquals(setOf("A", "B"), todos.keys)
		val a = todos.getValue("A")
		val b = todos.getValue("B")
		assertTrue(a.key != b.key)
		assertEquals("Of A", a.activity)
		assertEquals(listOf("INC-A"), a.refs)
		assertEquals(listOf("INC-B"), b.refs)
		/* each entry points to the to-do of its own company */
		val byKey = out.associateBy { it.key }
		assertEquals(listOf(a.key), byKey.getValue("e-a").refs)
		assertEquals(listOf(b.key), byKey.getValue("e-b").refs)
	}

	/* issue #177: an entry whose key equals a to-do's key is another record */
	@Test
	fun anEntryKeyEqualToAToDoKeyDoesNotCollide() {
		val v1 = Interchange(
			version = 1,
			todos = listOf(XTodo(key = "1234", companyKey = "A", title = "Task", refs = listOf("INC1"))),
			entries = listOf(
				XEntry(key = "1234", companyKey = "A", status = "CLOSED", activity = "Other", zone = "Europe/Rome",
					segments = listOf(seg("2026-11-10T08:00:00Z", "2026-11-10T09:00:00Z"))),
			),
		)
		val out = InterchangeUpgrade.toV2(v1, rome, week, now = now).data.entries
		assertEquals(2, out.size)
		assertEquals(2, out.map { SyncIds.canonical(it.key) }.toSet().size)
		val todo = out.single { it.externalKey == "1234" }
		assertEquals("TODO", todo.status)
		assertEquals(listOf("INC1"), todo.refs)
		val entry = out.single { it.key == "1234" }
		assertEquals("CLOSED", entry.status)
		assertEquals("Other", entry.activity)
		assertTrue(entry.refs.isEmpty())
	}

	/* a to-do born in the app (its key a UUID) keeps it as its uid, and an entry finds it by it */
	@Test
	fun aToDoWithAUuidKeyKeepsItAsItsUid() {
		val uuid = "0B1F6A2C-3D4E-4F50-8A61-72839405A6B7"
		val v1 = Interchange(
			version = 1,
			todos = listOf(XTodo(key = uuid, companyKey = "A", title = "App-born")),
			entries = listOf(
				XEntry(key = "e-1", todoKey = uuid, status = "CLOSED", zone = "Europe/Rome",
					segments = listOf(seg("2026-11-10T08:00:00Z", "2026-11-10T09:00:00Z"))),
			),
		)
		val byKey = InterchangeUpgrade.toV2(v1, rome, week, now = now).data.entries.associateBy { it.key }
		val todo = byKey.getValue(SyncIds.canonical(uuid))
		assertEquals(null, todo.externalKey)
		assertEquals(listOf(todo.key), byKey.getValue("e-1").refs)
	}

	@Test
	fun aVersion2FileStaysAsItIs() {
		val v2 = Interchange(entries = listOf(XEntry(key = "k", status = "TODO", zone = "Europe/Rome")))
		assertEquals(v2, InterchangeUpgrade.toV2(v2, rome).data)
	}
}
