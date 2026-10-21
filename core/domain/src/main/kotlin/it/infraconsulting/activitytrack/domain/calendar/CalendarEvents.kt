/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.calendar

import it.infraconsulting.activitytrack.domain.export.EntryMeta
import it.infraconsulting.activitytrack.domain.report.DayTotals
import java.time.Duration
import java.time.Instant

/* Localised words of the event description (the domain has no resources). */
data class EventLabels(
	val work: String,
	val travel: String,
	val pause: String,
	val refs: String,
	val km: String,
	val tags: EventTags = EventTags(),
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

/* A planned, open to-do to show as a tentative event (M9). */
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
)

/*
 * An event to keep in an external calendar (M5): one per entry and report
 * day, [key] = "<entry uid>/<yyyy-mm-dd>" is stored in the event so the next
 * sync finds it again; planned to-dos use "todo:<uid>" and are [tentative].
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
)

/*
 * Report lines -> calendar events (owner decisions 2026-10-17): the same
 * lines as the reports (day attribution, rounding), shown from the entry
 * start (a running entry ends "now"); title "Client – Activity"; the
 * description carries rounded work/travel/pause, km, references and notes,
 * the location the locality. No coordinates.
 */
object CalendarEvents {
	fun plan(
		days: List<DayTotals>,
		meta: Map<Long, EntryMeta>,
		uids: Map<Long, String>,
		companies: Map<Long, Long?>,
		labels: EventLabels,
	): List<PlannedEvent> = days.flatMap { d ->
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
		PlannedEvent(
			key = TODO_KEY + t.uid,
			companyId = t.companyId,
			start = t.start,
			end = maxOf(t.end, t.start.plusSeconds(60)),
			title = listOf(t.client, t.title).filter(String::isNotBlank).joinToString(" – "),
			description = parts.joinToString("\n"),
			location = null,
			tentative = true,
		)
	}

	const val TODO_KEY = "todo:"

	private fun hm(d: Duration): String = "%d:%02d".format(d.toHours(), d.toMinutesPart())
}
