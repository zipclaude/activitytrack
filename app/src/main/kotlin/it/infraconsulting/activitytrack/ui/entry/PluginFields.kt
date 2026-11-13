/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import it.infraconsulting.activitytrack.connect.plugin.PluginRoots
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.plugin.EntryField
import it.infraconsulting.activitytrack.domain.plugin.EntryFieldScope
import it.infraconsulting.activitytrack.domain.plugin.Expr
import it.infraconsulting.activitytrack.domain.plugin.FieldType
import it.infraconsulting.activitytrack.domain.plugin.eval
import it.infraconsulting.activitytrack.domain.plugin.text
import it.infraconsulting.activitytrack.domain.plugin.truthy
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.TextInput
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The entry fields of the company's plugin (issue #48 B5, docs/PLUGINS.md
 * §8), under the plugin's name: lists from the last sync (a child list
 * follows its parent's choice, a filter on the entry's client), switches
 * and text. Values stored with the entry; an imported to-do's values and
 * the descriptor's defaults show until the user picks another.
 */
/*
 * The entry's company and its plugin with entry fields, or null. Called
 * outside the lazy list (EntryForm): loaded inside an item, the first
 * composition would be empty, zero high, and the list would never show it.
 */
@Composable
fun rememberEntryPlugin(e: TimeEntryEntity): Pair<it.infraconsulting.activitytrack.data.db.CompanyEntity, it.infraconsulting.activitytrack.domain.plugin.Plugin>? {
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val plugins by app.plugins.plugins.collectAsState()
	val company by produceState<it.infraconsulting.activitytrack.data.db.CompanyEntity?>(null, e.companyId) { value = e.companyId?.let { app.container.masterData.company(it) } }
	val co = company ?: return null
	val plugin = co.connectorId?.let { plugins[it] }?.takeIf { it.entryFields.isNotEmpty() } ?: return null
	return co to plugin
}

@Composable
fun PluginFieldsSection(e: TimeEntryEntity, co: it.infraconsulting.activitytrack.data.db.CompanyEntity, plugin: it.infraconsulting.activitytrack.domain.plugin.Plugin) {
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val c = app.container
	val itemsFlow = remember(co.id, plugin.id) { c.pluginData.observeAllItems(co.id, plugin.id) }
	val items by itemsFlow.collectAsState(initial = emptyList())
	/* @entry and @activity as the send and the displayed state see them (PluginRoots), so the required fields agree */
	val roots by produceState<((Expr.Ref) -> JsonElement?)?>(null, e, co.id) {
		val client = e.clientId?.let { c.masterData.client(it) }
		value = PluginRoots.roots(PluginRoots.entryJson(e, co, client), PluginRoots.activityOf(c, e, co.id))
	}
	val language = LocalConfiguration.current.locales[0].language
	val scope = rememberCoroutineScope()
	var editing by remember { mutableStateOf<EntryField?>(null) }

	val own = PluginRoots.obj(e.pluginFields) ?: JsonObject(emptyMap())
	val remoteItems = remember(items) { items.map(PluginRoots::item) }
	/* until they are read: the record itself as @activity when the plugin imported it */
	val early = remember(e) {
		PluginRoots.roots(
			JsonNull,
			e.externalKey?.let { k -> JsonObject(mapOf("id" to JsonPrimitive(e.id), "externalKey" to JsonPrimitive(k), "title" to JsonPrimitive(e.activity.orEmpty()))) } ?: JsonNull,
		)
	}
	val fieldScope = EntryFieldScope(plugin, own, remoteItems, app.plugins.env(co, plugin), roots ?: early)
	val fields = fieldScope.fields

	fun save(key: String, value: JsonElement?) {
		val updated = HashMap(own)
		if (value == null || value is JsonNull) updated.remove(key) else updated[key] = value
		/* a new parent choice clears the fields that hang on it */
		plugin.entryFields.filter { f -> f.parent?.toString() == "@field.$key" }.forEach { updated.remove(it.key) }
		scope.launch { c.pluginData.setEntryFields(e.id, if (updated.isEmpty()) null else JsonObject(updated).toString()) }
	}

	Column {
		SectionHeader(plugin.name)
		val padded = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
		run {
			for (f in plugin.entryFields) {
				if (fieldScope.hidden(f))
					continue
				val label = f.label[language] ?: f.label["en"] ?: f.key
				val importance = if (fieldScope.required(f)) Importance.REQUIRED else Importance.OPTIONAL
				val value = fields[f.key]
				when (f.type) {
					FieldType.LIST -> {
						val parent = fieldScope.parentOf(f)
						val filters = f.filter.mapValues { (_, x) -> text(x.eval(null, fieldScope.env))?.ifEmpty { null } }
						val current = value?.let(::text)
						val options = items.filter { i ->
							i.list == f.list && (f.parent == null || i.parentId == parent) && (i.active || i.id == current) &&
								filters.all { (prop, want) -> want == null || when (prop) {
									"clientCode" -> i.clientCode.equals(want, ignoreCase = true)
									"kind" -> i.kind == want
									"parentId" -> i.parentId == want
									"id" -> i.id == want
									"name" -> i.name == want
									else -> true
								} }
						}
						Column(padded) {
							ChoiceField(
								label,
								listOf<Pair<String?, String>>(null to stringResource(R.string.field_none)) + options.map { it.id to it.name },
								current?.takeIf { id -> options.any { it.id == id } },
								importance,
							) { save(f.key, it?.let(::JsonPrimitive)) }
						}
					}
					FieldType.BOOL -> Column(padded) { SwitchRow(label, value?.let(::truthy) ?: false) { save(f.key, JsonPrimitive(it)) } }
					else -> ClickableRow(label, value?.let(::text)?.ifEmpty { null } ?: stringResource(R.string.connector_not_set)) { editing = f }
				}
			}
		}
	}

	editing?.let { f ->
		val label = f.label[language] ?: f.label["en"] ?: f.key
		var v by remember { mutableStateOf(fields[f.key]?.let(::text).orEmpty()) }
		EditDialog(
			title = label,
			error = null,
			onDismiss = { editing = null },
			onConfirm = {
				val t = v.trim()
				save(f.key, if (t.isEmpty()) null else if (f.type == FieldType.INT) t.toLongOrNull()?.let(::JsonPrimitive) else JsonPrimitive(t))
				editing = null
			},
		) { TextInput(label, v, { v = it }, if (f.type == FieldType.INT) KeyboardType.Number else KeyboardType.Text) }
	}
}
