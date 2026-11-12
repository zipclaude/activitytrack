/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.db

import it.infraconsulting.activitytrack.data.db.ActivityModelMigration.Change
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/* The wire-2 shadows of the dirty records, translated to wire 3 (ActivityModelMigration.baseShadows) */
class RealignBaseTest {
	private val shadows = mapOf(
		("todo" to "t-1") to """{"title":"Patch","notes":null,"priority":1,"done":true,"doneUtcMs":7,"ignoredUtcMs":null,"refs":"INC1","kind":"TASK","color":3}""",
		("todo" to "t-2") to """{"title":"Ferie","done":false,"kind":"ABSENCE"}""",
		("segment" to "s-1") to """{"kind":"WORK","startUtcMs":1}""",
		("client" to "c-1") to """{"name":"Banca"}""",
		("entry" to "e-1") to """{"todoUid":"t-9","status":"SUSPENDED","activity":"x"}""",
	)

	private fun base(vararg dirty: Change) =
		ActivityModelMigration.baseShadows(dirty.toList()) { t, u -> shadows[t to u] }.mapValues { it.value.toString() }

	private fun json(s: String) = Json.parseToJsonElement(s).toString()

	@Test
	fun eachTypeAsTheMigrationMapsIt() {
		val got = base(
			Change("entry", "t-1", "upsert", 1), Change("entry", "t-2", "upsert", 1), Change("segment", "s-1", "upsert", 1),
			Change("client", "c-1", "upsert", 1), Change("entry", "e-1", "upsert", 1), Change("client", "gone", "delete", 1),
		)
		assertEquals(
			mapOf(
				/* a task to-do kept as its own activity: renamed fields, P1 -> 5, done -> CLOSED at doneUtcMs */
				("entry" to "t-1") to json("""{"notes":null,"activity":"Patch","priority":5,"kind":"ACTIVITY","status":"CLOSED","closedUtcMs":7,"deletedUtcMs":null}"""),
				("segment" to "s-1") to json("""{"kind":"WORK","startUtcMs":1,"planned":false}"""),
				("client" to "c-1") to json("""{"name":"Banca"}"""),
				("entry" to "e-1") to json("""{"activity":"x","status":"RUNNING","kind":"ACTIVITY","deletedUtcMs":null}"""),
			),
			got,
		)
	}
}
