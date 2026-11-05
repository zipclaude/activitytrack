/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.maps.MapOrigin
import it.infraconsulting.activitytrack.maps.OfflineMapError
import it.infraconsulting.activitytrack.maps.OfflineMapPhase
import it.infraconsulting.activitytrack.maps.OfflineMapState
import java.util.Locale

/*
 * Settings → Location → Offline maps (issue #155): the maps on the phone, those ActivityTrack
 * Web sent with the sync and those imported from the data folder, each deletable; the import
 * of the files copied into <data folder>/maps.
 */
@Composable
fun OfflineMapRows(rootUri: String?) {
	val context = LocalContext.current
	val app = context.applicationContext as ActivityTrackApp
	val maps = app.container.offlineMaps
	val installed by maps.installed.collectAsState()
	val state by maps.state.collectAsState()
	val paired by app.syncAccount.info.collectAsState()

	Text(stringResource(R.string.offline_map_title), style = MaterialTheme.typography.titleMedium)
	if (installed.isEmpty())
		Text(stringResource(R.string.offline_map_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
	for (m in installed)
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(
				stringResource(
					R.string.offline_map_item, m.name, String.format(Locale.getDefault(), "%.1f", m.bytes / 1e6),
					stringResource(if (m.origin == MapOrigin.SERVER) R.string.offline_map_from_server else R.string.offline_map_from_folder),
				),
				modifier = Modifier.weight(1f),
			)
			TextButton(onClick = { maps.delete(m.id) }) { Text(stringResource(R.string.offline_map_delete)) }
		}
	when (val s = state) {
		is OfflineMapState.Working -> {
			Text(stringResource(if (s.phase == OfflineMapPhase.SERVER) R.string.offline_map_working_server else R.string.offline_map_working_folder))
			s.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
		}
		is OfflineMapState.Imported -> Text(stringResource(R.string.offline_map_imported, s.imported, s.skipped))
		is OfflineMapState.Failed -> Text(
			stringResource(
				when (s.error) {
					OfflineMapError.NO_FOLDER -> R.string.offline_map_err_folder
					OfflineMapError.NOTHING_FOUND -> R.string.offline_map_err_nothing
					OfflineMapError.INVALID_FILE -> R.string.offline_map_err_invalid
					OfflineMapError.TOO_LARGE -> R.string.offline_map_err_large
					OfflineMapError.CORRUPT -> R.string.offline_map_err_corrupt
					OfflineMapError.NO_NETWORK -> R.string.offline_map_err_network
					OfflineMapError.FAILED -> R.string.offline_map_err_failed
				},
			),
			color = MaterialTheme.colorScheme.error,
		)
		OfflineMapState.Idle -> {}
	}
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		OutlinedButton(
			onClick = { maps.importFromFolder(app.container.userDataDir, rootUri?.toUri()) },
			enabled = state !is OfflineMapState.Working,
		) { Text(stringResource(R.string.offline_map_import)) }
	}
	Text(
		stringResource(if (paired != null) R.string.offline_map_hint_server else R.string.offline_map_hint_folder),
		style = MaterialTheme.typography.bodySmall,
	)
}
