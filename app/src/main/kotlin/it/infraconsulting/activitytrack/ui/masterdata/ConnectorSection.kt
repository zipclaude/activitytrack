/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.connect.plugin.OAuthPkce
import it.infraconsulting.activitytrack.connect.plugin.PluginRegistry
import it.infraconsulting.activitytrack.connect.plugin.PluginSync
import it.infraconsulting.activitytrack.connect.plugin.PluginSyncReport
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.domain.interchange.Capability
import it.infraconsulting.activitytrack.domain.interchange.Connectors
import it.infraconsulting.activitytrack.domain.interchange.SettingField
import it.infraconsulting.activitytrack.domain.plugin.Auth
import it.infraconsulting.activitytrack.domain.plugin.OptionType
import it.infraconsulting.activitytrack.domain.plugin.Plugin
import it.infraconsulting.activitytrack.domain.plugin.PluginOption
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.settings.CompanyCalendarRow
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Company's management system (M4): external code (its key in that system),
 * connector and its settings. The built-in connectors keep their fields;
 * a plugin of the data folder (issue #48) gets its card from the
 * descriptor's options, with the login and "Sync now". Secret values go
 * to the keystore-backed SecretStore, plain ones into company.connector_config.
 */
@Composable
fun ConnectorSection(company: CompanyEntity, vm: MasterDataViewModel) {
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val secrets = app.secrets
	val plugins by app.plugins.plugins.collectAsState()
	val plugin = company.connectorId?.let { plugins[it] }
	val connector = Connectors.byId(company.connectorId)
	val config = remember(company.connectorConfig) {
		runCatching { JSONObject(company.connectorConfig ?: "{}") }.getOrDefault(JSONObject())
	}
	var editingCode by remember { mutableStateOf(false) }
	var editingField by remember { mutableStateOf<SettingField?>(null) }
	val notSet = stringResource(R.string.connector_not_set)
	val choices = Connectors.all.map { it.id to it.name } + plugins.values.map { it.id to it.name }
	val selected = plugin?.id ?: connector.id

	Column {
		SectionHeader(stringResource(R.string.connector_title))
		ClickableRow(stringResource(R.string.connector_external_code), company.externalCode ?: notSet) { editingCode = true }
		Column(Modifier.padding(horizontal = 16.dp)) {
			ChoiceField(stringResource(R.string.connector_plugin), choices, selected) { id ->
				if (id != selected) {
					company.connectorId?.let { old -> app.oauth.logout(company.id, old) }
					vm.save(company.copy(connectorId = id, connectorConfig = null)) { secrets.clear(company.id) }
				}
			}
			CompanyCalendarRow(company.id)
			if (plugin == null && Capability.FILE_IMPORT in connector.capabilities && connector.settingFields.isEmpty())
				Text(stringResource(R.string.connector_generic_hint), style = MaterialTheme.typography.bodySmall)
		}
		if (plugin != null)
			PluginCard(company, plugin, config, vm)
		else
			for (f in connector.settingFields) {
				val value = if (f.secret) (if (secrets.has(company.id, f.key)) "••••••" else notSet) else config.optString(f.key).ifEmpty { notSet }
				ClickableRow(f.label, value) { editingField = f }
			}
	}

	if (editingCode) {
		var code by remember { mutableStateOf(company.externalCode.orEmpty()) }
		EditDialog(
			title = stringResource(R.string.connector_external_code),
			error = null,
			onDismiss = { editingCode = false },
			onConfirm = { vm.save(company.copy(externalCode = code.trim().ifEmpty { null })) { editingCode = false } },
		) { TextInput(stringResource(R.string.connector_external_code), code, { code = it }) }
	}
	editingField?.let { f ->
		var value by remember { mutableStateOf(if (f.secret) "" else config.optString(f.key)) }
		EditDialog(
			title = f.label,
			error = null,
			onDismiss = { editingField = null },
			onConfirm = {
				if (f.secret) {
					secrets.put(company.id, f.key, value)
					editingField = null
				} else {
					val updated = JSONObject(config.toString()).put(f.key, value.trim())
					vm.save(company.copy(connectorConfig = updated.toString())) { editingField = null }
				}
			},
		) { TextInput(f.label, value, { value = it }) }
	}
}

/* A label of the descriptor in the phone's language, else English, else [fallback]. */
private fun Map<String, String>.pick(language: String, fallback: String) = this[language] ?: this["en"] ?: fallback

/* The settings card of a plugin: its options, the login (OAuth), "Sync now" and the last report. */
@Composable
private fun PluginCard(company: CompanyEntity, plugin: Plugin, config: JSONObject, vm: MasterDataViewModel) {
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val language = LocalConfiguration.current.locales[0].language
	val scope = rememberCoroutineScope()
	val notSet = stringResource(R.string.connector_not_set)
	var editing by remember { mutableStateOf<PluginOption?>(null) }
	/* recomposes the secret and login rows after a change */
	var tick by remember { mutableIntStateOf(0) }
	var running by remember { mutableStateOf(false) }
	var report by remember { mutableStateOf<PluginSyncReport?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	val env = remember(company, plugin, tick) { app.plugins.env(company, plugin) }
	val oauth = plugin.auth is Auth.OAuth2Pkce
	val loggedIn = remember(company, plugin, tick) { app.oauth.loggedIn(company.id, plugin.id) }

	fun save(key: String, value: Any?) {
		val updated = JSONObject(config.toString()).apply { if (value == null) remove(key) else put(key, value) }
		vm.save(company.copy(connectorConfig = updated.toString())) { tick++ }
	}

	Column {
		Column(Modifier.padding(horizontal = 16.dp)) {
			for (o in plugin.options) {
				val label = o.label.pick(language, o.key)
				when (o.type) {
					OptionType.CHOICE -> ChoiceField(
						label, o.choices.map { (k, l) -> k to l.pick(language, k) }, env.option(o.key)?.let { it.toString().trim('"') } ?: o.choices.keys.first(),
					) { save(o.key, it) }
					OptionType.BOOL -> SwitchRow(label, env.option(o.key)?.toString() == "true") { save(o.key, it) }
					else -> Unit
				}
			}
		}
		for (o in plugin.options.filter { it.type !in setOf(OptionType.CHOICE, OptionType.BOOL) }) {
			val value = when (o.type) {
				OptionType.SECRET -> if (app.secrets.has(company.id, PluginRegistry.secretField(plugin.id, o.key))) "••••••" else notSet
				else -> env.option(o.key)?.toString()?.trim('"')?.ifEmpty { null } ?: notSet
			}
			ClickableRow(o.label.pick(language, o.key) + if (o.required) " *" else "", value) { editing = o }
		}
		val missing = env.missingOptions()
		if (missing.isNotEmpty())
			Text(
				stringResource(R.string.plugin_options_missing, missing.joinToString { it.label.pick(language, it.key) }),
				style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
				modifier = Modifier.padding(horizontal = 16.dp),
			)
		if (oauth) {
			if (loggedIn)
				ClickableRow(stringResource(R.string.plugin_log_out), stringResource(R.string.plugin_logged_in_state)) { app.oauth.logout(company.id, plugin.id); tick++ }
			else
				ClickableRow(stringResource(R.string.plugin_log_in), notSet) {
					scope.launch {
						runCatching { app.oauth.startLogin(OAuthPkce.Client.of(company.id, env)) }.onFailure { failure = it.message }
					}
				}
		}
		if (missing.isEmpty() && (!oauth || loggedIn))
			ClickableRow(stringResource(if (running) R.string.plugin_syncing else R.string.plugin_sync_now), plugin.name) {
				if (!running)
					scope.launch {
						running = true
						try {
							report = PluginSync(app).sync(company.id, send = plugin.export != null)
						} catch (e: Exception) {
							failure = e.message ?: e.javaClass.simpleName
						} finally {
							running = false
						}
					}
			}
	}

	editing?.let { o ->
		val label = o.label.pick(language, o.key)
		var value by remember { mutableStateOf(if (o.type == OptionType.SECRET) "" else env.option(o.key)?.toString()?.trim('"').orEmpty()) }
		var error by remember { mutableStateOf(false) }
		EditDialog(
			title = label,
			error = null,
			onDismiss = { editing = null },
			onConfirm = {
				val v = value.trim()
				when (o.type) {
					OptionType.SECRET -> {
						app.secrets.put(company.id, PluginRegistry.secretField(plugin.id, o.key), v.ifEmpty { null })
						tick++
						editing = null
					}
					OptionType.INT -> {
						val n = v.toIntOrNull()
						if (v.isNotEmpty() && (n == null || (o.min != null && n < o.min!!) || (o.max != null && n > o.max!!)))
							error = true
						else {
							save(o.key, n)
							editing = null
						}
					}
					OptionType.URL -> if (v.isNotEmpty() && !v.startsWith("https://")) error = true else { save(o.key, v.ifEmpty { null }); editing = null }
					else -> { save(o.key, v.ifEmpty { null }); editing = null }
				}
			},
		) {
			TextInput(
				label, value, { value = it; error = false },
				keyboardType = when (o.type) { OptionType.INT -> KeyboardType.Number; OptionType.URL -> KeyboardType.Uri; OptionType.SECRET -> KeyboardType.Password; else -> KeyboardType.Text },
				isError = error,
			)
			if (error)
				Text(
					stringResource(if (o.type == OptionType.URL) R.string.plugin_https_only else R.string.err_invalid_value),
					color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
				)
		}
	}
	report?.let { r ->
		AlertDialog(
			onDismissRequest = { report = null },
			title = { Text(stringResource(R.string.plugin_sync_done, plugin.name)) },
			text = {
				Text(
					stringResource(R.string.plugin_sync_report, r.items, r.clients, r.contacts, r.todos, r.sent, r.updated, r.deleted) +
						r.skipped.joinToString("") { (what, why) -> "\n• $what: $why" },
				)
			},
			confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(android.R.string.ok)) } },
		)
	}
	failure?.let { msg ->
		AlertDialog(
			onDismissRequest = { failure = null },
			title = { Text(stringResource(R.string.plugin_sync_failed, plugin.name)) },
			text = { Text(msg) },
			confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(android.R.string.ok)) } },
		)
	}
}
