/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/* M0 placeholder: the tracking UI is implemented in M1. */
class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			ActivityTrackTheme {
				Scaffold { padding ->
					Column(
						modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
						Text(stringResource(R.string.m0_placeholder))
					}
				}
			}
		}
	}
}

@Composable
fun ActivityTrackTheme(content: @Composable () -> Unit) {
	val ctx = LocalContext.current
	val dark = isSystemInDarkTheme()
	/* Dynamic color is always available from API 31; minSdk is 33. */
	val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
	MaterialTheme(colorScheme = scheme, content = content)
}
