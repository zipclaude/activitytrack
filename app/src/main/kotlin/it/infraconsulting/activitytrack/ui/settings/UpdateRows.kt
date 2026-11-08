/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.update.Updates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * Settings → Updates (SYNC.md §12.4, issue #1): the installed version, a newer release with its notes and, unless
 * the app comes from a store, its download; "Check now". From a store the check stays, the download is never offered.
 */
@Composable
fun UpdateRows() {
	val context = LocalContext.current
	val app = context.applicationContext as ActivityTrackApp
	val updates = app.updates
	val available by updates.available.collectAsState()
	val error by updates.error.collectAsState()
	val download by updates.download.collectAsState()
	Text(stringResource(R.string.update_installed, app.container.appVersion))
	if (updates.fromStore)
		Text(stringResource(R.string.update_from_store), color = MaterialTheme.colorScheme.onSurfaceVariant)
	available?.let { r ->
		Text(stringResource(if (r.beta) R.string.update_text_beta else R.string.update_text, r.version), color = MaterialTheme.colorScheme.primary)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			if (!updates.fromStore)
				Button(onClick = { updates.dismissDownloadError(); updates.startDownload(r) }, enabled = download !is Updates.Download.Running) {
					Text(stringResource(R.string.update_download))
				}
			OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }) { Text(stringResource(R.string.update_notes)) }
		}
	}
	when (val d = download) {
		is Updates.Download.Running -> Text(stringResource(R.string.update_downloading, d.percent), color = MaterialTheme.colorScheme.onSurfaceVariant)
		is Updates.Download.Failed -> Text(stringResource(R.string.update_dl_failed, d.message), color = MaterialTheme.colorScheme.error)
		Updates.Download.Idle -> Unit
	}
	error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
	if (available == null && error == null && updates.checkedUtcMs > 0) {
		val at = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(updates.checkedUtcMs).atZone(ZoneId.systemDefault()))
		Text(stringResource(R.string.update_none, at), color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
	if (updates.canCheck) OutlinedButton(onClick = { Updates.now(context) }, modifier = Modifier) { Text(stringResource(R.string.update_check_now)) }
}
