/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

/* Compact display (M11, owner 2026-10-18): cards read it to tighten paddings and buttons. */
val LocalCompact = staticCompositionLocalOf { false }

@Composable
fun ActivityTrackTheme(compact: Boolean = false, content: @Composable () -> Unit) {
	val ctx = LocalContext.current
	/* Dynamic color is always available from API 31; minSdk is 33. */
	val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
	val typography = Typography().let { if (compact) it.scaled(COMPACT_FONT) else it }
	CompositionLocalProvider(LocalCompact provides compact) {
		MaterialTheme(colorScheme = scheme, typography = typography, content = content)
	}
}

/* compact text: a bit smaller, every style alike */
private const val COMPACT_FONT = 0.88f

private fun TextStyle.scaled(f: Float) = copy(fontSize = fontSize * f, lineHeight = lineHeight * f)

private fun Typography.scaled(f: Float) = Typography(
	displayLarge = displayLarge.scaled(f), displayMedium = displayMedium.scaled(f), displaySmall = displaySmall.scaled(f),
	headlineLarge = headlineLarge.scaled(f), headlineMedium = headlineMedium.scaled(f), headlineSmall = headlineSmall.scaled(f),
	titleLarge = titleLarge.scaled(f), titleMedium = titleMedium.scaled(f), titleSmall = titleSmall.scaled(f),
	bodyLarge = bodyLarge.scaled(f), bodyMedium = bodyMedium.scaled(f), bodySmall = bodySmall.scaled(f),
	labelLarge = labelLarge.scaled(f), labelMedium = labelMedium.scaled(f), labelSmall = labelSmall.scaled(f),
)
