/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.policy.PolicyChanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * What waits for the user's confirmation (owner, security review 2026-11):
 * an unsigned policy.json, a new plugin or one whose addresses changed.
 * Nothing of it is applied or used until confirmed; set by PolicyLoader
 * and PluginRegistry at every check, shown by [TrustDialog].
 */
class TrustPrompts {
	data class PolicyPrompt(val fingerprint: String, val name: String, val changes: List<PolicyChanges.Change>)

	data class PluginPrompt(
		val file: String,
		val name: String,
		val fingerprint: String,
		val endpoints: Set<String>,
		val isNew: Boolean,
		val added: List<String>,
		val removed: List<String>,
	)

	val policy = MutableStateFlow<PolicyPrompt?>(null)
	val plugins = MutableStateFlow<List<PluginPrompt>>(emptyList())

	/* fingerprints put off with "Later": asked again when the app comes to the foreground */
	val later = MutableStateFlow<Set<String>>(emptySet())

	/* also right after an answer, so the dialog goes while the answer is carried out */
	fun putOff(fingerprint: String) = later.update { it + fingerprint }

	fun foreground() {
		later.value = emptySet()
	}
}

/* The first thing waiting: the policy before the plugins (it decides which plugins count). */
@Composable
fun TrustDialog(app: ActivityTrackApp) {
	val policy by app.trust.policy.collectAsState()
	val plugins by app.trust.plugins.collectAsState()
	val later by app.trust.later.collectAsState()
	val p = policy?.takeIf { it.fingerprint !in later }
	if (p != null) {
		TrustAlert(
			title = stringResource(R.string.trust_policy_title),
			intro = stringResource(R.string.trust_policy_intro),
			name = stringResource(R.string.trust_policy_name, p.name),
			lines = p.changes.map(::changeLine).ifEmpty { listOf(stringResource(R.string.trust_no_changes)) },
			fingerprint = p.fingerprint,
			accept = stringResource(R.string.trust_apply),
			onAccept = { app.trust.putOff(p.fingerprint); app.appScope.launch { PolicyLoader.check(app, confirmed = p.fingerprint) } },
			onRefuse = { app.trust.putOff(p.fingerprint); app.appScope.launch { PolicyLoader.decline(app, p.fingerprint) } },
			onLater = { app.trust.putOff(p.fingerprint) },
		)
		return
	}
	val g = plugins.firstOrNull { it.fingerprint !in later } ?: return
	TrustAlert(
		title = stringResource(if (g.isNew) R.string.trust_plugin_title_new else R.string.trust_plugin_title_changed, g.name),
		intro = stringResource(if (g.isNew) R.string.trust_plugin_intro_new else R.string.trust_plugin_intro_changed, g.file),
		name = null,
		lines = g.added.map { "+ $it" } + g.removed.map { "− $it" },
		fingerprint = g.fingerprint,
		accept = stringResource(R.string.trust_use),
		onAccept = { app.trust.putOff(g.fingerprint); app.appScope.launch { app.plugins.confirm(g) } },
		onRefuse = { app.trust.putOff(g.fingerprint); app.appScope.launch { app.plugins.decline(g) } },
		onLater = { app.trust.putOff(g.fingerprint) },
	)
}

internal fun changeLine(c: PolicyChanges.Change): String = when {
	c.old == null -> "+ ${c.path}: ${c.new}"
	c.new == null -> "− ${c.path}"
	else -> "${c.path}: ${c.old} → ${c.new}"
}

/* "ab12 cd34 ef56 7890…": enough to compare at a glance, the whole one on request */
private fun short(fingerprint: String) = fingerprint.take(16).chunked(4).joinToString(" ") + "…"

private const val MAX_LINES = 30

@Composable
private fun TrustAlert(
	title: String,
	intro: String,
	name: String?,
	lines: List<String>,
	fingerprint: String,
	accept: String,
	onAccept: () -> Unit,
	onRefuse: () -> Unit,
	onLater: () -> Unit,
) {
	var full by remember(fingerprint) { mutableStateOf(false) }
	val context = LocalContext.current
	AlertDialog(
		onDismissRequest = onLater,
		properties = DialogProperties(dismissOnClickOutside = false),
		title = { Text(title) },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(intro)
				name?.let { Text(it, fontWeight = FontWeight.Bold) }
				Column {
					for (l in lines.take(MAX_LINES))
						Text(l, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
					if (lines.size > MAX_LINES)
						Text(context.getString(R.string.policy_more_errors, lines.size - MAX_LINES), style = MaterialTheme.typography.bodySmall)
				}
				Text(stringResource(R.string.trust_fingerprint), fontWeight = FontWeight.Bold)
				SelectionContainer {
					Text(
						if (full) fingerprint.chunked(4).joinToString(" ") else short(fingerprint),
						style = MaterialTheme.typography.bodySmall,
						fontFamily = FontFamily.Monospace,
					)
				}
				if (!full)
					TextButton(onClick = { full = true }) { Text(stringResource(R.string.trust_show_full)) }
			}
		},
		confirmButton = {
			TextButton(onClick = onAccept) { Text(accept) }
		},
		dismissButton = {
			Row {
				TextButton(onClick = onLater) { Text(stringResource(R.string.trust_later)) }
				TextButton(onClick = onRefuse) { Text(stringResource(R.string.trust_refuse)) }
			}
		},
	)
}
