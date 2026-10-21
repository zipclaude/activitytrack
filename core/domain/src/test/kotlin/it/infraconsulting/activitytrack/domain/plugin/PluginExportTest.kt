/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import it.infraconsulting.activitytrack.domain.model.SegmentKind.PAUSE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.TRAVEL
import it.infraconsulting.activitytrack.domain.model.SegmentKind.WORK
import it.infraconsulting.activitytrack.domain.report.DayAttributionPolicy
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import it.infraconsulting.activitytrack.domain.time.atMinute
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* The groupings, checked against PR #29's AntaresPlan cases, and the Antares body. */
class PluginExportTest {
	private val rome = ZoneId.of("Europe/Rome")
	private val day = LocalDate.of(2026, 10, 19)
	private fun at(h: Int, m: Int, d: LocalDate = day) = d.atMinute(h * 60 + m, rome)

	private fun blocks(vararg s: ExportSegment, by: GroupBy = GroupBy.DAY_BLOCK, travel: TravelMode = TravelMode.NEAREST_BLOCK) =
		PluginExport.blocks(Group(by, null, travel, "work"), s.toList(), rome, ReportRounding.DEFAULT, DayAttributionPolicy(), 60)

	@Test
	fun oneBlockWithShortPauseAndTravel() {
		val b = blocks(
			ExportSegment(TRAVEL, at(8, 30), at(9, 0), 18.4),
			ExportSegment(WORK, at(9, 0), at(12, 30)),
			ExportSegment(PAUSE, at(12, 30), at(13, 0)),
			ExportSegment(WORK, at(13, 0), at(16, 50)),
			ExportSegment(TRAVEL, at(16, 50), at(17, 22), 18.6),
		).single()
		assertEquals("2026-10-19#0", b.key)
		assertEquals(at(9, 0), b.from)
		/* 7:50 with the pause inside, rounded up with tolerance to 8:00 */
		assertEquals(at(17, 0), b.to)
		/* 30 + 32 min -> 62, rounded up to 5 -> 65 */
		assertEquals(65, b.travelMinutes)
		assertEquals(37, b.travelKm)
	}

	@Test
	fun longPauseSplitsAndTravelGoesToTheNearestBlock() {
		val b = blocks(
			ExportSegment(WORK, at(9, 0), at(11, 0)),
			ExportSegment(PAUSE, at(11, 0), at(14, 0)),
			ExportSegment(TRAVEL, at(14, 0), at(14, 20), 10.0),
			ExportSegment(WORK, at(14, 20), at(16, 0)),
		)
		assertEquals(listOf("2026-10-19#0", "2026-10-19#1"), b.map { it.key })
		assertEquals(at(11, 0), b[0].to)
		assertEquals(0, b[0].travelMinutes)
		assertEquals(20, b[1].travelMinutes)
		assertEquals(10, b[1].travelKm)
		assertEquals(at(16, 5), b[1].to)
		/* by day: one block over the pause */
		assertEquals(1, blocks(ExportSegment(WORK, at(9, 0), at(11, 0)), ExportSegment(WORK, at(14, 20), at(16, 0)), by = GroupBy.DAY).size)
	}

	@Test
	fun nightWorkAndTravelOnly() {
		assertEquals(listOf(day), blocks(ExportSegment(WORK, at(22, 0), at(4, 30, day.plusDays(1)))).map { it.day })
		val long = blocks(ExportSegment(WORK, at(20, 0), at(8, 0, day.plusDays(1))))
		assertEquals(listOf(day, day.plusDays(1)), long.map { it.day })
		assertEquals(listOf("entry"), blocks(ExportSegment(WORK, at(20, 0), at(8, 0, day.plusDays(1))), by = GroupBy.ENTRY).map { it.key })
		assertEquals(emptyList(), blocks(ExportSegment(TRAVEL, at(8, 0), at(9, 0), 50.0)))
		/* a travel of its own when the descriptor asks */
		val sep = blocks(ExportSegment(TRAVEL, at(8, 0), at(9, 0), 50.0), ExportSegment(WORK, at(9, 0), at(10, 0)), travel = TravelMode.SEPARATE)
		assertEquals(listOf("2026-10-19#0", "2026-10-19#travel#0"), sep.map { it.key })
		assertEquals(0, sep[0].travelMinutes)
		assertEquals(50, sep[1].travelKm)
	}

	@Test
	fun antaresBodyMatchesPr29() {
		val antares = (PluginParser.parse(File("../../plugins/antares.json").readText()) as PluginResult.Valid).plugin
		val block = blocks(ExportSegment(WORK, at(9, 0), at(12, 0)), ExportSegment(TRAVEL, at(12, 0), at(12, 20), 12.0)).single()
		val refs = mapOf<String, JsonElement>(
			"todo" to JsonNull,
			"field" to Json.parseToJsonElement("""{ "portfolio": "S1", "activityType": "T1", "included": true }"""),
			"list" to Json.parseToJsonElement("""{ "portfolio": { "selected": { "id": "S1", "kind": "service" } } }"""),
			"entry" to Json.parseToJsonElement("""{ "activity": "Printer", "notes": "" }"""),
			"block" to block.asJson(),
		)
		val env = PluginEnv(antares, emptyMap(), { null }, rome, more = { r -> refs[r.root]?.let { PluginEnv.dig(it, r.path) } }, materialsOf = { JsonPrimitive("") })
		env.identity["personId"] = JsonPrimitive("42")
		val body = PluginExport.body(antares.export!!.body, env)
		assertEquals(
			Json.parseToJsonElement(
				"""{ "RelationType": 1, "PortfolioId": "S1", "ActivityTypeId": "T1", "OwnerId": "42",
				     "FromDate": "2026-10-19T09:00:00+02:00", "ToDate": "2026-10-19T12:00:00+02:00", "TimeFormat": 1, "Status": 1,
				     "Included": true, "ActivityReport": "Printer", "Note": "", "TravelTimeMinutes": 20, "TravelKm": 12 }""",
			),
			body,
		)
		/* no TicketId key for a service activity: a null leaves the key out */
		assertEquals(false, "TicketId" in body)
	}

	@Test
	fun planCreatesUpdatesDeletesAndSkipsUnchanged() {
		val (a, b) = blocks(ExportSegment(WORK, at(9, 0), at(11, 0)), ExportSegment(WORK, at(14, 20), at(16, 0)))
		val bodyA = JsonObject(mapOf("x" to JsonPrimitive(1)))
		val bodyB = JsonObject(mapOf("x" to JsonPrimitive(2)))
		val sent = listOf(
			SentRecord(a.key, "R1", PluginExport.hash(bodyA)),
			SentRecord(b.key, "R2", "old"),
			SentRecord("2026-10-18#0", "R0", "x"),
		)
		val actions = PluginExport.plan(listOf(a to bodyA, b to bodyB), sent)
		assertEquals(2, actions.size)
		assertEquals("R2", assertIs<ExportAction.Update>(actions[0]).sent.remoteId)
		assertEquals("R0", assertIs<ExportAction.Delete>(actions[1]).sent.remoteId)
		assertIs<ExportAction.Create>(PluginExport.plan(listOf(a to bodyA), emptyList()).single())
	}
}
