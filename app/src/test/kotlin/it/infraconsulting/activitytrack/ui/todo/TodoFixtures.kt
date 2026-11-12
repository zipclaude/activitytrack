/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.todo

import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import java.time.ZoneId

/* a to-do of the tests as a TODO activity (closed when done, deleted when ignored) */
fun todoRow(
	id: Long = 0, title: String = "t", notes: String? = null, planEpochDay: Long? = null, planStartMin: Int? = null,
	dueEpochDay: Long? = null, dueTimeMin: Int? = null, priority: Int? = null, done: Boolean = false, doneUtcMs: Long? = null,
	ignoredUtcMs: Long? = null,
): TimeEntryEntity = TimeEntryEntity(
	id = id, uid = null, companyId = null, clientId = null, activity = title, notes = notes,
	status = if (done) ActivityStatus.CLOSED else ActivityStatus.TODO, zoneId = ZoneId.systemDefault().id,
	dueEpochDay = dueEpochDay, dueTimeMin = dueTimeMin, priority = priority ?: 0, planEpochDay = planEpochDay, planStartMin = planStartMin,
	closedUtcMs = if (done) doneUtcMs ?: 0 else null, deletedUtcMs = ignoredUtcMs, createdUtcMs = 0, updatedUtcMs = 0,
)
