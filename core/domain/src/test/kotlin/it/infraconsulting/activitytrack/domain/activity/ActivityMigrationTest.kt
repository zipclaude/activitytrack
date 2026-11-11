/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.model.ExternalRefKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/* Pieces of the migration the shared cases (ActivityMigrationCasesTest) do not isolate. */
class ActivityMigrationTest {
	private val rome = ZoneId.of("Europe/Rome")

	@Test
	fun uidsAreTheSameAsPythonUuid5() {
		/* values of Python's uuid.uuid5(NS, name) */
		assertEquals("346bda51-47ab-5016-8d54-0a15b4869b1a", ActivityMigration.uid("todo-1", "abs/2026-11-12/0"))
		assertEquals("ca4447aa-3f17-5d05-bd96-812707597389", ActivityMigration.uid("absence", "7"))
		assertEquals("842dc6c4-8aae-5092-b677-16a9762e22f1", ActivityMigration.uid("t-àé", "ref/0"))
	}

	@Test
	fun pluginFieldsAndRefs() {
		assertEquals("""{"a":1,"b":"e","c":3}""", ActivityMigration.mergePluginFields("""{"b":"t","a":1}""", """{"c":3,"b":"e"}"""))
		assertEquals("""{"a":1}""", ActivityMigration.mergePluginFields("""{"a":1}""", "not json"))
		assertEquals(null, ActivityMigration.mergePluginFields("[1]", "{}"))
		assertEquals(listOf("INC1", "https://x/y"), ActivityMigration.refValues(" INC1 \n\n https://x/y\r\nINC1\n  "))
	}

	@Test
	fun overlappingWorkingHoursGiveDisjointSegments() {
		/* two schedule rows for Thursday that overlap: one segment, not two overlapping ones */
		val todo = LegacyTodo(1, "a1", kind = "ABSENCE", title = "Ferie", createdUtcMs = 0, updatedUtcMs = 0, planEpochDay = LocalDate.of(2026, 11, 12).toEpochDay())
		val r = ActivityMigration.migrate(LegacyData(todos = listOf(todo), schedule = listOf(DayWindow(4, 480, 720), DayWindow(4, 600, 1020)), zone = rome))
		val a = r.activities.single()
		assertEquals(AbsenceKind.HOLIDAY, a.absenceKind)
		assertEquals(listOf(SegmentKind.ABSENCE), r.segments.map { it.kind })
		assertEquals(a.closedUtcMs, r.segments.single().endUtcMs)
		assertEquals((1020 - 480) * 60_000L, r.segments.single().endUtcMs - r.segments.single().startUtcMs)
	}

	@Test
	fun absorbedTodoKeepsTheEntryAndLinksTheOthers() {
		val e1 = LegacyEntry(10, "e1", 1, "CLOSED", zoneId = "Europe/Rome", createdUtcMs = 5, updatedUtcMs = 6)
		val e2 = LegacyEntry(11, "e2", 1, "ACTIVE", activity = "mine", zoneId = "Europe/Rome", createdUtcMs = 1, updatedUtcMs = 2)
		val segs = listOf(LegacySegment(1, 10, "WORK", 100, 200), LegacySegment(2, 11, "WORK", 300, null))
		val todo = LegacyTodo(1, "t1", title = "Do it", priority = 2, refs = "e1\nJIRA-7", createdUtcMs = 0, updatedUtcMs = 0)
		val r = ActivityMigration.migrate(LegacyData(entries = listOf(e1, e2), segments = segs, refs = listOf(LegacyRef(10, " JIRA-7 ")), todos = listOf(todo), zone = rome))
		/* e2 runs, so the to-do is absorbed, by e1 (earliest first start) */
		val (a1, a2) = r.activities
		assertEquals(ActivityStatus.CLOSED to "Do it", a1.status to a1.activity)
		assertEquals(4, a1.priority)
		assertEquals(200L, a1.closedUtcMs)
		assertEquals(Triple(ActivityStatus.RUNNING, SegmentKind.WORK, "mine"), Triple(a2.status, a2.mode, a2.activity))
		assertEquals(
			listOf(
				NewRef("e1", ActivityMigration.uid("t1", "ref/0"), ExternalRefKind.TICKET, "e1"),
				NewRef("e2", ActivityMigration.uid("t1", "link/e2"), ExternalRefKind.UUID, "e1"),
			),
			r.refs,
		)
	}
}
