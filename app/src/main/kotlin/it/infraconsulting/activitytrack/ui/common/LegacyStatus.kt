/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus

/*
 * TODO(activity-1.0-ui): the 0.9 states as the old screens, widgets and
 * notifications branch on them, computed from the activity model 1.0
 * (ACTIVITY-MODEL.md 2): ACTIVE = RUNNING with a segment open, SUSPENDED =
 * RUNNING without, TO_COMPLETE = CLOSED without client or description (an
 * approximation of 2.4 without the plugin's required fields), TODO = a
 * to-do. The new screens use ActivityDisplay.display instead.
 */
enum class LegacyStatus { TODO, ACTIVE, SUSPENDED, TO_COMPLETE, CLOSED }

val TimeEntryEntity.legacy: LegacyStatus
	get() = when (status) {
		ActivityStatus.TODO -> LegacyStatus.TODO
		ActivityStatus.RUNNING -> if (mode != null) LegacyStatus.ACTIVE else LegacyStatus.SUSPENDED
		ActivityStatus.CLOSED ->
			if (kind == ActivityKind.ACTIVITY && (clientId == null || activity.isNullOrBlank())) LegacyStatus.TO_COMPLETE else LegacyStatus.CLOSED
	}
