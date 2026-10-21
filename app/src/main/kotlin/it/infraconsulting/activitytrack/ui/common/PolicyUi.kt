/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.domain.policy.Policy

/*
 * The policy in force (issue #48) for every screen: provided at the root
 * (MainActivity), the app as shipped (Policy.NONE) elsewhere.
 */
val LocalPolicy = staticCompositionLocalOf { Policy.NONE }

/* The colours of the field being drawn (policy fields.<f>.colors); null = the app's own. */
data class FieldColors(val invalid: Color? = null, val valid: Color? = null)

val LocalFieldColors = staticCompositionLocalOf { FieldColors() }

/* false: the field shows its value but cannot be changed (policy fields.<f>.enabled) */
val LocalFieldEnabled = staticCompositionLocalOf { true }

@Composable
fun enabled(f: Feature): Boolean = LocalPolicy.current.enabled(f)

/* The field's name: the policy's label in the phone's language, its English one, else the app's text. */
@Composable
fun fieldLabel(f: Field, @StringRes default: Int): String =
	LocalPolicy.current.field(f).labelFor(LocalConfiguration.current.locales[0].language) ?: stringResource(default)

/* The field's importance: REQUIRED when the policy requires it, else [default]. */
@Composable
fun fieldImportance(f: Field, default: Importance?): Importance? =
	if (LocalPolicy.current.field(f).required == true) Importance.REQUIRED else default

/*
 * Draws [content] as field [f] of the policy: nothing when hidden; its
 * colours and enabled state passed to the common text and choice fields.
 */
@Composable
fun PolicyField(f: Field, content: @Composable () -> Unit) {
	val p = LocalPolicy.current.field(f)
	if (!p.visible)
		return
	CompositionLocalProvider(
		LocalFieldColors provides FieldColors(p.invalidColor?.let { Color(it) }, p.validColor?.let { Color(it) }),
		LocalFieldEnabled provides p.enabled,
	) { content() }
}

@Composable
fun fieldShown(f: Field): Boolean = LocalPolicy.current.field(f).visible

/* companies, clients and contacts created in the app, or only by imports (policy masterData) */
@Composable
fun canCreateMasterData(): Boolean = LocalPolicy.current.masterData == it.infraconsulting.activitytrack.domain.policy.MasterDataSource.USER
