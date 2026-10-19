/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.InputChip
import androidx.compose.foundation.layout.FlowRow
import it.infraconsulting.activitytrack.domain.tag.Tags
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.ColoredActionButton
import it.infraconsulting.activitytrack.data.db.ExternalRefEntity
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.EntryDetails
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.ExternalRefKind
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentSource
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.AddButton
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.DictationInput
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.clockOf
import it.infraconsulting.activitytrack.ui.common.commandStyle
import it.infraconsulting.activitytrack.ui.common.hmOf
import it.infraconsulting.activitytrack.ui.common.localRange
import it.infraconsulting.activitytrack.ui.common.parseDate
import it.infraconsulting.activitytrack.ui.common.parseDecimal
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import it.infraconsulting.activitytrack.ui.common.reasonText
import it.infraconsulting.activitytrack.ui.common.DateField
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.home.statusText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun EntryScreen(entryId: Long, onBack: () -> Unit) {
	val vm = appViewModel { EntryViewModel(entryId, it.entries, it.masterData, it.todos) }
	val entry by vm.entry.collectAsState()
	ScreenScaffold(title = stringResource(R.string.entry_title), onBack = onBack) { padding ->
		/* the form starts from the first loaded state; later emissions refresh only status and lists */
		entry?.let { EntryForm(it, vm, onBack, Modifier.padding(padding)) }
	}
}

@Composable
private fun EntryForm(initial: TimeEntryEntity, vm: EntryViewModel, onBack: () -> Unit, modifier: Modifier) {
	val e = vm.entry.collectAsState().value ?: initial
	val segments by vm.segments.collectAsState()
	val refs by vm.refs.collectAsState()
	val clients by vm.clients.collectAsState()
	val companies by vm.companies.collectAsState()
	val roles by vm.roles.collectAsState()
	val presets by vm.presets.collectAsState()
	val error by vm.error.collectAsState()
	val none = stringResource(R.string.field_none)
	val zone = ZoneId.of(initial.zoneId)

	var clientId by remember { mutableStateOf(initial.clientId) }
	var roleId by remember { mutableStateOf(initial.roleId) }
	var activity by remember { mutableStateOf(initial.activity.orEmpty()) }
	var locality by remember { mutableStateOf(initial.locality.orEmpty()) }
	var locationKind by remember { mutableStateOf(initial.locationKind) }
	var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
	var adding by remember { mutableStateOf<SegmentKind?>(null) }
	var editingSegment by remember { mutableStateOf<SegmentEntity?>(null) }
	var deletingRef by remember { mutableStateOf<ExternalRefEntity?>(null) }
	var newRef by remember { mutableStateOf("") }

	val companyId = clients.firstOrNull { it.id == clientId }?.companyId ?: initial.companyId
	fun details() = EntryDetails(companyId, clientId, roleId, activity, locality, locationKind, notes)

	Column(modifier = modifier.fillMaxSize()) {
		LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
			item {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(statusText(e.status, e.mode), color = MaterialTheme.colorScheme.primary)
					val coNames = companies.associate { it.id to it.name }
					/* by company in the company order, then in the client order (M16; stable sort) */
					val coIndex = companies.withIndex().associate { (i, c) -> c.id to i }
					SearchableChoiceField(
						stringResource(R.string.field_client),
						listOf<Pair<Long?, String>>(null to none) +
							clients.filter { !it.archived || it.id == clientId }
								.filter { initial.companyId == null || initial.clientId != null || it.companyId == initial.companyId }
								.sortedBy { coIndex[it.companyId] ?: Int.MAX_VALUE }
								.map { it.id to "${coNames[it.companyId].orEmpty()} · ${it.name}" },
						clientId,
						importance = Importance.REQUIRED,
						colorOf = { id -> clients.firstOrNull { it.id == id }?.color },
					) { clientId = it }
					val usable = presets.filter { it.companyId == null || it.companyId == companyId }
					if (usable.isNotEmpty())
						ChoiceField(
							stringResource(R.string.field_preset),
							listOf<Pair<Long?, String>>(null to none) + usable.map { it.id to it.text },
							null,
							importance = Importance.OPTIONAL,
						) { id ->
							usable.firstOrNull { it.id == id }?.let { p ->
								activity = p.text
								if (roleId == null) roleId = p.defaultRoleId
							}
						}
					DictationInput(stringResource(R.string.field_activity), activity, { activity = it }, Importance.REQUIRED)
					ChoiceField(
						stringResource(R.string.field_role),
						listOf<Pair<Long?, String>>(null to none) +
							roles.filter { !it.archived || it.id == roleId }.map { it.id to it.name },
						roleId,
						importance = Importance.IMPORTANT,
					) { roleId = it }
					TextInput(stringResource(R.string.field_locality), locality, { locality = it }, importance = Importance.OPTIONAL)
					ChoiceField(
						stringResource(R.string.field_location_kind),
						listOf<Pair<LocationKind?, String>>(null to none) + LocationKind.entries.map { it to locationLabel(it) },
						locationKind,
						importance = Importance.OPTIONAL,
					) { locationKind = it }
					TextInput(stringResource(R.string.field_notes), notes, { notes = it }, singleLine = false, importance = Importance.OPTIONAL)
				}
			}

			item {
				Column {
					SectionHeader(stringResource(R.string.segments))
					Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						val travel = commandStyle(EntryCommand.TRAVEL)!!
						val pause = commandStyle(EntryCommand.PAUSE)!!
						ColoredActionButton(stringResource(R.string.add_travel), travel.icon, travel.container, { vm.clearError(); adding = SegmentKind.TRAVEL }, travel.content)
						ColoredActionButton(stringResource(R.string.add_pause), pause.icon, pause.container, { vm.clearError(); adding = SegmentKind.PAUSE }, pause.content)
					}
				}
			}
			if (segments.isEmpty())
				item { EmptyText() }
			items(segments, key = { "s${it.id}" }) { s ->
				val end = s.endUtcMs
				val range = clockOf(s.startUtcMs, zone) + " – " + (end?.let { clockOf(it, zone) } ?: "…")
				val os = s.origStartUtcMs
				val oe = s.origEndUtcMs
				val tracked = if (os != null && oe != null) clockOf(os, zone) + "–" + clockOf(oe, zone) else ""
				val extra = listOfNotNull(
					end?.let { hmOf(it - s.startUtcMs) },
					(s.kmDeclared ?: s.kmEstimated)?.let { "%.1f km".format(it) },
					when (s.source) {
						SegmentSource.MANUAL -> stringResource(R.string.segment_manual)
						SegmentSource.CORRECTED -> stringResource(R.string.segment_corrected, tracked)
						SegmentSource.TRACKED -> null
					},
				).joinToString(" · ")
				/* every closed segment can be edited or deleted (backlog 11); the running one cannot */
				ClickableRow("${kindLabel(s.kind)}  $range", extra) { if (end != null) { vm.clearError(); editingSegment = s } }
			}

			item { TagsSection(vm) }
			item { SectionHeader(stringResource(R.string.external_refs)) }
			items(refs, key = { "r${it.id}" }) { r ->
				ClickableRow(r.value, refKindLabel(r.kind)) { deletingRef = r }
			}
			item {
				Row(Modifier.padding(horizontal = 16.dp)) {
					Column(Modifier.weight(1f)) {
						TextInput(stringResource(R.string.external_ref_new), newRef, { newRef = it })
					}
					AddButton { vm.addRef(newRef) { newRef = "" } }
				}
			}
			item { ExpensesSection(initial.id) }
			item { AttachmentsSection(initial.id, zone) }
		}

		/* outside the list: a validation error must stay visible above the buttons */
		error?.let {
			Text(reasonText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
		}
		Row(
			modifier = Modifier.fillMaxWidth().padding(16.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			if (e.status == EntryStatus.TO_COMPLETE || e.status == EntryStatus.CLOSED)
				OutlinedButton(onClick = vm::reopen) { Text(stringResource(R.string.cmd_reopen)) }
			OutlinedButton(onClick = { vm.save(details(), onBack) }) { Text(stringResource(R.string.action_save)) }
			if (e.status == EntryStatus.TO_COMPLETE)
				Button(
					onClick = { vm.complete(details(), onBack) },
					colors = ButtonDefaults.buttonColors(containerColor = commandStyle(EntryCommand.WORK)!!.container, contentColor = Color.White),
				) { Text(stringResource(R.string.cmd_complete)) }
		}
	}

	val emptyPrompt by vm.emptyPrompt.collectAsState()
	emptyPrompt?.let { p ->
		EmptyActivityDialog(
			lastActivity = p.lastActivity,
			onEmpty = { vm.completeEmpty(onBack) },
			onCopy = { vm.completeCopyingLast(onBack) },
			onEdit = vm::dismissEmptyPrompt,
		)
	}

	val todoPrompt by vm.todoPrompt.collectAsState()
	todoPrompt?.let { p ->
		AlertDialog(
			onDismissRequest = { vm.answerTodo(false) },
			title = { Text(stringResource(R.string.todo_done_question)) },
			text = { Text(p.title) },
			confirmButton = { TextButton(onClick = { vm.answerTodo(true) }) { Text(stringResource(R.string.action_yes)) } },
			dismissButton = { TextButton(onClick = { vm.answerTodo(false) }) { Text(stringResource(R.string.todo_later)) } },
		)
	}

	adding?.let { kind ->
		val firstDay = segments.firstOrNull()?.let { Instant.ofEpochMilli(it.startUtcMs).atZone(zone).toLocalDate() }
			?: LocalDate.now(zone)
		ManualSegmentDialog(kind, firstDay, zone, vm) { adding = null }
	}
	editingSegment?.let { s -> SegmentDialog(s, zone, vm) { editingSegment = null } }
	deletingRef?.let { r ->
		ConfirmDialog(stringResource(R.string.confirm_delete, r.value), { deletingRef = null }) {
			deletingRef = null
			vm.removeRef(r.id)
		}
	}
}

/*
 * Edit or delete a closed segment (backlog 11): date, start, end, km of a
 * travel; a tracked one becomes "corrected" and keeps its tracked times.
 */
@Composable
private fun SegmentDialog(s: SegmentEntity, zone: ZoneId, vm: EntryViewModel, onClose: () -> Unit) {
	val error by vm.error.collectAsState()
	val startAt = Instant.ofEpochMilli(s.startUtcMs).atZone(zone)
	var date by remember { mutableStateOf(startAt.toLocalDate().toString()) }
	var start by remember { mutableStateOf(clockOf(s.startUtcMs, zone)) }
	var end by remember { mutableStateOf(s.endUtcMs?.let { clockOf(it, zone) }.orEmpty()) }
	var km by remember { mutableStateOf(s.kmDeclared?.toString().orEmpty()) }
	var deleting by remember { mutableStateOf(false) }
	EditDialog(
		title = stringResource(R.string.segment_edit),
		error = error,
		onDismiss = { vm.clearError(); onClose() },
		onDelete = { deleting = true },
		onConfirm = {
			val d = parseDate(date)
			val a = parseHhMm(start)
			val b = parseHhMm(end)
			if (d == null || a == null || b == null)
				vm.updateSegment(s.id, 1, 0, null, onClose)
			else {
				val (from, to) = localRange(d, a, b, zone)
				vm.updateSegment(s.id, from, to, if (s.kind == SegmentKind.TRAVEL) parseDecimal(km) else null, onClose)
			}
		},
	) {
		DateField(stringResource(R.string.field_date), date, { date = it }, importance = Importance.REQUIRED)
		TimeField(stringResource(R.string.field_start), start, { start = it }, importance = Importance.REQUIRED)
		TimeField(stringResource(R.string.field_end), end, { end = it }, importance = Importance.REQUIRED)
		if (s.kind == SegmentKind.TRAVEL)
			TextInput(stringResource(R.string.field_km), km, { km = it }, KeyboardType.Decimal, importance = Importance.OPTIONAL)
		val os = s.origStartUtcMs
		val oe = s.origEndUtcMs
		if (os != null && oe != null)
			Text(
				stringResource(R.string.segment_corrected, clockOf(os, zone) + "–" + clockOf(oe, zone)),
				style = MaterialTheme.typography.bodySmall,
			)
	}
	if (deleting)
		ConfirmDialog(stringResource(R.string.confirm_delete_segment), { deleting = false }) {
			deleting = false
			vm.deleteSegment(s.id, onClose)
		}
}

/*
 * Tags of the entry (backlog 13): chips with a remove button, a field to
 * add one or more ("#urgente remoto"), suggestions from the tags in use.
 */
@Composable
private fun TagsSection(vm: EntryViewModel) {
	val tags by vm.tags.collectAsState()
	val all by vm.allTags.collectAsState()
	var text by remember { mutableStateOf("") }
	val typed = Tags.normalize(text.substringAfterLast(' '))
	val suggestions = all.filter { it !in tags && (typed == null || it.startsWith(typed)) }.take(8)
	Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		SectionHeader(stringResource(R.string.tags))
		if (tags.isNotEmpty())
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				for (t in tags)
					InputChip(
						selected = false, onClick = { vm.setTags(tags - t) }, label = { Text("#$t") },
						trailingIcon = { Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.action_delete), modifier = Modifier.size(16.dp)) },
					)
			}
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				TextInput(stringResource(R.string.tag_new), text, { text = it }, importance = Importance.OPTIONAL)
			}
			AddButton {
				vm.setTags(tags + Tags.parse(text))
				text = ""
			}
		}
		if (suggestions.isNotEmpty())
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				for (t in suggestions)
					SuggestionChip(onClick = { vm.setTags(tags + t); text = "" }, label = { Text("#$t") })
			}
	}
}

@Composable
private fun ManualSegmentDialog(
	kind: SegmentKind,
	defaultDay: LocalDate,
	zone: ZoneId,
	vm: EntryViewModel,
	onClose: () -> Unit,
) {
	val error by vm.error.collectAsState()
	var date by remember { mutableStateOf(defaultDay.toString()) }
	var start by remember { mutableStateOf("") }
	var end by remember { mutableStateOf("") }
	var km by remember { mutableStateOf("") }
	EditDialog(
		title = stringResource(if (kind == SegmentKind.TRAVEL) R.string.add_travel else R.string.add_pause),
		error = error,
		onDismiss = onClose,
		onConfirm = {
			val d = parseDate(date)
			val s = parseHhMm(start)
			val t = parseHhMm(end)
			if (d == null || s == null || t == null) {
				/* reported by the repository as an invalid segment */
				vm.addSegment(kind, 1, 0, null, onClose)
			} else {
				val (from, to) = localRange(d, s, t, zone)
				vm.addSegment(kind, from, to, if (kind == SegmentKind.TRAVEL) parseDecimal(km) else null, onClose)
			}
		},
	) {
		DateField(stringResource(R.string.field_date), date, { date = it })
		TimeField(stringResource(R.string.field_start), start, { start = it })
		TimeField(stringResource(R.string.field_end), end, { end = it })
		if (kind == SegmentKind.TRAVEL)
			TextInput(stringResource(R.string.field_km), km, { km = it }, KeyboardType.Decimal)
		Text(stringResource(R.string.segment_hint), style = MaterialTheme.typography.bodySmall)
	}
}

/* "No activity description": leave it empty, copy the last one on this client, or go back. */
@Composable
private fun EmptyActivityDialog(lastActivity: String?, onEmpty: () -> Unit, onCopy: () -> Unit, onEdit: () -> Unit) {
	AlertDialog(
		onDismissRequest = onEdit,
		title = { Text(stringResource(R.string.empty_activity_title)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(stringResource(R.string.empty_activity_text))
				TextButton(onClick = onEmpty) { Text(stringResource(R.string.empty_activity_leave)) }
				if (lastActivity != null)
					TextButton(onClick = onCopy) {
						Text(stringResource(R.string.empty_activity_copy, lastActivity), maxLines = 2, overflow = TextOverflow.Ellipsis)
					}
				TextButton(onClick = onEdit) { Text(stringResource(R.string.empty_activity_edit)) }
			}
		},
		confirmButton = {},
	)
}

@Composable
private fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
	AlertDialog(
		onDismissRequest = onDismiss,
		text = { Text(text) },
		confirmButton = {
			TextButton(onClick = onConfirm) {
				Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
	)
}

@Composable
private fun kindLabel(k: SegmentKind) = stringResource(
	when (k) {
		SegmentKind.WORK -> R.string.cmd_work
		SegmentKind.TRAVEL -> R.string.cmd_travel
		SegmentKind.PAUSE -> R.string.cmd_pause
	},
)

@Composable
private fun refKindLabel(k: ExternalRefKind) = stringResource(
	when (k) {
		ExternalRefKind.TICKET -> R.string.ref_ticket
		ExternalRefKind.URL -> R.string.ref_url
		ExternalRefKind.UUID -> R.string.ref_uuid
		ExternalRefKind.OTHER -> R.string.ref_other
	},
)

@Composable
fun locationLabel(k: LocationKind) = stringResource(
	when (k) {
		LocationKind.COMPANY -> R.string.loc_company
		LocationKind.CLIENT -> R.string.loc_client
		LocationKind.HOME -> R.string.loc_home
		LocationKind.OTHER -> R.string.loc_other
	},
)
