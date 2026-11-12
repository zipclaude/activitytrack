/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.todo

import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.ui.common.LegacyStatus

/* TODO(activity-1.0-ui): the 0.9 to-dos of the tests as TODO activities (closed when done, deleted when ignored) */
fun todoRow(
	id: Long = 0, title: String = "t", notes: String? = null, planEpochDay: Long? = null, planStartMin: Int? = null,
	dueEpochDay: Long? = null, dueTimeMin: Int? = null, priority: Int? = null, done: Boolean = false, doneUtcMs: Long? = null,
	ignoredUtcMs: Long? = null,
): TimeEntryEntity = todoDraft(
	id = id, companyId = null, clientId = null, title = title, notes = notes, dueEpochDay = dueEpochDay, dueTimeMin = dueTimeMin,
	priority = priority, planEpochDay = planEpochDay, planStartMin = planStartMin, durationMin = null,
).copy(
	status = if (done) ActivityStatus.CLOSED else ActivityStatus.TODO,
	closedUtcMs = if (done) doneUtcMs ?: 0 else null,
	deletedUtcMs = ignoredUtcMs,
)

/* an activity in a 0.9 state */
fun entryRow(id: Long, s: LegacyStatus, zone: String, updatedUtcMs: Long): TimeEntryEntity = TimeEntryEntity(
	id = id, companyId = null, clientId = if (s == LegacyStatus.CLOSED) 1 else null, activity = if (s == LegacyStatus.CLOSED) "a" else null,
	status = when (s) {
		LegacyStatus.TODO -> ActivityStatus.TODO
		LegacyStatus.ACTIVE, LegacyStatus.SUSPENDED -> ActivityStatus.RUNNING
		LegacyStatus.TO_COMPLETE, LegacyStatus.CLOSED -> ActivityStatus.CLOSED
	},
	mode = SegmentKind.WORK.takeIf { s == LegacyStatus.ACTIVE }, zoneId = zone, createdUtcMs = 0, updatedUtcMs = updatedUtcMs,
)
