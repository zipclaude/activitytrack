/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold

/* "More" tab: the secondary destinations. */
@Composable
fun MoreScreen(onOpen: (Screen) -> Unit) {
	ScreenScaffold(title = stringResource(R.string.nav_more), onBack = null) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			val rows = listOf(
				Screen.Todos to R.string.todo_title,
				Screen.Export to R.string.export_title,
				Screen.MasterData to R.string.md_title,
				Screen.Gestures to R.string.gestures_title,
				Screen.Settings to R.string.settings_title,
			)
			items(rows) { (screen, label) -> ClickableRow(stringResource(label)) { onOpen(screen) } }
		}
	}
}
