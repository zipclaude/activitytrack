/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * The shared cases of the data migration (docs/samples/activity-migration.json,
 * ACTIVITY-MODEL.md 6): migrate(before) must equal `after` once both are in the
 * normal form the file describes (no ids, children by entry uid, nulls
 * omitted, plugin_fields parsed, rows compared as sets). The Python reference
 * and the server run the same file.
 */
class ActivityMigrationCasesTest {
	private val doc = Json.parseToJsonElement(File("../../docs/samples/activity-migration.json").readText()).jsonObject

	@Test
	fun everySharedCase() {
		val cases = doc["cases"]!!.jsonArray.map { it.jsonObject }
		assertTrue(cases.isNotEmpty())
		val failures = cases.mapNotNull { c ->
			val got = runCatching { normalForm(c) }.getOrElse { return@mapNotNull "${c.str("name")}: $it" }
			val want = c["after"]!!.jsonObject
			val diff = TABLES.mapNotNull { t ->
				val w = want[t]?.jsonArray.orEmpty().map { canonical(it.jsonObject) }.sorted()
				val g = got.getValue(t).map(::canonical).sorted()
				if (w == g) null else "  $t\n    missing ${w - g.toSet()}\n    extra   ${g - w.toSet()}"
			}
			if (diff.isEmpty()) null else "${c.str("name")}:\n" + diff.joinToString("\n")
		}
		assertEquals("", failures.joinToString("\n"), "${failures.size} of ${cases.size} cases fail")
	}

	private fun normalForm(c: JsonObject): Map<String, List<JsonObject>> {
		val before = c["before"]!!.jsonObject
		fun table(name: String) = before[name]?.jsonArray.orEmpty().map { it.jsonObject }
		val entries = table("time_entry")
		val segments = table("segment")
		val refs = table("external_ref")
		val zone = ZoneId.of(c.str("zone") ?: doc.str("zone")!!)
		val data = LegacyData(
			entries = entries.map {
				LegacyEntry(
					id = it.long("id")!!, uid = it.str("uid")!!, todoId = it.long("todo_id"), status = it.str("status")!!,
					companyId = it.long("company_id"), clientId = it.long("client_id"), activity = it.str("activity"),
					notes = it.str("notes"), pluginFields = it.str("plugin_fields"), zoneId = it.str("zone_id")!!,
					createdUtcMs = it.long("created_utc_ms")!!, updatedUtcMs = it.long("updated_utc_ms")!!,
				)
			},
			segments = segments.map { LegacySegment(it.long("id")!!, it.long("entry_id")!!, it.str("kind")!!, it.long("start_utc_ms")!!, it.long("end_utc_ms")) },
			refs = refs.map { LegacyRef(it.long("entry_id")!!, it.str("value")!!) },
			todos = table("todo").map {
				LegacyTodo(
					id = it.long("id")!!, uid = it.str("uid")!!, kind = it.str("kind") ?: "TASK", title = it.str("title")!!,
					companyId = it.long("company_id"), clientId = it.long("client_id"), externalKey = it.str("external_key"),
					notes = it.str("notes"), refs = it.str("refs") ?: "", dueEpochDay = it.long("due_epoch_day"),
					dueTimeMin = it.int("due_time_min"), priority = it.int("priority"), done = (it.int("done") ?: 0) != 0,
					doneUtcMs = it.long("done_utc_ms"), createdUtcMs = it.long("created_utc_ms")!!, updatedUtcMs = it.long("updated_utc_ms")!!,
					planEpochDay = it.long("plan_epoch_day"), planStartMin = it.int("plan_start_min"), durationMin = it.int("duration_min"),
					planRemindedUtcMs = it.long("plan_reminded_utc_ms"), ignoredUtcMs = it.long("ignored_utc_ms"),
					pluginFields = it.str("plugin_fields"), planEndEpochDay = it.long("plan_end_epoch_day"),
				)
			},
			absences = table("absence").map { LegacyAbsence(it.long("id")!!, it.str("uid"), it.long("date_epoch_day")!!, it.str("kind")!!, it.int("time_min")) },
			schedule = table("work_schedule").map { DayWindow(it.int("day_of_week")!!, it.int("start_min")!!, it.int("end_min")!!) },
			lunchBreaks = table("lunch_break").map { DayWindow(it.int("day_of_week")!!, it.int("start_min")!!, it.int("end_min")!!) },
			daysOff = table("day_off").map { LocalDate.ofEpochDay(it.long("date_epoch_day")!!) }.toSet(),
			zone = zone,
		)
		val r = ActivityMigration.migrate(data)

		val beforeById = entries.associateBy { it.long("id")!! }
		val uidOf = entries.associate { it.long("id")!! to it.str("uid")!! }
		val outEntries = r.activities.map { a ->
			val m = LinkedHashMap<String, JsonElement>()
			a.id?.let { m += beforeById.getValue(it) }
			m.remove("id")
			m.remove("todo_id")
			if (m["unanswered_reminders"].isNull()) m["unanswered_reminders"] = JsonPrimitive(0)
			if (m["needs_review"].isNull()) m["needs_review"] = JsonPrimitive(0)
			m += mapOf(
				"uid" to JsonPrimitive(a.uid), "kind" to JsonPrimitive(a.kind.name), "status" to JsonPrimitive(a.status.name),
				"mode" to JsonPrimitive(a.mode?.name), "closed_utc_ms" to JsonPrimitive(a.closedUtcMs), "deleted_utc_ms" to JsonPrimitive(a.deletedUtcMs),
				"company_id" to JsonPrimitive(a.companyId), "client_id" to JsonPrimitive(a.clientId), "activity" to JsonPrimitive(a.activity),
				"notes" to JsonPrimitive(a.notes), "plugin_fields" to (a.pluginFields?.let(Json::parseToJsonElement) ?: JsonNull),
				"external_key" to JsonPrimitive(a.externalKey), "due_epoch_day" to JsonPrimitive(a.dueEpochDay), "due_time_min" to JsonPrimitive(a.dueTimeMin),
				"plan_epoch_day" to JsonPrimitive(a.planEpochDay), "plan_start_min" to JsonPrimitive(a.planStartMin), "duration_min" to JsonPrimitive(a.durationMin),
				"plan_reminded_utc_ms" to JsonPrimitive(a.planRemindedUtcMs), "priority" to JsonPrimitive(a.priority),
				"absence_kind" to JsonPrimitive(a.absenceKind?.name), "zone_id" to JsonPrimitive(a.zoneId),
				"created_utc_ms" to JsonPrimitive(a.createdUtcMs), "updated_utc_ms" to JsonPrimitive(a.updatedUtcMs),
			)
			JsonObject(m.filterValues { !it.isNull() })
		}
		val outSegments = segments.map { s ->
			val m = LinkedHashMap(s)
			m.remove("id")
			m["entry_uid"] = JsonPrimitive(uidOf.getValue(m.remove("entry_id")!!.jsonPrimitive.long))
			m["planned"] = JsonPrimitive(0)
			if (m["uid"].isNull()) m["uid"] = JsonPrimitive("")
			JsonObject(m.filterValues { !it.isNull() })
		} + r.segments.map {
			JsonObject(
				mapOf(
					"entry_uid" to JsonPrimitive(it.entryUid), "uid" to JsonPrimitive(it.uid), "kind" to JsonPrimitive(it.kind.name),
					"start_utc_ms" to JsonPrimitive(it.startUtcMs), "end_utc_ms" to JsonPrimitive(it.endUtcMs), "zone_id" to JsonPrimitive(it.zoneId),
					"source" to JsonPrimitive("MANUAL"), "planned" to JsonPrimitive(0),
				),
			)
		}
		val outRefs = refs.map { x ->
			val m = LinkedHashMap(x)
			m.remove("id")
			m["entry_uid"] = JsonPrimitive(uidOf.getValue(m.remove("entry_id")!!.jsonPrimitive.long))
			JsonObject(m.filterValues { !it.isNull() })
		} + r.refs.map {
			JsonObject(mapOf("entry_uid" to JsonPrimitive(it.entryUid), "uid" to JsonPrimitive(it.uid), "kind" to JsonPrimitive(it.kind.name), "value" to JsonPrimitive(it.value)))
		}
		return mapOf("time_entry" to outEntries, "segment" to outSegments, "external_ref" to outRefs)
	}

	private fun JsonElement?.isNull() = this == null || this is JsonNull
	private fun canonical(o: JsonObject): String = JsonObject(o.toSortedMap()).toString()
	private fun JsonObject.str(k: String): String? = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
	private fun JsonObject.long(k: String): Long? = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
	private fun JsonObject.int(k: String): Int? = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int

	companion object {
		val TABLES = listOf("time_entry", "segment", "external_ref")
	}
}
