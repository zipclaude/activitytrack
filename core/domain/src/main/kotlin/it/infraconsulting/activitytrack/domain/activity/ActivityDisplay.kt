/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import it.infraconsulting.activitytrack.domain.todo.dueOverdue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* The state shown for an activity (2.2), in the order the rules are tried; icon and colour live in the UI. */
enum class DisplayState {
	DELETED,	/* bin, grey */
	ABSENCE,	/* dot, pink/fuchsia */
	LATE,		/* exclamation mark, brown */
	PLANNED,	/* clock, grey */
	TODO,		/* check box, white */
	WORK,		/* play, green */
	TRAVEL,		/* car, blue */
	PAUSE,		/* pause, yellow */
	SUSPENDED,	/* crescent moon, orange */
	DONE,		/* check mark, greenish */
	INCOMPLETE,	/* stop, reddish */
}

/* A segment as the activity rules see it; [end] null while open; [planned] only in a TODO activity (1.2). */
data class ActivitySegment(
	val kind: SegmentKind,
	val start: Instant,
	val end: Instant?,
	val planned: Boolean = false,
) {
	init {
		require(end == null || !end.isBefore(start)) { "end ($end) before start ($start)" }
	}
}

/*
 * What the displayed state depends on, independent of persistence:
 * [hasClient] a client is set, [plan] and [dueDay]/[dueMin] the to-do
 * fields, [missingRequired] a field the company's plugin requires is empty.
 */
data class ActivitySnapshot(
	val kind: ActivityKind,
	val status: ActivityStatus,
	val segments: List<ActivitySegment> = emptyList(),
	val deletedAt: Instant? = null,
	val closedAt: Instant? = null,
	val hasClient: Boolean = false,
	val plan: TodoPlan? = null,
	val dueDay: LocalDate? = null,
	val dueMin: Int? = null,
	val missingRequired: Boolean = false,
) {
	val real: List<ActivitySegment> get() = segments.filter { !it.planned }
	val planned: List<ActivitySegment> get() = segments.filter { it.planned }
}

/* "In <state> dalle <since>"; [since] null when there is nothing to tell. */
data class Displayed(val state: DisplayState, val since: Instant?)

object ActivityDisplay {
	/* The displayed state (2.2): the first rule that matches wins. */
	fun display(a: ActivitySnapshot, now: Instant, zone: ZoneId): Displayed {
		val real = a.real
		/* the last segment's end; closedAt when there is no segment (9-11) */
		val lastEnd = real.mapNotNull { it.end }.maxOrNull() ?: a.closedAt.takeIf { real.isEmpty() }
		return when {
			a.deletedAt != null -> Displayed(DisplayState.DELETED, a.deletedAt)
			a.kind == ActivityKind.ABSENCE ->
				Displayed(DisplayState.ABSENCE, real.filter { it.kind == SegmentKind.ABSENCE }.minOfOrNull { it.start })
			a.status == ActivityStatus.TODO -> {
				val since = a.plan?.start(zone) ?: a.planned.minOfOrNull { it.start }
				val state = when {
					late(a, now, zone) -> DisplayState.LATE
					a.plan != null || a.dueDay != null || a.planned.isNotEmpty() -> DisplayState.PLANNED
					else -> DisplayState.TODO
				}
				Displayed(state, since)
			}
			a.status == ActivityStatus.RUNNING -> {
				val open = real.filter { it.end == null && it.kind != SegmentKind.ABSENCE }.maxByOrNull { it.start }
				when (open?.kind) {
					SegmentKind.WORK -> Displayed(DisplayState.WORK, open.start)
					SegmentKind.TRAVEL -> Displayed(DisplayState.TRAVEL, open.start)
					SegmentKind.PAUSE -> Displayed(DisplayState.PAUSE, open.start)
					else -> Displayed(DisplayState.SUSPENDED, lastEnd)
				}
			}
			complete(a) -> Displayed(DisplayState.DONE, lastEnd)
			else -> Displayed(DisplayState.INCOMPLETE, lastEnd)
		}
	}

	/*
	 * A TODO activity is late (2.3) when the due date has passed, or its
	 * last planned segment has ended, or, without planned segments, the plan
	 * is over. "Ended" as a plan block is over: strictly after the end.
	 */
	fun late(a: ActivitySnapshot, now: Instant, zone: ZoneId): Boolean {
		if (dueOverdue(a.dueDay, a.dueMin, now, zone))
			return true
		val planned = a.planned
		if (planned.isNotEmpty()) {
			val ends = planned.map { it.end }
			/* a planned segment without end has not ended */
			return ends.all { it != null } && now.isAfter(ends.maxOf { it!! })
		}
		return a.plan?.over(now, zone) == true
	}

	/*
	 * A CLOSED activity is complete (2.4) with no real segment at all (a task
	 * ticked off), or with a client, a WORK segment and every required field.
	 */
	fun complete(a: ActivitySnapshot): Boolean {
		val real = a.real
		return real.isEmpty() || (a.hasClient && real.any { it.kind == SegmentKind.WORK } && !a.missingRequired)
	}
}
