/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.SegmentInput
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.plugin.EntryField
import it.infraconsulting.activitytrack.domain.plugin.FieldType
import it.infraconsulting.activitytrack.domain.plugin.PluginEnv
import it.infraconsulting.activitytrack.domain.plugin.RemoteItem
import it.infraconsulting.activitytrack.domain.plugin.eval
import it.infraconsulting.activitytrack.domain.plugin.text
import it.infraconsulting.activitytrack.domain.plugin.truthy
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.domain.time.atMinute
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.PolicyField
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.TimeGridSheet
import it.infraconsulting.activitytrack.ui.common.commandStyle
import it.infraconsulting.activitytrack.ui.common.fieldImportance
import it.infraconsulting.activitytrack.ui.common.fieldLabel
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Pieces the three creation dialogs share (Da fare, Lavoro, Assenza):
 * the "Altre informazioni" toggle, notes and references, the list of
 * segments ("Tratti") with its split button, and the management system's
 * fields as the activity is not saved yet.
 */

/* "Altre informazioni ▾": reveals the less used fields. */
@Composable
fun MoreInfoToggle(open: Boolean, onToggle: () -> Unit) {
	TextButton(onClick = onToggle) { Text(stringResource(R.string.create_more_info) + if (open) " ▴" else " ▾") }
}

@Composable
fun NotesInput(notes: String, onChange: (String) -> Unit) {
	PolicyField(Field.NOTES) {
		TextInput(fieldLabel(Field.NOTES, R.string.field_notes), notes, onChange, singleLine = false, importance = fieldImportance(Field.NOTES, Importance.OPTIONAL))
	}
}

/* one external reference per line */
@Composable
fun RefsInput(refs: String, onChange: (String) -> Unit) {
	PolicyField(Field.REFS) {
		TextInput(fieldLabel(Field.REFS, R.string.create_external_refs), refs, onChange, singleLine = false, importance = fieldImportance(Field.REFS, Importance.OPTIONAL))
	}
}

fun refLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

/*
 * A segment typed in a dialog: minutes from midnight of the day the form
 * is about; an end past 1440 falls on the next day.
 */
data class SegDraft(val kind: SegmentKind, val startMin: Int, val endMin: Int) {
	fun toInput(day: LocalDate, zone: ZoneId) = SegmentInput(kind, day.atOffsetMinute(startMin, zone), day.atOffsetMinute(endMin, zone))

	val lengthMin: Int get() = endMin - startMin
}

/* wall-clock minute [min] from the midnight of this day, days beyond the first included (DST-safe) */
fun LocalDate.atOffsetMinute(min: Int, zone: ZoneId): Long = plusDays((min / 1440).toLong()).atMinute(min % 1440, zone).toEpochMilli()

/* the same, as a segment of a one-day plan or past activity */
fun SegmentInput.minutesFrom(day: LocalDate, zone: ZoneId): Pair<Int, Int> {
	fun min(ms: Long): Int {
		val at = java.time.Instant.ofEpochMilli(ms).atZone(zone)
		return (java.time.temporal.ChronoUnit.DAYS.between(day, at.toLocalDate()) * 1440 + at.hour * 60 + at.minute).toInt()
	}
	return min(startUtcMs) to min(endUtcMs)
}

@Composable
private fun kindName(kind: SegmentKind): String = stringResource(
	when (kind) {
		SegmentKind.TRAVEL -> R.string.cmd_travel
		SegmentKind.PAUSE -> R.string.cmd_pause
		else -> R.string.cmd_work
	},
)

private fun kindStyle(kind: SegmentKind) = commandStyle(
	when (kind) {
		SegmentKind.TRAVEL -> EntryCommand.TRAVEL
		SegmentKind.PAUSE -> EntryCommand.PAUSE
		else -> EntryCommand.WORK
	},
)!!

/*
 * "Tratti": the list of segments with a split button to add one of work,
 * travel or pause. [planned] draws them dashed (a to-do's plan, never
 * counted); [required] flags an empty list. [day] is the day the times
 * belong to.
 */
@Composable
fun SegmentsEditor(
	segments: List<SegDraft>,
	onChange: (List<SegDraft>) -> Unit,
	day: LocalDate,
	planned: Boolean = false,
	required: Boolean = false,
	kinds: List<SegmentKind> = listOf(SegmentKind.WORK, SegmentKind.TRAVEL, SegmentKind.PAUSE),
) {
	/* the segment being typed: its kind, and the index it replaces (null = a new one) */
	var editing by remember { mutableStateOf<Pair<SegmentKind, Int?>?>(null) }
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(stringResource(R.string.create_segments), style = MaterialTheme.typography.labelLarge)
		if (segments.isEmpty())
			Text(
				stringResource(if (planned) R.string.create_segments_planned_hint else R.string.create_segments_hint),
				style = MaterialTheme.typography.bodySmall,
				color = if (required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
			)
		segments.forEachIndexed { i, s ->
			val style = kindStyle(s.kind)
			val line = MaterialTheme.colorScheme.outline
			Row(
				modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
					.then(
						if (planned) Modifier.drawBehind {
							/* planned: dashed, like in the lists */
							drawRoundRect(
								line, cornerRadius = CornerRadius(8.dp.toPx()),
								style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
							)
						} else Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
					)
					.clickable { editing = s.kind to i }.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Box(Modifier.size(24.dp).clip(CircleShape).background(style.container), contentAlignment = Alignment.Center) {
					Icon(painterResource(style.icon), contentDescription = null, tint = style.content, modifier = Modifier.size(16.dp))
				}
				Spacer(Modifier.width(8.dp))
				Column(Modifier.weight(1f)) {
					Text(kindName(s.kind), style = MaterialTheme.typography.labelMedium)
					Text(
						formatMin(s.startMin % 1440) + " – " + (if (s.endMin == 1440) "24:00" else formatMin(s.endMin % 1440) + if (s.endMin > 1440) " +1" else "") +
							"  (%d:%02d)".format(s.lengthMin / 60, s.lengthMin % 60),
						style = MaterialTheme.typography.bodyMedium,
					)
				}
				IconButton(onClick = { onChange(segments.filterIndexed { j, _ -> j != i }) }) {
					Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.action_delete))
				}
			}
		}
		SegmentAddButton(kinds) { editing = it to null }
	}
	editing?.let { (kind, index) ->
		SegmentDialog(
			kind, day, segments.getOrNull(index ?: -1),
			onDone = { s ->
				onChange((if (index == null) segments + s else segments.mapIndexed { j, o -> if (j == index) s else o }).sortedBy { it.startMin })
				editing = null
			},
			onDismiss = { editing = null },
		)
	}
}

/* Split button: the first kind in one tap, the arrow opens the others. */
@Composable
private fun SegmentAddButton(kinds: List<SegmentKind>, onPick: (SegmentKind) -> Unit) {
	var open by remember { mutableStateOf(false) }
	val main = kinds.first()
	val style = kindStyle(main)
	Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
		Button(
			onClick = { onPick(main) },
			shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50, topEndPercent = 12, bottomEndPercent = 12),
			colors = ButtonDefaults.buttonColors(containerColor = style.container, contentColor = style.content),
		) {
			Icon(painterResource(style.icon), contentDescription = null, modifier = Modifier.size(20.dp))
			Spacer(Modifier.width(6.dp))
			Text(stringResource(R.string.create_add_segment, kindName(main)))
		}
		Box {
			Button(
				onClick = { open = true },
				shape = RoundedCornerShape(topStartPercent = 12, bottomStartPercent = 12, topEndPercent = 50, bottomEndPercent = 50),
				colors = ButtonDefaults.buttonColors(containerColor = style.container, contentColor = style.content),
				contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
			) { Text("▾") }
			DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
				for (k in kinds) {
					val s = kindStyle(k)
					DropdownMenuItem(
						text = { Text(kindName(k)) },
						leadingIcon = {
							Box(Modifier.size(24.dp).clip(CircleShape).background(s.container), contentAlignment = Alignment.Center) {
								Icon(painterResource(s.icon), contentDescription = null, tint = s.content, modifier = Modifier.size(16.dp))
							}
						},
						onClick = { open = false; onPick(k) },
					)
				}
			}
		}
	}
}

/* Start and end of one segment, both on the range grid; an end not after the start is on the next day. */
@Composable
private fun SegmentDialog(kind: SegmentKind, day: LocalDate, initial: SegDraft?, onDone: (SegDraft) -> Unit, onDismiss: () -> Unit) {
	var start by remember { mutableStateOf(initial?.startMin?.let { formatMin(it % 1440) }.orEmpty()) }
	var end by remember { mutableStateOf(initial?.endMin?.let { formatMin(if (it == 1440) 1440 else it % 1440) }.orEmpty()) }
	var grid by remember { mutableStateOf(false) }
	val a = parseHhMm(start)?.takeIf { it < 1440 }
	val b = parseHhMm(end)
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(kindName(kind)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Box(Modifier.weight(1f)) {
						TimeField(
							stringResource(R.string.field_start), start, { start = it }, importance = Importance.REQUIRED,
							onOpen = if (start.isBlank()) ({ grid = true }) else null,
						)
					}
					Box(Modifier.weight(1f)) {
						TimeField(stringResource(R.string.field_end), end, { end = it }, importance = Importance.REQUIRED, endOfDay = true, onOpen = { grid = true })
					}
				}
				Text(stringResource(R.string.segment_hint), style = MaterialTheme.typography.bodySmall)
			}
		},
		confirmButton = {
			TextButton(
				enabled = a != null && b != null,
				onClick = { onDone(SegDraft(kind, a!!, if (b!! <= a) b + 1440 else b)) },
			) { Text(stringResource(R.string.action_ok)) }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
	)
	if (grid)
		TimeGridSheet(
			title = kindName(kind),
			initial = if (a != null && b != null && b > a) a until b else null,
			range = true,
			day = day,
			onDismiss = { grid = false },
		) { from, to ->
			grid = false
			start = formatMin(from)
			end = formatMin(to)
		}
}

/*
 * The management system's fields of a company, for an activity not saved
 * yet (PluginFieldsSection edits a saved one): the plugin's entry fields
 * (docs/PLUGINS.md 8) with their lists from the last sync, the required
 * ones for the visible part of a dialog, the others under "Altre
 * informazioni".
 */
class PluginRow(
	val field: EntryField,
	val label: String,
	val required: Boolean,
	/* the list's choices, null for a text or a switch */
	val options: List<Pair<String?, String>>?,
	val value: JsonElement?,
)

class PluginForm(val name: String, val rows: List<PluginRow>, val json: String?, val set: (String, JsonElement?) -> Unit) {
	val hasRequired get() = rows.any { it.required }
	val hasOptional get() = rows.any { !it.required }
}

private fun jsonObject(s: String?) = s?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

/*
 * The plugin of [companyId] with entry fields, as a form; null without one.
 * [initial] is the JSON already known (a to-do's values), [activity] the
 * to-do when one is being started (the @activity of the descriptors).
 * Called outside lazy lists: in an item the first composition would be empty.
 */
@Composable
fun rememberPluginForm(companyId: Long?, clientId: Long?, initial: String?, activity: TimeEntryEntity? = null): PluginForm? {
	val app = LocalContext.current.applicationContext as ActivityTrackApp
	val c = app.container
	val plugins by app.plugins.plugins.collectAsState()
	val company by produceState<CompanyEntity?>(null, companyId) { value = companyId?.let { c.masterData.company(it) } }
	val clientCode by produceState<String?>(null, clientId) { value = clientId?.let { c.masterData.client(it)?.externalCode } }
	val co = company
	val plugin = co?.connectorId?.let { plugins[it] }?.takeIf { it.entryFields.isNotEmpty() }
	val itemsFlow = remember(co?.id, plugin?.id) {
		if (co != null && plugin != null) c.pluginData.observeAllItems(co.id, plugin.id) else kotlinx.coroutines.flow.flowOf(emptyList())
	}
	val items by itemsFlow.collectAsState(initial = emptyList())
	val values = remember(co?.id, initial) { mutableStateMapOf<String, JsonElement>().also { m -> jsonObject(initial)?.let(m::putAll) } }
	val language = LocalConfiguration.current.locales[0].language
	if (co == null || plugin == null)
		return null

	val fields = HashMap<String, JsonElement>(values)
	val activityJson: JsonElement = activity?.takeIf { it.externalKey != null }?.let {
		JsonObject(mapOf("id" to JsonPrimitive(it.id), "externalKey" to JsonPrimitive(it.externalKey!!), "title" to JsonPrimitive(it.activity.orEmpty())))
	} ?: JsonNull
	val entryJson = JsonObject(mapOf("client" to JsonObject(mapOf("code" to (clientCode?.let(::JsonPrimitive) ?: JsonNull)))))
	fun itemJson(i: it.infraconsulting.activitytrack.data.db.RemoteItemEntity) =
		RemoteItem(i.list, i.id, i.parentId, i.name, i.clientCode, i.kind, i.active, i.order, jsonObject(i.extra) ?: JsonObject(emptyMap())).asJson()
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
			"activity" -> if (r.path.isEmpty()) activityJson else PluginEnv.dig(activityJson, r.path)
			"field" -> r.path.firstOrNull()?.let { fields[it] }?.let { PluginEnv.dig(it, r.path.drop(1)) } ?: JsonNull
			"list" -> if (r.path.getOrNull(1) == "selected") PluginEnv.dig(selected(r.path[0]), r.path.drop(2)) else JsonNull
			else -> null
		}
	}
	for (f in plugin.entryFields)
		if (f.key !in fields)
			f.default?.eval(null, env)?.takeIf { it !is JsonNull }?.let { fields[f.key] = it }

	val rows = ArrayList<PluginRow>()
	for (f in plugin.entryFields) {
		if (truthy(f.hidden.eval(null, env)))
			continue
		val label = f.label[language] ?: f.label["en"] ?: f.key
		val required = truthy(f.required.eval(null, env))
		val options = if (f.type != FieldType.LIST) null else {
			val parent = parentOf(f)
			val filters = f.filter.mapValues { (_, x) -> text(x.eval(null, env))?.ifEmpty { null } }
			val current = fields[f.key]?.let(::text)
			listOf<Pair<String?, String>>(null to "") + items.filter { i ->
				i.list == f.list && (f.parent == null || i.parentId == parent) && (i.active || i.id == current) &&
					filters.all { (prop, want) -> want == null || when (prop) {
						"clientCode" -> i.clientCode.equals(want, ignoreCase = true)
						"kind" -> i.kind == want
						"parentId" -> i.parentId == want
						"id" -> i.id == want
						"name" -> i.name == want
						else -> true
					} }
			}.map { it.id to it.name }
		}
		rows += PluginRow(f, label, required, options, fields[f.key])
	}
	val saved = HashMap<String, JsonElement>()
	for ((k, v) in fields) {
		val f = plugin.entryFields.firstOrNull { it.key == k }
		/* an int is typed as text: kept only when it is one */
		val kept = if (f?.type == FieldType.INT && v is JsonPrimitive && v.isString) v.content.trim().toLongOrNull()?.let(::JsonPrimitive) else v
		if (kept != null && kept !is JsonNull)
			saved[k] = kept
	}
	val json = if (saved.isEmpty()) null else JsonObject(saved).toString()
	return PluginForm(
		plugin.name, rows, json,
		set = { key, value ->
			if (value == null || value is JsonNull) values.remove(key) else values[key] = value
			/* a new parent choice clears the fields that hang on it */
			plugin.entryFields.filter { f -> f.parent?.toString() == "@field.$key" }.forEach { values.remove(it.key) }
		},
	)
}

/* The [required] or the other fields of [form]. */
@Composable
fun PluginRows(form: PluginForm, required: Boolean) {
	val rows = form.rows.filter { it.required == required }
	if (rows.isEmpty())
		return
	Text(form.name, style = MaterialTheme.typography.labelLarge)
	val none = stringResource(R.string.field_none)
	for (r in rows) {
		val importance = if (r.required) Importance.REQUIRED else Importance.OPTIONAL
		val key = r.field.key
		when (r.field.type) {
			FieldType.LIST -> {
				val current = r.value?.let(::text)
				val options = r.options.orEmpty().map { (id, name) -> id to if (id == null) none else name }
				ChoiceField(r.label, options, current?.takeIf { id -> options.any { it.first == id } }, importance) { form.set(key, it?.let(::JsonPrimitive)) }
			}
			FieldType.BOOL -> SwitchRow(r.label, r.value?.let(::truthy) ?: false) { form.set(key, JsonPrimitive(it)) }
			else -> TextInput(
				r.label, r.value?.let(::text).orEmpty(),
				{ t -> form.set(key, if (t.isEmpty()) null else JsonPrimitive(t)) },
				if (r.field.type == FieldType.INT) KeyboardType.Number else KeyboardType.Text, importance = importance,
			)
		}
	}
}
