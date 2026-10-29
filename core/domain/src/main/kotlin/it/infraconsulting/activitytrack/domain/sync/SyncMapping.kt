/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

/*
 * Records as they travel to ActivityTrack Web (issue #112, server SYNC.md §1):
 * for each record type its table, and for each wire field its column and
 * kind. docs/samples/sync-schema.json is the server's schema
 * (`manage.py sync_schema`); SyncMappingTest keeps the two in step.
 *
 * Database values are what SQLite hands back: Long, Double, String or null.
 */
enum class WireKind {
	TEXT,
	INT,
	FLOAT,
	/* 0/1 in the database */
	BOOL,
	/* epoch milliseconds both sides */
	INSTANT,
	/* epoch day in the database, yyyy-mm-dd on the wire */
	DATE,
	/* a local id in the database, the target's uid on the wire */
	REF,
	/* JSON text in the database, the JSON value on the wire */
	JSON,
}

class WireField(
	val name: String,
	val column: String,
	val kind: WireKind,
	val nullable: Boolean = false,
	/* record type a REF points to */
	val ref: String? = null,
	/* set by the server only: received, never pushed */
	val readOnly: Boolean = false,
)

/* rows of another table folded into a list of strings (contact phones, entry tags) */
class FoldedList(val name: String, val table: String, val parentColumn: String, val valueColumn: String)

class RecordType(
	val type: String,
	val table: String,
	val fields: List<WireField>,
	val lists: List<FoldedList> = emptyList(),
	/* the field naming the parent of a child record */
	val parent: String? = null,
	/* NOT NULL columns kept only on the phone: their value when a record arrives from the server */
	val localDefaults: Map<String, Any> = emptyMap(),
	/* server fields the app does not keep yet, with the issue that adds them */
	val notYet: Map<String, String> = emptyMap(),
	/* the column identifying a row besides uid (all tables have "id" but day_off and remote_item) */
	val idColumn: String? = "id",
) {
	val byName: Map<String, WireField> = fields.associateBy { it.name }
}

class WireException(message: String) : IllegalArgumentException(message)

object SyncMapping {
	private fun text(name: String, column: String = snake(name), nullable: Boolean = false) = WireField(name, column, WireKind.TEXT, nullable)
	private fun int(name: String, column: String = snake(name), nullable: Boolean = false) = WireField(name, column, WireKind.INT, nullable)
	private fun float(name: String, column: String = snake(name), nullable: Boolean = false) = WireField(name, column, WireKind.FLOAT, nullable)
	private fun bool(name: String, column: String = snake(name)) = WireField(name, column, WireKind.BOOL)
	private fun instant(name: String, nullable: Boolean = false) = WireField(name, snake(name), WireKind.INSTANT, nullable)
	private fun date(name: String, column: String, nullable: Boolean = false) = WireField(name, column, WireKind.DATE, nullable)
	private fun json(name: String, column: String = snake(name), nullable: Boolean = true) = WireField(name, column, WireKind.JSON, nullable)
	private fun ref(name: String, type: String, column: String, nullable: Boolean = true) = WireField(name, column, WireKind.REF, nullable, type)

	private fun snake(name: String) = name.replace(Regex("[A-Z]")) { "_" + it.value.lowercase() }

	private val site = listOf(text("label"), float("lat"), float("lon"), int("radiusM"), text("address", nullable = true))

	val TYPES: List<RecordType> = listOf(
		RecordType(
			"company", "company",
			listOf(text("name"), bool("archived"), int("color", nullable = true), text("externalCode", nullable = true), int("position", "sort_position", nullable = true)),
		),
		RecordType("companySite", "company_site", site + ref("companyUid", "company", "company_id", nullable = false), parent = "companyUid"),
		RecordType(
			"client", "client",
			listOf(
				ref("companyUid", "company", "company_id"), text("name"), bool("archived"), int("color", nullable = true),
				text("externalCode", nullable = true), int("position", "sort_position", nullable = true),
			),
		),
		RecordType("clientSite", "client_site", site + ref("clientUid", "client", "client_id", nullable = false), parent = "clientUid"),
		RecordType(
			"contact", "contact",
			listOf(ref("clientUid", "client", "client_id", nullable = false), text("name"), text("email", nullable = true), text("externalKey", nullable = true)),
			lists = listOf(FoldedList("phones", "contact_phone", "contact_id", "number")),
			parent = "clientUid",
		),
		RecordType("role", "role", listOf(text("name"), bool("archived"), int("position", "sort_position", nullable = true))),
		RecordType(
			"preset", "activity_preset",
			listOf(text("text"), ref("defaultRoleUid", "role", "default_role_id"), ref("companyUid", "company", "company_id"), int("position", "sort_position", nullable = true)),
		),
		RecordType(
			"material", "material",
			listOf(text("name"), text("unit", nullable = true), int("unitCostCents", nullable = true), ref("companyUid", "company", "company_id"), bool("archived")),
		),
		RecordType("place", "place", listOf(text("kind"), text("label"), float("lat"), float("lon"), int("radiusM"))),
		RecordType(
			"todo", "todo",
			listOf(
				ref("companyUid", "company", "company_id"), ref("clientUid", "client", "client_id"), text("externalKey", nullable = true),
				text("title"), text("notes", nullable = true), text("refs"), date("dueDate", "due_epoch_day", nullable = true),
				int("dueTimeMin", nullable = true), int("priority", nullable = true), bool("done"), instant("doneUtcMs", nullable = true),
				instant("createdUtcMs"), instant("updatedUtcMs"), date("planDate", "plan_epoch_day", nullable = true),
				int("planStartMin", nullable = true), int("durationMin", nullable = true), instant("ignoredUtcMs", nullable = true),
				json("pluginFields"),
			),
		),
		RecordType(
			"entry", "time_entry",
			listOf(
				ref("todoUid", "todo", "todo_id"), ref("contactUid", "contact", "contact_id"), ref("companyUid", "company", "company_id"),
				ref("clientUid", "client", "client_id"), ref("roleUid", "role", "role_id"), text("activity", nullable = true),
				text("status"), text("mode", nullable = true), text("locality", nullable = true), text("locationKind", nullable = true),
				text("notes", nullable = true), text("zoneId"), instant("plannedEndUtcMs", nullable = true), json("pluginFields"),
				bool("needsReview"), instant("createdUtcMs"), instant("updatedUtcMs"),
			),
			lists = listOf(FoldedList("tags", "entry_tag", "entry_id", "tag")),
			localDefaults = mapOf("unanswered_reminders" to 0L),
		),
		RecordType(
			"segment", "segment",
			listOf(
				ref("entryUid", "entry", "entry_id", nullable = false), text("kind"), instant("startUtcMs"), instant("endUtcMs", nullable = true),
				text("zoneId"), text("source"), float("kmEstimated", nullable = true), float("kmDeclared", nullable = true), text("note", nullable = true),
			),
			parent = "entryUid",
		),
		RecordType(
			"attachment", "attachment",
			listOf(
				ref("entryUid", "entry", "entry_id", nullable = false), text("kind"), text("mime"), text("transcript", nullable = true),
				instant("createdUtcMs"), text("sha256"), int("size", nullable = true), WireField("hasFile", "has_file", WireKind.BOOL, readOnly = true),
			),
			parent = "entryUid",
			/* the file travels apart (issue #116): empty until downloaded */
			localDefaults = mapOf("rel_path" to ""),
		),
		RecordType(
			"expense", "expense",
			listOf(
				ref("entryUid", "entry", "entry_id", nullable = false), ref("segmentUid", "segment", "segment_id"), int("amountCents"),
				text("description"), ref("attachmentUid", "attachment", "attachment_id"), instant("createdUtcMs"),
			),
			parent = "entryUid",
		),
		RecordType(
			"entryMaterial", "entry_material",
			listOf(
				ref("entryUid", "entry", "entry_id", nullable = false), ref("materialUid", "material", "material_id"), text("description"),
				float("quantity", nullable = true), text("unit", nullable = true), int("unitCostCents", nullable = true), instant("createdUtcMs"),
			),
			parent = "entryUid",
		),
		RecordType("externalRef", "external_ref", listOf(ref("entryUid", "entry", "entry_id", nullable = false), text("kind"), text("value")), parent = "entryUid"),
		RecordType("absence", "absence", listOf(date("date", "date_epoch_day"), text("kind"), int("timeMin", nullable = true))),
		RecordType("dayOff", "day_off", listOf(date("date", "date_epoch_day"), text("note", nullable = true)), idColumn = null),
		RecordType("workSchedule", "work_schedule", listOf(int("dayOfWeek"), int("startMin"), int("endMin"))),
		RecordType("lunchBreak", "lunch_break", listOf(int("dayOfWeek"), int("startMin"), int("endMin"))),
		RecordType(
			"remoteItem", "remote_item",
			listOf(
				ref("companyUid", "company", "company_id", nullable = false), text("pluginId"), text("list"), text("parentId"),
				text("itemId", "id"), text("name"), text("clientCode", nullable = true), text("kind", nullable = true), bool("active"),
				int("order", "sort_order", nullable = true), json("extra", nullable = false),
			),
			idColumn = null,
		),
	)

	val byType: Map<String, RecordType> = TYPES.associateBy { it.type }
	val byTable: Map<String, RecordType> = TYPES.associateBy { it.table }

	/* a database value as it travels */
	fun toWire(field: WireField, value: Any?): JsonElement {
		if (value == null) return JsonNull
		return when (field.kind) {
			WireKind.TEXT, WireKind.REF -> JsonPrimitive(value as String)
			WireKind.INT, WireKind.INSTANT -> JsonPrimitive((value as Number).toLong())
			WireKind.FLOAT -> JsonPrimitive((value as Number).toDouble())
			WireKind.BOOL -> JsonPrimitive((value as Number).toLong() != 0L)
			WireKind.DATE -> JsonPrimitive(LocalDate.ofEpochDay((value as Number).toLong()).toString())
			WireKind.JSON -> Json.parseToJsonElement(value as String)
		}
	}

	/* a wire value as the database keeps it; REF stays the uid string, resolved by the caller */
	fun fromWire(field: WireField, value: JsonElement): Any? {
		if (value is JsonNull) {
			if (!field.nullable) throw WireException("${field.name}: null not allowed")
			return null
		}
		fun bad(): Nothing = throw WireException("${field.name}: bad value $value")
		return when (field.kind) {
			WireKind.JSON -> value.toString()
			else -> {
				val p = value as? JsonPrimitive ?: bad()
				when (field.kind) {
					WireKind.TEXT, WireKind.REF -> if (p.isString) p.content else bad()
					WireKind.INT, WireKind.INSTANT -> if (p.isString) bad() else p.longOrNull ?: bad()
					WireKind.FLOAT -> if (p.isString) bad() else p.doubleOrNull ?: bad()
					WireKind.BOOL -> if (p.isString) bad() else if (p.booleanOrNull ?: bad()) 1L else 0L
					WireKind.DATE -> if (p.isString) runCatching { LocalDate.parse(p.content).toEpochDay() }.getOrNull() ?: bad() else bad()
					WireKind.JSON -> bad()
				}
			}
		}
	}

	fun list(values: List<String>): JsonElement = JsonArray(values.sorted().map(::JsonPrimitive))

	fun listFromWire(name: String, value: JsonElement): List<String> {
		if (value is JsonNull) return emptyList()
		val array = value as? JsonArray ?: throw WireException("$name: not a list")
		return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw WireException("$name: not a list of strings") }
	}
}
