/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.notify.NotificationChannels
import it.infraconsulting.activitytrack.ui.common.ClickableRow

/*
 * Notification type per event (backlog 5, 2026-10-18): each kind of
 * notification is an Android channel, whose banner, sound (chosen),
 * vibration and importance the system settings page sets; a row opens it.
 */
@Composable
fun NotificationRows() {
	val context = LocalContext.current
	Text(
		stringResource(R.string.notif_settings_hint),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(vertical = 4.dp),
	)
	for ((id, label) in NotificationChannels.LIST)
		ClickableRow(stringResource(label)) {
			context.startActivity(
				Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
					.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
					.putExtra(Settings.EXTRA_CHANNEL_ID, id),
			)
		}
	ClickableRow(stringResource(R.string.notif_all)) {
		context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
	}
}
