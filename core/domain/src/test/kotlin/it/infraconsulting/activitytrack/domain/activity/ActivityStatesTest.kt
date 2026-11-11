/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * The shared cases of the displayed state (docs/samples/activity-states.json,
 * ACTIVITY-MODEL.md 2.2-2.4): the app, the web and the Python library run the
 * same file, so a rule changed on one side fails here until all agree.
 */
class ActivityStatesTest {
	private val doc = Json.parseToJsonElement(File("../../docs/samples/activity-states.json").readText()).jsonObject
	private val zone = ZoneId.of(doc.str("zone")!!)
	private val now = instant(doc.str("now"))!!

	@Test
	fun everySharedCase() {
		val cases = doc["cases"]!!.jsonArray.map { it.jsonObject }
		assertTrue(cases.size >= 26, "cases: ${cases.size}")
		val failures = cases.mapNotNull { c ->
			val expect = c["expect"]!!.jsonObject
			val want = Displayed(DisplayState.valueOf(expect.str("state")!!), instant(expect.str("since")))
			val got = ActivityDisplay.display(snapshot(c), now, zone)
			if (got == want) null else "${c.str("name")}: expected $want, got $got"
		}
		assertEquals(emptyList(), failures)
	}

	private fun snapshot(c: JsonObject): ActivitySnapshot {
		val a = c["activity"]!!.jsonObject
		val plan = a.obj("plan")?.let { p -> TodoPlan(LocalDate.parse(p.str("day")), p.int("startMin"), p.int("durationMin")) }
		val due = a.obj("due")
		return ActivitySnapshot(
			kind = ActivityKind.valueOf(a.str("kind")!!),
			status = ActivityStatus.valueOf(a.str("status")!!),
			segments = c["segments"]!!.jsonArray.map { it.jsonObject }.map { s ->
				ActivitySegment(SegmentKind.valueOf(s.str("kind")!!), instant(s.str("start"))!!, instant(s.str("end")), s["planned"]?.jsonPrimitive?.boolean ?: false)
			},
			deletedAt = instant(a.str("deletedAt")),
			closedAt = instant(a.str("closedAt")),
			hasClient = a["client"]?.jsonPrimitive?.boolean ?: false,
			plan = plan,
			dueDay = due?.str("day")?.let(LocalDate::parse),
			dueMin = due?.int("timeMin"),
			missingRequired = c["missingRequired"]?.jsonPrimitive?.boolean ?: false,
		)
	}

	private fun JsonObject.str(k: String): String? = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
	private fun JsonObject.int(k: String): Int? = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
	private fun JsonObject.obj(k: String): JsonObject? = this[k]?.takeIf { it !is JsonNull }?.jsonObject
	private fun instant(s: String?): Instant? = s?.let { OffsetDateTime.parse(it).toInstant() }
}
