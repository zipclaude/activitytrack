/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.location.LocationTracker
import it.infraconsulting.activitytrack.quick.ToggleTileService

/*
 * Location access levels and battery optimisation (DESIGN §6.2). Both are
 * granted on system pages, so the state is re-read whenever the screen
 * resumes.
 */
@Composable
fun LocationPermissionRows() {
	val context = LocalContext.current
	var resumed by remember { mutableIntStateOf(0) }
	LifecycleResumeEffect(Unit) {
		resumed++
		onPauseOrDispose {}
	}
	val refresh = { (context.applicationContext as ActivityTrackApp).notificationSync.refresh() }

	val foreground = remember(resumed) { LocationTracker.hasPermission(context) }
	val background = remember(resumed) { granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
	val battery = remember(resumed) { ignoresBatteryOptimizations(context) }

	val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
		resumed++
		refresh()
	}
	/* from API 30 this opens the system page with "Allow all the time" */
	val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		resumed++
		refresh()
	}

	StatusRow(
		label = stringResource(R.string.perm_location),
		state = stringResource(
			when {
				background -> R.string.perm_location_always
				foreground -> R.string.perm_location_in_use
				else -> R.string.perm_denied
			},
		),
		action = when {
			!foreground -> stringResource(R.string.perm_grant) to {
				askForeground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
			}
			!background -> stringResource(R.string.perm_allow_always) to {
				askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
			}
			else -> null
		},
	)
	StatusRow(
		label = stringResource(R.string.perm_battery),
		state = stringResource(if (battery) R.string.perm_battery_unrestricted else R.string.perm_battery_optimized),
		action = if (battery) null else stringResource(R.string.perm_grant) to { requestIgnoreBatteryOptimizations(context) },
	)
}

@Composable
private fun StatusRow(label: String, state: String, action: Pair<String, () -> Unit>?) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Column(modifier = Modifier.weight(1f)) {
			Text(label)
			Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		action?.let { (text, onClick) -> TextButton(onClick = onClick) { Text(text) } }
	}
}

private fun granted(context: Context, permission: String) =
	context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun ignoresBatteryOptimizations(context: Context): Boolean =
	context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/* Sideloaded app (no store policy): the direct request is allowed and needs one tap. */
@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
	context.startActivity(
		Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
	)
}

/*
 * Quick Settings tile: some launchers (OxygenOS) do not list third-party
 * tiles in the panel editor; the system dialog adds it directly (API 33).
 */
@Composable
fun AddTileRow() {
	val context = LocalContext.current
	var result by remember { mutableIntStateOf(-1) }
	val appName = stringResource(R.string.app_name)
	StatusRow(
		label = stringResource(R.string.tile_add),
		state = stringResource(
			when (result) {
				StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
				StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.tile_added
				else -> R.string.tile_add_hint
			},
		),
		action = stringResource(R.string.action_add) to {
			context.getSystemService(StatusBarManager::class.java).requestAddTileService(
				ComponentName(context, ToggleTileService::class.java),
				appName,
				Icon.createWithResource(context, R.drawable.ic_cmd_play),
				context.mainExecutor,
			) { result = it }
		},
	)
}

