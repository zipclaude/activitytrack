/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.model.ExternalRefClassifier
import it.infraconsulting.activitytrack.domain.model.ExternalRefKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import it.infraconsulting.activitytrack.domain.time.atMinute
import it.infraconsulting.activitytrack.domain.todo.AbsenceSpan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/* The rows of DB 21 (wire 2, interchange 1) the migration reads; column names as in the Room schema 21. */

/* A `time_entry` row; [uid] must be set (the sync gave every row one). */
data class LegacyEntry(
	val id: Long,
	val uid: String,
	val todoId: Long?,
	val status: String,
	val companyId: Long? = null,
	val clientId: Long? = null,
	val activity: String? = null,
	val notes: String? = null,
	val pluginFields: String? = null,
	val zoneId: String,
	val createdUtcMs: Long,
	val updatedUtcMs: Long,
)

/* A `segment` row: only what the migration reads; the rows stay as they are (planned = 0). */
data class LegacySegment(val id: Long, val entryId: Long, val kind: String, val startUtcMs: Long, val endUtcMs: Long?)

/* An `external_ref` row, read to avoid adding a value the entry already has. */
data class LegacyRef(val entryId: Long, val value: String)

/* A `todo` row. */
data class LegacyTodo(
	val id: Long,
	val uid: String,
	val kind: String = "TASK",
	val title: String,
	val companyId: Long? = null,
	val clientId: Long? = null,
	val externalKey: String? = null,
	val notes: String? = null,
	val refs: String = "",
	val dueEpochDay: Long? = null,
	val dueTimeMin: Int? = null,
	val priority: Int? = null,
	val done: Boolean = false,
	val doneUtcMs: Long? = null,
	val createdUtcMs: Long,
	val updatedUtcMs: Long,
	val planEpochDay: Long? = null,
	val planStartMin: Int? = null,
	val durationMin: Int? = null,
	val planRemindedUtcMs: Long? = null,
	val ignoredUtcMs: Long? = null,
	val pluginFields: String? = null,
	val planEndEpochDay: Long? = null,
)

/* A legacy `absence` row (none should be left since DB 21). */
data class LegacyAbsence(val id: Long, val uid: String?, val dateEpochDay: Long, val kind: String, val timeMin: Int?)

/*
 * Everything the migration reads. [zone]: the zone of what is created from
 * a to-do or a legacy absence (the `todo` table has none): the device's on
 * the phone, the owner's on the server. [schedule] and [lunchBreaks] are the
 * `work_schedule` and `lunch_break` rows, [daysOff] the `day_off` dates.
 */
data class LegacyData(
	val entries: List<LegacyEntry> = emptyList(),
	val segments: List<LegacySegment> = emptyList(),
	val refs: List<LegacyRef> = emptyList(),
	val todos: List<LegacyTodo> = emptyList(),
	val absences: List<LegacyAbsence> = emptyList(),
	val schedule: List<DayWindow> = emptyList(),
	val lunchBreaks: List<DayWindow> = emptyList(),
	val daysOff: Set<LocalDate> = emptySet(),
	val zone: ZoneId,
)

/*
 * The `time_entry` columns the migration owns, with their new values. For
 * an existing entry ([id] set) these columns are written and every other
 * one stays; a new activity ([id] null) is inserted with them, the others
 * null (counters 0).
 */
data class ActivityRow(
	val id: Long?,
	val uid: String,
	val kind: ActivityKind,
	val status: ActivityStatus,
	val mode: SegmentKind? = null,
	val closedUtcMs: Long? = null,
	val deletedUtcMs: Long? = null,
	val companyId: Long? = null,
	val clientId: Long? = null,
	val activity: String? = null,
	val notes: String? = null,
	val pluginFields: String? = null,
	val externalKey: String? = null,
	val dueEpochDay: Long? = null,
	val dueTimeMin: Int? = null,
	val planEpochDay: Long? = null,
	val planStartMin: Int? = null,
	val durationMin: Int? = null,
	val planRemindedUtcMs: Long? = null,
	val priority: Int = 0,
	val absenceKind: AbsenceKind? = null,
	val zoneId: String,
	val createdUtcMs: Long,
	val updatedUtcMs: Long,
)

/* A new ABSENCE segment (source MANUAL, not planned) of the activity [entryUid]. */
data class NewSegment(val entryUid: String, val uid: String, val startUtcMs: Long, val endUtcMs: Long, val zoneId: String) {
	val kind: SegmentKind get() = SegmentKind.ABSENCE
}

/* A new `external_ref` row of the activity [entryUid]. */
data class NewRef(val entryUid: String, val uid: String, val kind: ExternalRefKind, val value: String)

/* [activities]: the existing entries first, in input order, then the new ones; children by uid. */
data class MigrationResult(val activities: List<ActivityRow>, val segments: List<NewSegment>, val refs: List<NewRef>)

/*
 * DB 21 -> 22, wire 2 -> 3, interchange 1 -> 2 (ACTIVITY-MODEL.md 6): the
 * same pure function for the Room migration, the import of an old file and,
 * written once more in Python, the server; docs/samples/activity-migration.json
 * holds the cases all of them pass. The choices the spec leaves open follow
 * the Python reference (scripts/activity_migration.py, marked CHOICE there);
 * two rules follow the server: an ignored absence to-do goes to the bin, and
 * external_key stays unique with the company (the earliest created keeps it).
 * New uids are name-based: uuid5(NS, "<to-do uid>/<suffix>").
 */
object ActivityMigration {
	val NS: UUID = UUID.fromString("6f1c2a52-6b1e-5a8e-9d7e-0a5c1e7d1000")

	fun uid(base: String, suffix: String): String = SyncIds.nameBased("$base/$suffix", NS)

	fun migrate(data: LegacyData): MigrationResult = Run(data).result()

	/* Plugin fields as one JSON object: [over] wins over [base]; text that is not an object counts as empty; null when empty. */
	fun mergePluginFields(base: String?, over: String?): String? {
		val merged = parseObject(base) + parseObject(over)
		return if (merged.isEmpty()) null else JsonObject(merged.toSortedMap()).toString()
	}

	private fun parseObject(text: String?): Map<String, JsonElement> =
		if (text.isNullOrBlank()) emptyMap() else (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject) ?: emptyMap()

	/* The to-do's references: one per non-blank line, trimmed, the first of duplicates. */
	fun refValues(refs: String): List<String> = refs.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

	private class Run(val d: LegacyData) {
		val zoneName: String = d.zone.id
		val segmentsOf: Map<Long, List<LegacySegment>> = d.segments.groupBy { it.entryId }
		val oldRefs: Map<Long, Set<String>> = d.refs.groupBy({ it.entryId }, { it.value.trim() }).mapValues { it.value.toSet() }

		/* existing entries by id, updated in place by the to-do steps */
		val rows = LinkedHashMap<Long, ActivityRow>()
		val created = ArrayList<ActivityRow>()
		val newSegments = ArrayList<NewSegment>()
		val newRefs = ArrayList<NewRef>()

		fun result(): MigrationResult {
			for (e in d.entries)
				rows[e.id] = entry(e)
			val byTodo = d.entries.filter { it.todoId != null }.groupBy { it.todoId!! }
			for (t in d.todos.sortedBy { it.id })
				todo(t, byTodo[t.id].orEmpty())
			for (a in d.absences.sortedBy { it.id })
				legacyAbsence(a)
			return MigrationResult(uniqueKeys(rows.values.toList() + created), newSegments, newRefs)
		}

		/*
		 * external_key is unique with the company (the server's rule): on a
		 * clash the earliest created activity, then the lower uid, keeps it.
		 */
		fun uniqueKeys(all: List<ActivityRow>): List<ActivityRow> {
			val seen = HashSet<Pair<Long?, String>>()
			val lose = HashSet<String>()
			for (a in all.sortedWith(compareBy({ it.createdUtcMs }, { it.uid }))) {
				val k = a.externalKey?.takeIf { it.isNotBlank() } ?: continue
				if (!seen.add(a.companyId to k))
					lose += a.uid
			}
			/* a blank key is no key (the server's rule): it would clash with every other blank one */
			return all.map { if (it.uid in lose || it.externalKey?.isBlank() == true) it.copy(externalKey = null) else it }
		}

		/* step 1: the entry's status; closed at its last segment end, else at its last update */
		fun entry(e: LegacyEntry): ActivityRow {
			val own = segmentsOf[e.id].orEmpty()
			val status = if (e.status == "ACTIVE" || e.status == "SUSPENDED") ActivityStatus.RUNNING else ActivityStatus.CLOSED
			val closed = if (status == ActivityStatus.RUNNING) null else own.mapNotNull { it.endUtcMs }.maxOrNull() ?: e.updatedUtcMs
			/* mode = the open segment's kind, recomputed rather than trusted */
			val open = own.filter { it.endUtcMs == null }.maxWithOrNull(compareBy({ it.startUtcMs }, { it.id }))
			return ActivityRow(
				id = e.id, uid = e.uid, kind = ActivityKind.ACTIVITY, status = status,
				mode = open?.let { runCatching { SegmentKind.valueOf(it.kind) }.getOrNull() }, closedUtcMs = closed,
				companyId = e.companyId, clientId = e.clientId, activity = e.activity, notes = e.notes, pluginFields = e.pluginFields,
				zoneId = e.zoneId, createdUtcMs = e.createdUtcMs, updatedUtcMs = e.updatedUtcMs,
			)
		}

		/* steps 2-3 */
		fun todo(t: LegacyTodo, linkedEntries: List<LegacyEntry>) {
			val linked = linkedEntries.sortedWith(
				compareBy<LegacyEntry>({ e -> segmentsOf[e.id]?.minOfOrNull { it.startUtcMs }?.let { 0 } ?: 1 })
					.thenBy { e -> segmentsOf[e.id]?.minOfOrNull { it.startUtcMs } ?: 0L }
					.thenBy { it.createdUtcMs }
					.thenBy { it.uid },
			)
			if (t.kind == "ABSENCE") {
				absenceTodo(t)
				return
			}
			val running = linked.any { rows.getValue(it.id).status == ActivityStatus.RUNNING }
			if (linked.isEmpty() || !(t.done || running)) {
				/* none linked: the to-do itself; all closed and the to-do open: it stays its own TODO */
				val a = withTodoFields(
					ActivityRow(
						id = null, uid = t.uid, kind = ActivityKind.ACTIVITY,
						status = if (t.done) ActivityStatus.CLOSED else ActivityStatus.TODO,
						closedUtcMs = if (t.done) t.doneUtcMs ?: t.updatedUtcMs else null, deletedUtcMs = t.ignoredUtcMs,
						companyId = t.companyId, clientId = t.clientId, activity = t.title, notes = t.notes,
						pluginFields = mergePluginFields(t.pluginFields, null),
						zoneId = zoneName, createdUtcMs = t.createdUtcMs, updatedUtcMs = t.updatedUtcMs,
					),
					t,
				)
				created += a
				refValues(t.refs).forEachIndexed { i, v -> addRef(null, a.uid, t.uid, "ref/$i", v) }
				for (e in linked)
					addRef(e.id, e.uid, t.uid, "link/${e.uid}", a.uid, ExternalRefKind.UUID)
			} else {
				/* the earliest entry absorbs the to-do; the entry wins where it has a value */
				val head = linked.first()
				val h = rows.getValue(head.id)
				rows[head.id] = withTodoFields(h, t).copy(
					pluginFields = mergePluginFields(t.pluginFields, h.pluginFields),
					activity = h.activity.takeUnless { it.isNullOrBlank() } ?: t.title,
					notes = h.notes.takeUnless { it.isNullOrBlank() } ?: t.notes,
					companyId = h.companyId ?: t.companyId,
					clientId = h.clientId ?: t.clientId,
				)
				refValues(t.refs).forEachIndexed { i, v -> addRef(head.id, head.uid, t.uid, "ref/$i", v) }
				for (e in linked.drop(1))
					addRef(e.id, e.uid, t.uid, "link/${e.uid}", head.uid, ExternalRefKind.UUID)
			}
		}

		fun withTodoFields(r: ActivityRow, t: LegacyTodo) = r.copy(
			externalKey = t.externalKey, dueEpochDay = t.dueEpochDay, dueTimeMin = t.dueTimeMin,
			planEpochDay = t.planEpochDay, planStartMin = t.planStartMin, durationMin = t.durationMin,
			planRemindedUtcMs = t.planRemindedUtcMs, priority = Priority.fromLegacy(t.priority).value,
		)

		/*
		 * step 2: an absence to-do becomes an ABSENCE activity with its uid;
		 * company, client, notes kept, the title as description and kind; the
		 * plan becomes the segments (3.1). Entries linked to it stay as they are.
		 */
		fun absenceTodo(t: LegacyTodo) {
			val segs = absenceSegments(t.uid, t.uid, t.planEpochDay, t.planEndEpochDay, t.planStartMin, t.durationMin)
			created += ActivityRow(
				id = null, uid = t.uid, kind = ActivityKind.ABSENCE, status = ActivityStatus.CLOSED,
				closedUtcMs = segs.maxOfOrNull { it.endUtcMs } ?: t.updatedUtcMs,
				companyId = t.companyId, clientId = t.clientId, activity = t.title.takeUnless { it.isBlank() }, notes = t.notes,
				absenceKind = AbsenceKind.fromTitle(t.title), zoneId = zoneName, createdUtcMs = t.createdUtcMs, updatedUtcMs = t.updatedUtcMs,
				/* an ignored absence goes to the bin, as an ignored task does (the server's rule) */
				deletedUtcMs = t.ignoredUtcMs,
			)
			newSegments += segs
		}

		/*
		 * step 4: a legacy absence row as the 20 -> 21 migration read it
		 * (AbsenceSpan, working hours from the first start to the last end, the
		 * whole day without hours); nothing missed or an unknown kind: dropped.
		 */
		fun legacyAbsence(r: LegacyAbsence) {
			val day = LocalDate.ofEpochDay(r.dateEpochDay)
			val hours = d.schedule.filter { it.dayOfWeek == day.dayOfWeek.value }
			val kind = AbsenceSpan.Kind.of(r.kind) ?: return
			val span = AbsenceSpan.of(kind, r.timeMin, hours.minOfOrNull { it.startMin } ?: 0, hours.maxOfOrNull { it.endMin } ?: 1440) ?: return
			val uid = r.uid?.takeIf { it.isNotEmpty() } ?: uid("absence", r.id.toString())
			val stamp = day.atMinute(0, d.zone).toEpochMilli()
			val segs = absenceSegments(uid, uid, r.dateEpochDay, r.dateEpochDay, span.startMin, span.durationMin)
			created += ActivityRow(
				id = null, uid = uid, kind = ActivityKind.ABSENCE, status = ActivityStatus.CLOSED,
				closedUtcMs = segs.maxOfOrNull { it.endUtcMs }, absenceKind = AbsenceKind.ABSENCE,
				zoneId = zoneName, createdUtcMs = stamp, updatedUtcMs = stamp,
			)
			newSegments += segs
		}

		/*
		 * The segments of an absence plan (3.1): with a start, one segment on
		 * the first day lasting the duration (elapsed time, as TodoPlan.end; none
		 * = zero length); without, the working hours of every day of the range.
		 */
		fun absenceSegments(entryUid: String, base: String, first: Long?, last: Long?, startMin: Int?, durationMin: Int?): List<NewSegment> {
			first ?: return emptyList()
			val from = LocalDate.ofEpochDay(first)
			if (startMin != null) {
				val start = from.atMinute(startMin, d.zone).toEpochMilli()
				return listOf(NewSegment(entryUid, uid(base, "abs/$from/0"), start, start + (durationMin ?: 0) * 60_000L, zoneName))
			}
			val to = LocalDate.ofEpochDay(if (last == null || last < first) first else last)
			return AbsencePlanner.plan(from, to, null, null, d.schedule, d.lunchBreaks, { it in d.daysOff }, d.zone).map {
				NewSegment(entryUid, uid(base, "abs/${it.day}/${it.index}"), it.start.toEpochMilli(), it.end.toEpochMilli(), zoneName)
			}
		}

		/* one external_ref unless the entry already has the value (trimmed) */
		fun addRef(entryId: Long?, entryUid: String, base: String, suffix: String, value: String, kind: ExternalRefKind? = null) {
			val v = value.trim()
			val have = (entryId?.let { oldRefs[it] }.orEmpty()) + newRefs.filter { it.entryUid == entryUid }.map { it.value }
			if (v in have)
				return
			newRefs += NewRef(entryUid, uid(base, suffix), kind ?: ExternalRefClassifier.classify(v), v)
		}
	}
}
