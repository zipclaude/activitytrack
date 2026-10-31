/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.update.Updates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/* Settings → Updates (SYNC.md §12.4): the installed version, a newer release, "Check now". */
@Composable
fun UpdateRows() {
	val context = LocalContext.current
	val app = context.applicationContext as ActivityTrackApp
	val updates = app.updates
	val available by updates.available.collectAsState()
	Text(stringResource(R.string.update_installed, app.container.appVersion))
	if (updates.fromStore) {
		Text(stringResource(R.string.update_from_store), color = MaterialTheme.colorScheme.onSurfaceVariant)
		return
	}
	available?.let { r ->
		Text(stringResource(if (r.beta) R.string.update_text_beta else R.string.update_text, r.version), color = MaterialTheme.colorScheme.primary)
		Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }) { Text(stringResource(R.string.update_download)) }
	}
	if (available == null && updates.checkedUtcMs > 0) {
		val at = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(updates.checkedUtcMs).atZone(ZoneId.systemDefault()))
		Text(stringResource(R.string.update_none, at), color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
	if (updates.applies) OutlinedButton(onClick = { Updates.now(context) }) { Text(stringResource(R.string.update_check_now)) }
}
