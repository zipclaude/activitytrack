/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import it.infraconsulting.activitytrack.domain.activity.ActivityMigration
import it.infraconsulting.activitytrack.domain.activity.LegacyAbsence
import it.infraconsulting.activitytrack.domain.activity.LegacyData
import it.infraconsulting.activitytrack.domain.activity.LegacyEntry
import it.infraconsulting.activitytrack.domain.activity.LegacyRef
import it.infraconsulting.activitytrack.domain.activity.LegacySegment
import it.infraconsulting.activitytrack.domain.activity.LegacyTodo
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * A version 1 interchange file read as version 2 (ACTIVITY-MODEL.md 6.2):
 * its entries, to-dos and absences go through the same migration function
 * as the database (ActivityMigration), with ids made up for the rows of the
 * file. A to-do becomes an activity whose uid is its key as a canonical UUID
 * and whose external key is its key (unless the key is already a UUID, an
 * app-born to-do); the link of an entry to a to-do that is not in the file
 * is dropped. Records with values the migration cannot read are left out
 * and named in [Result.skipped].
 */
object InterchangeUpgrade {
	data class Result(val data: Interchange, val skipped: List<Pair<String, String>>)

	fun toV2(
		data: Interchange,
		zone: ZoneId,
		schedule: List<DayWindow> = emptyList(),
		lunchBreaks: List<DayWindow> = emptyList(),
		daysOff: Set<LocalDate> = emptySet(),
		now: Instant = Instant.now(),
	): Result {
		if (data.version >= 2)
			return Result(data, emptyList())
		val skipped = ArrayList<Pair<String, String>>()
		val nowMs = now.toEpochMilli()
		/* made-up ids for the company and client keys, and back */
		val companyIds = LinkedHashMap<String, Long>()
		val clientIds = LinkedHashMap<Pair<String?, String>, Long>()
		fun company(key: String?): Long? = key?.let { companyIds.getOrPut(it) { companyIds.size + 1L } }
		fun client(companyKey: String?, key: String?): Long? = key?.let { clientIds.getOrPut(companyKey to it) { clientIds.size + 1L } }
		val companyKeyOf = { id: Long? -> id?.let { i -> companyIds.entries.first { it.value == i }.key } }
		val clientKeyOf = { id: Long? -> id?.let { i -> clientIds.entries.first { it.value == i }.key.second } }

		val todoKeys = HashMap<String, Long>()
		val todos = data.todos.mapIndexedNotNull { i, t ->
			val id = i + 1L
			val absence = t.kind == "ABSENCE"
			if (t.kind != "TASK" && !absence)
				return@mapIndexedNotNull null.also { skipped += t.key to "kind ${t.kind}" }
			fun day(s: String?, what: String): Long? = s?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() ?: throw Bad("$what $it") }
			fun min(s: String?, what: String): Int? = s?.let { runCatching { LocalTime.parse(it).let { lt -> lt.hour * 60 + lt.minute } }.getOrNull() ?: throw Bad("$what $it") }
			try {
				val companyKey = t.companyKey.takeIf { !absence }
				val uid = SyncIds.canonical(t.key)
				LegacyTodo(
					id = id, uid = uid, kind = t.kind, title = t.title.trim(),
					companyId = company(companyKey), clientId = client(companyKey, t.clientKey.takeIf { !absence }),
					externalKey = t.key.takeUnless { SyncIds.canonical(it) == it.trim() },
					notes = t.notes?.trim()?.ifEmpty { null }, refs = t.refs.joinToString("\n"),
					dueEpochDay = day(t.dueDate, "due date"), dueTimeMin = min(t.dueTime, "due time"), priority = t.priority,
					done = t.done, doneUtcMs = nowMs.takeIf { t.done }, createdUtcMs = nowMs, updatedUtcMs = nowMs,
					planEpochDay = day(t.plannedDate, "planned date"), planStartMin = min(t.plannedTime, "planned time"),
					durationMin = t.durationMin, ignoredUtcMs = nowMs.takeIf { t.ignored },
					planEndEpochDay = day(t.plannedEndDate, "planned end date"),
				).also { todoKeys[t.key] = id }
			} catch (e: Bad) {
				skipped += t.key to e.message!!
				null
			}
		}

		val entries = ArrayList<LegacyEntry>()
		val segments = ArrayList<LegacySegment>()
		val refs = ArrayList<LegacyRef>()
		val entryIndex = HashMap<Long, XEntry>()
		for ((i, e) in data.entries.withIndex()) {
			val id = i + 1L
			val segs = e.segments.mapIndexedNotNull { j, s ->
				val start = runCatching { Instant.parse(s.startUtc).toEpochMilli() }.getOrNull() ?: return@mapIndexedNotNull null
				LegacySegment(id * 10_000 + j, id, s.kind, start, s.endUtc?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() })
			}
			val first = segs.minOfOrNull { it.startUtcMs } ?: nowMs
			entries += LegacyEntry(
				id = id, uid = SyncIds.canonical(e.key), todoId = e.todoKey?.let { todoKeys[it] }, status = e.status,
				companyId = company(e.companyKey), clientId = client(e.companyKey, e.clientKey), activity = e.activity, notes = e.notes,
				zoneId = e.zone, createdUtcMs = first, updatedUtcMs = segs.mapNotNull { it.endUtcMs }.maxOrNull() ?: first,
			)
			segments += segs
			refs += e.refs.map { LegacyRef(id, it) }
			entryIndex[id] = e
		}
		val absences = data.absences.mapIndexedNotNull { i, a ->
			val day = runCatching { LocalDate.parse(a.date).toEpochDay() }.getOrNull() ?: return@mapIndexedNotNull null.also { skipped += a.date to "date" }
			val time = a.time?.let { runCatching { LocalTime.parse(it).let { lt -> lt.hour * 60 + lt.minute } }.getOrNull() ?: return@mapIndexedNotNull null.also { skipped += a.date to "time ${a.time}" } }
			LegacyAbsence(i + 1L, SyncIds.nameBased("absence/${a.date}/${a.kind}/${a.time}"), day, a.kind, time)
		}

		val r = ActivityMigration.migrate(
			LegacyData(entries, segments, refs, todos, absences, schedule, lunchBreaks, daysOff, zone),
		)
		val newRefs = r.refs.groupBy({ it.entryUid }, { it.value })
		val newSegments = r.segments.groupBy { it.entryUid }
		fun date(d: Long?) = d?.let { LocalDate.ofEpochDay(it).toString() }
		fun time(m: Int?) = m?.let { "%02d:%02d".format(it / 60, it % 60) }
		fun instant(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).toString() }
		val out = r.activities.map { a ->
			val base = a.id?.let(entryIndex::getValue)
			val segs = base?.segments.orEmpty() + newSegments[a.uid].orEmpty().map {
				XSegment("ABSENCE", instant(it.startUtcMs)!!, instant(it.endUtcMs), source = "MANUAL")
			}
			XEntry(
				key = base?.key ?: a.uid,
				companyKey = companyKeyOf(a.companyId), clientKey = clientKeyOf(a.clientId), todoKey = null,
				role = base?.role, activity = a.activity, status = a.status.name, kind = a.kind.name,
				deletedUtc = instant(a.deletedUtcMs), closedUtc = instant(a.closedUtcMs), externalKey = a.externalKey,
				dueDate = date(a.dueEpochDay), dueTime = time(a.dueTimeMin), plannedDate = date(a.planEpochDay),
				plannedTime = time(a.planStartMin), durationMin = a.durationMin, priority = a.priority, absenceKind = a.absenceKind?.name,
				locality = base?.locality, locationKind = base?.locationKind, notes = a.notes, zone = a.zoneId,
				segments = segs, expenses = base?.expenses.orEmpty(), refs = base?.refs.orEmpty() + newRefs[a.uid].orEmpty(),
				tags = base?.tags.orEmpty(), materials = base?.materials.orEmpty(),
			)
		}
		return Result(data.copy(version = 2, todos = emptyList(), absences = emptyList(), entries = out), skipped)
	}

	private class Bad(message: String) : Exception(message)
}
