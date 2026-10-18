/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

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
import org.json.JSONObject

/*
 * Company's management system (M4): external code (its key in that system),
 * connector plugin and the plugin's settings; secret fields go to the
 * keystore-backed SecretStore, plain ones into company.connector_config.
 */
@Composable
fun ConnectorSection(company: CompanyEntity, vm: MasterDataViewModel) {
	val secrets = (LocalContext.current.applicationContext as ActivityTrackApp).secrets
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
			if (Capability.FILE_IMPORT in connector.capabilities && connector.settingFields.isEmpty())
				Text(stringResource(R.string.connector_generic_hint), style = MaterialTheme.typography.bodySmall)
		}
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
