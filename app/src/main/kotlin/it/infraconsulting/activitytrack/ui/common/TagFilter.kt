/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.R

/*
 * Filter by one tag (M19, owner backlog 13 "later: tag filters in agenda
 * and reports"): a chip opening the tags in use; null = every entry. Not
 * shown while there are no tags and none is selected.
 */
@Composable
fun TagFilterChip(tags: List<String>, selected: String?, label: String? = null, onSelect: (String?) -> Unit) {
	if (tags.isEmpty() && selected == null)
		return
	var open by remember { mutableStateOf(false) }
	val all = stringResource(R.string.tag_filter_all)
	Box {
		FilterChip(
			selected = selected != null,
			onClick = { open = true },
			/* [label]: a compact chip that names the filter, coloured while one is chosen (Home, owner 2026-10-28) */
			label = { Text((label ?: selected?.let { "#$it" } ?: all) + " ▾") },
		)
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			DropdownMenuItem(text = { Text(all) }, onClick = { open = false; onSelect(null) })
			for (t in (tags + listOfNotNull(selected)).distinct().sorted())
				DropdownMenuItem(text = { Text("#$t") }, onClick = { open = false; onSelect(t) })
		}
	}
}
