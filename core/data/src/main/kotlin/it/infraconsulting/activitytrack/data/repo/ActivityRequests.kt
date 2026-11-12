/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import it.infraconsulting.activitytrack.domain.todo.TodoPlan

/*
 * What the creation buttons of the activity model 1.0 send to
 * EntryRepository (docs/ACTIVITY-MODEL.md 3, the table "Creation").
 */

/*
 * "Lavoro · Adesso / Già iniziata", "Viaggio": a RUNNING activity; only the
 * client is required, except for a TRAVEL start. With [todoId] the TODO
 * activity is started instead of a new one being created.
 */
data class StartRequest(
	val mode: SegmentKind,
	val clientId: Long?,
	val companyId: Long? = null,
	val roleId: Long? = null,
	/* chosen start (M17 editable times); null = now (rounded when saving rounded times is on) */
	val startUtcMs: Long? = null,
	/*
	 * Chosen end (M22): in the past the activity starts and stops at once, as
	 * with Stop; in the future it is the planned end, asked about then.
	 */
	val endUtcMs: Long? = null,
	val activity: String? = null,
	val locality: String? = null,
	val locationKind: LocationKind? = null,
	/* the TODO activity to start (a to-do before 1.0) */
	val todoId: Long? = null,
)

/* A segment typed by hand: a past activity's, or a planned one of a TODO activity. */
data class SegmentInput(val kind: SegmentKind, val startUtcMs: Long, val endUtcMs: Long, val kmDeclared: Double? = null)

/* "Lavoro · Passata": a CLOSED activity with the segments entered, saved at once. */
data class PastRequest(
	val details: EntryDetails,
	val segments: List<SegmentInput>,
)

/*
 * "Da fare": a TODO activity with the dialog's fields. A plan needs a day
 * (the time is optional), a duration needs a start; [planned] segments
 * are drawn dashed and never counted (1.2).
 */
data class TodoRequest(
	val companyId: Long?,
	val clientId: Long? = null,
	val activity: String? = null,
	val notes: String? = null,
	val roleId: Long? = null,
	val dueEpochDay: Long? = null,
	val dueTimeMin: Int? = null,
	val planEpochDay: Long? = null,
	val planStartMin: Int? = null,
	val durationMin: Int? = null,
	/* 0 none ... 5 emergency */
	val priority: Int = 0,
	val planned: List<SegmentInput> = emptyList(),
	/* key in the management system (imports) */
	val externalKey: String? = null,
	val pluginFields: String? = null,
)

/*
 * "Assenza" (3.1): company optional (null = every company), kind, notes,
 * from day to day (default the same), optional start and end time.
 */
data class AbsenceRequest(
	val companyId: Long? = null,
	val absenceKind: AbsenceKind = AbsenceKind.ABSENCE,
	val notes: String? = null,
	val fromEpochDay: Long,
	val toEpochDay: Long = fromEpochDay,
	val startMin: Int? = null,
	val endMin: Int? = null,
)

/* Descriptive fields of an activity, edited in the detail dialog. */
data class EntryDetails(
	val companyId: Long?,
	val clientId: Long?,
	val roleId: Long?,
	val activity: String?,
	val locality: String?,
	val locationKind: LocationKind?,
	val notes: String?,
	/* a contact of the activity's client (issue #44) */
	val contactId: Long? = null,
)

/* Defaults proposed when a client is picked. */
data class ClientDefaults(val activity: String?, val roleId: Long?)

/*
 * The times rules of the moment (ACTIVITY-MODEL.md 4): times editable
 * (setting and policy), saving rounded times, the rounding rules whose
 * quanta the rounded instants use.
 */
data class TimeRules(
	val editableTimes: Boolean = true,
	val roundStoredTimes: Boolean = false,
	val rounding: ReportRounding = ReportRounding.DEFAULT,
) {
	val rounds: Boolean get() = it.infraconsulting.activitytrack.domain.activity.InstantRounding.effective(roundStoredTimes, editableTimes)
}

/* An earlier activity whose end lay after another one's start was pulled back to it (4.1): the UI tells. */
data class PulledBack(val entryId: Long, val oldEndUtcMs: Long, val newEndUtcMs: Long)

/* The plan of a TODO activity (null without one). */
fun TimeEntryEntity.plan(): TodoPlan? = TodoPlan.of(planEpochDay, planStartMin, durationMin)

/* A TODO activity not deleted: a commitment (reminders, calendars). */
fun TimeEntryEntity.isOpenTodo(): Boolean = status == it.infraconsulting.activitytrack.domain.activity.ActivityStatus.TODO && deletedUtcMs == null

fun TimeEntryEntity.isAbsence(): Boolean = kind == it.infraconsulting.activitytrack.domain.activity.ActivityKind.ABSENCE

/* running with a segment open (the 0.9 "ACTIVE") */
fun TimeEntryEntity.isActive(): Boolean = status == it.infraconsulting.activitytrack.domain.activity.ActivityStatus.RUNNING && mode != null

/* running without a segment open (the 0.9 "SUSPENDED") */
fun TimeEntryEntity.isSuspended(): Boolean = status == it.infraconsulting.activitytrack.domain.activity.ActivityStatus.RUNNING && mode == null
