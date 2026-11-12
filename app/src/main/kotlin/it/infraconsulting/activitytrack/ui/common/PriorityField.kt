/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.activity.Priority

/* Translated name of a priority level (ACTIVITY-MODEL.md 5). */
@StringRes
fun priorityName(p: Priority): Int = when (p) {
	Priority.NONE -> R.string.priority_none
	Priority.LOW -> R.string.priority_low
	Priority.MEDIUM -> R.string.priority_medium
	Priority.HIGH -> R.string.priority_high
	Priority.URGENT -> R.string.priority_urgent
	Priority.EMERGENCY -> R.string.priority_emergency
}

/* Drop-down of the six levels, none by default (owner, 2026-11-11). */
@Composable
fun PriorityField(selected: Priority, onSelect: (Priority) -> Unit) {
	SearchableChoiceField(
		label = stringResource(R.string.priority),
		options = Priority.entries.map { it to stringResource(priorityName(it)) },
		selected = selected,
		onSelect = onSelect,
	)
}
