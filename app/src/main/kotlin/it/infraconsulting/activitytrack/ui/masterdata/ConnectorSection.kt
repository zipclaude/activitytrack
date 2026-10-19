/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

import it.infraconsulting.activitytrack.connect.antares.AntaresConfig
import it.infraconsulting.activitytrack.domain.interchange.AntaresConnector
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.domain.interchange.Capability
import it.infraconsulting.activitytrack.domain.interchange.Connectors
import it.infraconsulting.activitytrack.domain.interchange.SettingField
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.settings.CompanyCalendarRow
import org.json.JSONObject

/*
 * Company's management system (M4): external code (its key in that system),
 * connector plugin and the plugin's settings; secret fields go to the
 * keystore-backed SecretStore, plain ones into company.connector_config.
 */
@Composable
fun ConnectorSection(company: CompanyEntity, vm: MasterDataViewModel) {
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val secrets = app.secrets
	val scope = rememberCoroutineScope()
	val connector = Connectors.byId(company.connectorId)
	val config = remember(company.connectorConfig) {
		runCatching { JSONObject(company.connectorConfig ?: "{}") }.getOrDefault(JSONObject())
	}
	var editingCode by remember { mutableStateOf(false) }
	var editingField by remember { mutableStateOf<SettingField?>(null) }
	val notSet = stringResource(R.string.connector_not_set)

	Column {
		SectionHeader(stringResource(R.string.connector_title))
		ClickableRow(stringResource(R.string.connector_external_code), company.externalCode ?: notSet) { editingCode = true }
		Column(Modifier.padding(horizontal = 16.dp)) {
			ChoiceField(stringResource(R.string.connector_plugin), Connectors.all.map { it.id to it.name }, connector.id) { id ->
				if (id != connector.id)
					vm.save(company.copy(connectorId = id, connectorConfig = null)) { secrets.clear(company.id) }
			}
			CompanyCalendarRow(company.id)
			if (Capability.FILE_IMPORT in connector.capabilities && connector.settingFields.isEmpty())
				Text(stringResource(R.string.connector_generic_hint), style = MaterialTheme.typography.bodySmall)
		}
		for (f in connector.settingFields) {
			val default = when (f.key) {
				AntaresConnector.API_URL -> AntaresConnector.DEMO_API
				AntaresConnector.IDENTITY_URL -> AntaresConnector.DEMO_IDENTITY
				else -> null
			}.takeIf { connector == AntaresConnector }
			val value = if (f.secret) (if (secrets.has(company.id, f.key)) "••••••" else notSet)
				else config.optString(f.key).ifEmpty { default ?: notSet }
			ClickableRow(f.label, value) { editingField = f }
		}
		if (connector == AntaresConnector) {
			/* login in the browser (public client, PKCE); the person becomes the OwnerId of the activities */
			val user = config.optString(AntaresConnector.USERNAME)
			val logged = app.antares.loggedIn(company.id)
			var error by remember { mutableStateOf<String?>(null) }
			var syncing by remember { mutableStateOf(false) }
			var result by remember { mutableStateOf<Result<it.infraconsulting.activitytrack.connect.antares.AntaresSyncReport>?>(null) }
			result?.let { r -> SyncReportDialog(r) { result = null } }
			Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
				Text(
					if (logged) stringResource(R.string.antares_connected_as, user.ifEmpty { "?" }) else stringResource(R.string.antares_not_connected),
					style = MaterialTheme.typography.bodyMedium,
				)
				androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
					androidx.compose.material3.OutlinedButton(onClick = {
						error = null
						scope.launch {
							runCatching { app.antares.startLogin(company.id, AntaresConfig.of(company.connectorConfig)) }
								.onFailure { error = it.message }
						}
					}) { Text(stringResource(R.string.antares_login)) }
					if (logged)
						androidx.compose.material3.TextButton(onClick = { app.antares.logout(company.id); vm.save(company.copy(connectorConfig = JSONObject(config.toString()).apply { remove(AntaresConnector.USERNAME); remove(AntaresConnector.PERSON_ID) }.toString())) {} }) {
							Text(stringResource(R.string.antares_logout))
						}
				}
				if (logged)
					androidx.compose.material3.Button(enabled = !syncing, onClick = {
						syncing = true
						scope.launch {
							result = runCatching { it.infraconsulting.activitytrack.connect.antares.AntaresSync(app).sync(company.id) }
							syncing = false
						}
					}) { Text(stringResource(if (syncing) R.string.antares_syncing else R.string.antares_sync)) }
				Text(stringResource(R.string.antares_redirect_hint, app.antares.redirectUri), style = MaterialTheme.typography.bodySmall)
				error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
			}
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

/* What "Sync" did: counts, entries left out with the reason, or the error. */
@Composable
private fun SyncReportDialog(r: Result<it.infraconsulting.activitytrack.connect.antares.AntaresSyncReport>, onClose: () -> Unit) {
	androidx.compose.material3.AlertDialog(
		onDismissRequest = onClose,
		title = { Text(stringResource(R.string.antares_sync_title)) },
		text = {
			Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
				r.onSuccess { s ->
					Text(stringResource(R.string.antares_sync_counts, s.portfolios, s.tickets, s.sent, s.updated, s.deleted))
					if (s.skipped.isNotEmpty()) {
						Text(stringResource(R.string.antares_sync_skipped), style = MaterialTheme.typography.titleSmall)
						for ((title, why) in s.skipped.take(20))
							Text("• $title: " + stringResource(if (why == "activity type") R.string.antares_missing_type else R.string.antares_missing_portfolio), style = MaterialTheme.typography.bodySmall)
					}
				}.onFailure { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) }
			}
		},
		confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(stringResource(R.string.action_ok)) } },
	)
}
