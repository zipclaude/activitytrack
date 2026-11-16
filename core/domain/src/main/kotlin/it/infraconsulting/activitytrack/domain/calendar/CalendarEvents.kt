/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.domain.calendar

import it.infraconsulting.activitytrack.domain.activity.AbsencePlanner
import it.infraconsulting.activitytrack.domain.export.EntryMeta
import it.infraconsulting.activitytrack.domain.report.DayTotals
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/* Localised words of the event description (the domain has no resources). */
data class EventLabels(
	val work: String,
	val travel: String,
	val pause: String,
	val refs: String,
	val km: String,
	val tags: EventTags = EventTags(),
	/* an absence's title by its kind (ABSENCE, HOLIDAY, LEAVE, SICK); [absence] without one */
	val absence: String = "",
	val absenceKinds: Map<String, String> = emptyMap(),
)

/*
 * Hashtags on the last description line, one per kind of time in the event
 * (M9, owner: "i tag per i diversi segmenti"), so calendar searches find
 * work, travel, pause and planned blocks apart; blank = no tag.
 */
data class EventTags(val work: String = "", val travel: String = "", val pause: String = "", val planned: String = "") {
	companion object {
		/* always Italian, whatever the phone language (owner, 2026-10-18): searches stay the same */
		val ITALIAN = EventTags("#lavoro", "#viaggio", "#pausa", "#pianificata")
	}
}

/* The plan of a TODO activity, shown as a tentative event (M9; [uid] the activity's). */
data class TodoEvent(
	val uid: String,
	/* null: a to-do of no company (issue #48) */
	val companyId: Long?,
	val start: Instant,
	val end: Instant,
	val title: String,
	val client: String,
	val notes: String,
	val refs: List<String>,
	/* planned by day only (issue #52): an all-day event on [day] */
	val allDay: Boolean = false,
	val day: java.time.LocalDate? = null,
)

/*
 * An event to keep in an external calendar (M5): one per entry and report
 * day, [key] = "<entry uid>/<yyyy-mm-dd>" is stored in the event so the next
 * sync finds it again; the plan of a TODO activity uses "plan:<uid>" and is
 * [tentative] (activity model 1.0; "todo:<uid>" before, removed once).
 */
data class PlannedEvent(
	val key: String,
	val companyId: Long?,
	val start: Instant,
	val end: Instant,
	val title: String,
	val description: String,
	val location: String?,
	val tentative: Boolean = false,
	/* all-day: [start] and [end] are UTC midnights, as calendar providers want them */
	val allDay: Boolean = false,
)

/*
 * Report lines -> calendar events (owner decisions 2026-10-17): the same
 * lines as the reports (day attribution, rounding), shown from the entry
 * start (a running entry ends "now"); title "Client – Activity"; the
 * description carries rounded work/travel/pause, km, references and notes,
 * the location the locality. No coordinates. With [zone], the absences
 * too (issue #188).
 */
object CalendarEvents {
	fun plan(
		days: List<DayTotals>,
		meta: Map<Long, EntryMeta>,
		uids: Map<Long, String>,
		companies: Map<Long, Long?>,
		labels: EventLabels,
		zone: ZoneId? = null,
	): List<PlannedEvent> = days.flatMap { d ->
		lines(d, meta, uids, companies, labels) + if (zone == null) emptyList() else absences(d, meta, uids, companies, labels, zone)
	}

	/*
	 * The absences of a day (issue #188): "Ferie" etc. from the first to the
	 * last segment, an all-day event when the day is covered 00:00-23:59.
	 */
	private fun absences(
		d: DayTotals,
		meta: Map<Long, EntryMeta>,
		uids: Map<Long, String>,
		companies: Map<Long, Long?>,
		labels: EventLabels,
		zone: ZoneId,
	): List<PlannedEvent> = d.absences.mapNotNull { a ->
		val m = meta[a.entryId] ?: return@mapNotNull null
		val uid = uids[a.entryId] ?: return@mapNotNull null
		val allDay = AbsencePlanner.isWholeDay(d.day, a.firstStart, a.lastEnd, zone)
		val utc = ZoneOffset.UTC
		PlannedEvent(
			key = "$uid/${d.day}",
			companyId = companies[a.entryId],
			start = if (allDay) d.day.atStartOfDay(utc).toInstant() else a.firstStart,
			end = if (allDay) d.day.plusDays(1).atStartOfDay(utc).toInstant() else maxOf(a.lastEnd, a.firstStart.plusSeconds(60)),
			title = m.absenceKind?.let { labels.absenceKinds[it] } ?: labels.absence.ifBlank { m.company },
			description = m.notes,
			location = null,
			allDay = allDay,
		)
	}

	private fun lines(
		d: DayTotals,
		meta: Map<Long, EntryMeta>,
		uids: Map<Long, String>,
		companies: Map<Long, Long?>,
		labels: EventLabels,
	): List<PlannedEvent> =
		d.lines.mapNotNull { l ->
			val m = meta[l.entryId] ?: return@mapNotNull null
			val uid = uids[l.entryId] ?: return@mapNotNull null
			val title = listOf(m.client.ifBlank { m.company }, m.activity).filter(String::isNotBlank).joinToString(" – ")
			val parts = buildList {
				add(listOfNotNull(
					"${labels.work} ${hm(l.roundedWork)}".takeIf { !l.work.isZero },
					"${labels.travel} ${hm(l.roundedTravel)}".takeIf { !l.travel.isZero },
					"${labels.pause} ${hm(l.roundedPause)}".takeIf { !l.pause.isZero },
				).joinToString(" · "))
				m.km?.let { add("%.1f %s".format(it, labels.km)) }
				if (m.externalRefs.isNotEmpty())
					add("${labels.refs}: ${m.externalRefs.joinToString(", ")}")
				if (m.notes.isNotBlank())
					add(m.notes)
				add(listOfNotNull(
					labels.tags.work.takeIf { !l.work.isZero },
					labels.tags.travel.takeIf { !l.travel.isZero },
					labels.tags.pause.takeIf { !l.pause.isZero },
				).filter(String::isNotBlank).plus(m.tags.map { "#$it" }).joinToString(" "))
			}.filter(String::isNotBlank)
			PlannedEvent(
				key = "$uid/${d.day}",
				companyId = companies[l.entryId],
				start = l.firstStart,
				/* calendars reject empty events */
				end = maxOf(l.lastEnd, l.firstStart.plusSeconds(60)),
				title = title.ifBlank { m.company },
				description = parts.joinToString("\n"),
				location = m.locality.ifBlank { null },
			)
		}

	/* Planned to-dos: "Client – Title", notes, references and the planned tag. */
	fun planTodos(todos: List<TodoEvent>, labels: EventLabels): List<PlannedEvent> = todos.map { t ->
		val parts = buildList {
			if (t.notes.isNotBlank())
				add(t.notes)
			if (t.refs.isNotEmpty())
				add("${labels.refs}: ${t.refs.joinToString(", ")}")
			add(labels.tags.planned)
		}.filter(String::isNotBlank)
		val utc = java.time.ZoneOffset.UTC
		PlannedEvent(
			key = PLAN_KEY + t.uid,
			companyId = t.companyId,
			start = if (t.allDay && t.day != null) t.day.atStartOfDay(utc).toInstant() else t.start,
			end = if (t.allDay && t.day != null) t.day.plusDays(1).atStartOfDay(utc).toInstant() else maxOf(t.end, t.start.plusSeconds(60)),
			allDay = t.allDay && t.day != null,
			title = listOf(t.client, t.title).filter(String::isNotBlank).joinToString(" – "),
			description = parts.joinToString("\n"),
			location = null,
			tentative = true,
		)
	}

	const val PLAN_KEY = "plan:"

	/* the key of the plans before 1.0: such events are stale, the sync removes them */
	const val LEGACY_TODO_KEY = "todo:"

	private fun hm(d: Duration): String = "%d:%02d".format(d.toHours(), d.toMinutesPart())
}
