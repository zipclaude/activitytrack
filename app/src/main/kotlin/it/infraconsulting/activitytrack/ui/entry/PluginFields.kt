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
import it.infraconsulting.activitytrack.data.db.RemoteItemEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.plugin.EntryField
import it.infraconsulting.activitytrack.domain.plugin.FieldType
import it.infraconsulting.activitytrack.domain.plugin.PluginEnv
import it.infraconsulting.activitytrack.domain.plugin.RemoteItem
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
import kotlinx.serialization.json.Json
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
@Composable
fun PluginFieldsSection(e: TimeEntryEntity) {
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val c = app.container
	val plugins by app.plugins.plugins.collectAsState()
	val company by produceState<it.infraconsulting.activitytrack.data.db.CompanyEntity?>(null, e.companyId) { value = e.companyId?.let { c.masterData.company(it) } }
	val plugin = company?.connectorId?.let { plugins[it] }?.takeIf { it.entryFields.isNotEmpty() } ?: return
	val co = company ?: return
	val itemsFlow = remember(co.id, plugin.id) { c.pluginData.observeAllItems(co.id, plugin.id) }
	val items by itemsFlow.collectAsState(initial = emptyList())
	val todo by produceState<it.infraconsulting.activitytrack.data.db.TodoEntity?>(null, e.todoId) { value = e.todoId?.let { c.pluginData.todo(it) } }
	val clientCode by produceState<String?>(null, e.clientId) { value = e.clientId?.let { c.masterData.client(it)?.externalCode } }
	val language = LocalConfiguration.current.locales[0].language
	val scope = rememberCoroutineScope()
	var editing by remember { mutableStateOf<EntryField?>(null) }

	fun obj(s: String?) = s?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
	val own = obj(e.pluginFields) ?: JsonObject(emptyMap())
	val fields = HashMap<String, JsonElement>().apply {
		obj(todo?.pluginFields)?.let { putAll(it) }
		putAll(own)
	}
	val todoJson: JsonElement = todo?.takeIf { it.externalKey != null }?.let {
		JsonObject(mapOf("id" to JsonPrimitive(it.id), "externalKey" to JsonPrimitive(it.externalKey!!), "title" to JsonPrimitive(it.title)))
	} ?: JsonNull
	val entryJson = JsonObject(mapOf("client" to JsonObject(mapOf("code" to (clientCode?.let(::JsonPrimitive) ?: JsonNull)))))
	fun itemJson(i: RemoteItemEntity) =
		RemoteItem(i.list, i.id, i.parentId, i.name, i.clientCode, i.kind, i.active, i.order, obj(i.extra) ?: JsonObject(emptyMap())).asJson()
	lateinit var env: PluginEnv
	fun parentOf(f: EntryField): String? = f.parent?.let { text(it.eval(null, env)) }
	fun selected(list: String): JsonElement {
		val f = plugin.entryFields.firstOrNull { it.list == list } ?: return JsonNull
		val id = fields[f.key]?.let(::text) ?: return JsonNull
		val parent = parentOf(f)
		return items.firstOrNull { it.list == list && it.id == id && (f.parent == null || it.parentId == parent) }?.let(::itemJson) ?: JsonNull
	}
	env = app.plugins.env(co, plugin).with { r ->
		when (r.root) {
			"entry" -> PluginEnv.dig(entryJson, r.path)
			"todo" -> if (r.path.isEmpty()) todoJson else PluginEnv.dig(todoJson, r.path)
			"field" -> r.path.firstOrNull()?.let { fields[it] }?.let { PluginEnv.dig(it, r.path.drop(1)) } ?: JsonNull
			"list" -> if (r.path.getOrNull(1) == "selected") PluginEnv.dig(selected(r.path[0]), r.path.drop(2)) else JsonNull
			else -> null
		}
	}
	for (f in plugin.entryFields)
		if (f.key !in fields)
			f.default?.eval(null, env)?.takeIf { it !is JsonNull }?.let { fields[f.key] = it }

	fun save(key: String, value: JsonElement?) {
		val updated = HashMap(own)
		if (value == null || value is JsonNull) updated.remove(key) else updated[key] = value
		/* a new parent choice clears the fields that hang on it */
		plugin.entryFields.filter { f -> f.parent?.toString() == "@field.$key" }.forEach { updated.remove(it.key) }
		scope.launch { c.pluginData.setEntryFields(e.id, if (updated.isEmpty()) null else JsonObject(updated).toString()) }
	}

	Column {
		SectionHeader(plugin.name)
		val padded = Modifier.padding(horizontal = 16.dp)
		run {
			for (f in plugin.entryFields) {
				if (truthy(f.hidden.eval(null, env)))
					continue
				val label = f.label[language] ?: f.label["en"] ?: f.key
				val importance = if (truthy(f.required.eval(null, env))) Importance.REQUIRED else Importance.OPTIONAL
				val value = fields[f.key]
				when (f.type) {
					FieldType.LIST -> {
						val parent = parentOf(f)
						val filters = f.filter.mapValues { (_, x) -> text(x.eval(null, env))?.ifEmpty { null } }
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
