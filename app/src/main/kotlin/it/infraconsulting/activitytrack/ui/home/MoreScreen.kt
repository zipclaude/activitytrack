/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.domain.policy.Feature
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
	val policy = LocalPolicy.current
	ScreenScaffold(title = stringResource(R.string.nav_more), onBack = null) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			/* what the policy keeps (issue #48) */
			val rows = listOfNotNull(
				Triple(Screen.Export, R.string.export_title, R.drawable.ic_menu_import_export).takeIf { policy.enabled(Feature.IMPORT) || policy.enabled(Feature.EXPORT) || policy.enabled(Feature.RESTORE) },
				Triple(Screen.MasterData, R.string.md_title, R.drawable.ic_menu_folder),
				Triple(Screen.Settings, R.string.settings_title, R.drawable.ic_menu_settings),
			)
			items(rows) { (screen, label, icon) -> ClickableRow(stringResource(label), icon = icon) { onOpen(screen) } }
		}
	}
}
