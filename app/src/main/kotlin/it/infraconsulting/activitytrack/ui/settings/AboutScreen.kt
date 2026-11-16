/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SectionHeader

/* Who holds the copyright and where the source is: names, not translated. */
private const val COPYRIGHT = "Copyright © 2026 Flavio Visentin"
private const val SOURCE = "https://gitlab.com/activitytrack/activitytrack"

/*
 * The licence texts in the package (assets), by the name each licence has: the application's
 * own first, then the notices and the licences of the components of other authors
 * (assets/licenses/THIRD-PARTY.txt lists them; issue #198).
 */
internal val LICENCE_TEXTS = listOf(
	"GNU General Public License 3" to "licenses/GPL-3.0.txt",
	null to "licenses/THIRD-PARTY.txt",
	"Apache License 2.0" to "licenses/Apache-2.0.txt",
	"MapLibre Native Android" to "licenses/MapLibre-Native-Android.md",
	"SIL Open Font License 1.1 (Noto Sans)" to "fonts/OFL.txt",
)

/*
 * About and licences (issue #198): the version, the copyright, the licence of the application
 * and the notices the components of other authors ask to go with the package.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
	var shown by rememberSaveable { mutableStateOf<String?>(null) }
	val thirdParty = stringResource(R.string.about_third_party)
	val texts = LICENCE_TEXTS.map { (name, path) -> (name ?: thirdParty) to path }
	texts.firstOrNull { it.second == shown }?.let { (name, path) ->
		BackHandler { shown = null }
		LicenceText(name, path) { shown = null }
		return
	}
	val context = LocalContext.current
	val version = (context.applicationContext as ActivityTrackApp).container.appVersion
	val uri = LocalUriHandler.current
	ScreenScaffold(title = stringResource(R.string.about_title), onBack = onBack) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			item {
				Text(
					stringResource(R.string.app_name) + " " + version,
					style = MaterialTheme.typography.titleLarge,
					modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
				)
				Text(COPYRIGHT, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
				Text(stringResource(R.string.about_licence), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
				SectionHeader(stringResource(R.string.about_source))
				Text(
					SOURCE,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier.clickable { runCatching { uri.openUri(SOURCE) } }.padding(horizontal = 16.dp, vertical = 8.dp),
				)
				SectionHeader(stringResource(R.string.about_licences))
			}
			items(texts) { (name, path) -> ClickableRow(name) { shown = path } }
		}
	}
}

/* One text of the package, as it is: paragraph by paragraph, so that a long licence scrolls well. */
@Composable
private fun LicenceText(title: String, path: String, onBack: () -> Unit) {
	val context = LocalContext.current
	val paragraphs = remember(path) {
		runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("").split(Regex("\n\\s*\n")).filter { it.isNotBlank() }
	}
	ScreenScaffold(title = title, onBack = onBack) { padding ->
		SelectionContainer {
			LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
				items(paragraphs) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
			}
		}
	}
}
