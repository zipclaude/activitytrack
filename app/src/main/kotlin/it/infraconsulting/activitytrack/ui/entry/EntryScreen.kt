/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.ExternalRefEntity
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.EntryDetails
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.Priority
import it.infraconsulting.activitytrack.domain.model.ExternalRefKind
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentSource
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.domain.policy.Field
import it.infraconsulting.activitytrack.domain.tag.Tags
import it.infraconsulting.activitytrack.location.CurrentPosition
import it.infraconsulting.activitytrack.ui.LocalCompact
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.AddButton
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.DateField
import it.infraconsulting.activitytrack.ui.common.DictationInput
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.INDIGO
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.ui.common.MAGENTA
import it.infraconsulting.activitytrack.ui.common.MiniActionButton
import it.infraconsulting.activitytrack.ui.common.PURPLE
import it.infraconsulting.activitytrack.ui.common.PolicyField
import it.infraconsulting.activitytrack.ui.common.PriorityField
import it.infraconsulting.activitytrack.ui.common.QuickClientDialog
import it.infraconsulting.activitytrack.ui.common.QuickPresetDialog
import it.infraconsulting.activitytrack.ui.common.QuickRoleDialog
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.TEAL
import it.infraconsulting.activitytrack.ui.common.TagDialog
import it.infraconsulting.activitytrack.ui.common.TagLabel
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.TimeField
import it.infraconsulting.activitytrack.ui.common.WithAddButton
import it.infraconsulting.activitytrack.ui.common.canCreateMasterData
import it.infraconsulting.activitytrack.ui.common.clockOf
import it.infraconsulting.activitytrack.ui.common.enabled
import it.infraconsulting.activitytrack.ui.common.fieldImportance
import it.infraconsulting.activitytrack.ui.common.fieldLabel
import it.infraconsulting.activitytrack.ui.common.fieldShown
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.hmOf
import it.infraconsulting.activitytrack.ui.common.parseDate
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import it.infraconsulting.activitytrack.ui.common.reasonText
import it.infraconsulting.activitytrack.ui.common.tagColor
import it.infraconsulting.activitytrack.ui.masterdata.ContactsViewModel
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * The detail dialog of an activity, for every state (ACTIVITY-MODEL.md 8,
 * owner 2026-11-11), opened from the Home and the to-do list: the state
 * and the commands it allows on top, then the fields in the order of the
 * creation dialogs, Annulla and Salva at the bottom.
 */
@Composable
fun EntryScreen(entryId: Long, onBack: () -> Unit, onOpenTrack: (Long) -> Unit = {}) {
	val appContext = LocalContext.current.applicationContext
	val vm = appViewModel { c ->
		/* the locality proposal needs the position, so the location feature (8) */
		val prefill = if (c.policies.current.value.enabled(Feature.LOCATION))
			LocalityPrefill(
				c.masterData, c.locations,
				municipality = { p -> c.settings.current().nominatim.takeIf { it.enabled }?.let { n -> c.nominatim.locality(n.endpoint, p) } },
				currentPosition = { CurrentPosition.get(appContext) },
			)
		else null
		EntryViewModel(
			entryId, c.entries, c.masterData, c.settings.settings.map { s -> s.editableTimes }, c.settings.settings.map { s -> s.tagsFreeText },
			{ c.policies.current.value }, prefill,
		)
	}
	val entry by vm.entry.collectAsState()
	ScreenScaffold(
		title = stringResource(R.string.entry_title),
		onBack = onBack,
		actions = {
			/* the route of this activity (issue #155): its map or export, as the policy allows */
			val policy = LocalPolicy.current
			val route = entry?.kind == ActivityKind.ACTIVITY && policy.enabled(Feature.LOCATION) &&
				(policy.enabled(Feature.TRACK_MAP) || (policy.enabled(Feature.TRACK_EXPORT) && policy.enabled(Feature.EXPORT)))
			if (route) {
				var menu by remember { mutableStateOf(false) }
				val menuLabel = stringResource(R.string.nav_more)
				Box {
					IconButton(onClick = { menu = true }) {
						Text("⋮", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = menuLabel })
					}
					DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
						DropdownMenuItem(text = { Text(stringResource(R.string.track_entry)) }, onClick = { menu = false; onOpenTrack(entryId) })
					}
				}
			}
		},
	) { padding ->
		/* the form starts from the first loaded state; later emissions refresh state, commands and lists */
		entry?.let { EntryForm(it, vm, onBack, Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) }
	}
}

@Composable
private fun EntryForm(initial: TimeEntryEntity, vm: EntryViewModel, onBack: () -> Unit, modifier: Modifier) {
	val e = vm.entry.collectAsState().value ?: initial
	val segments by vm.segments.collectAsState()
	/* collected here: a WhileSubscribed flow nobody collects stays at its initial value */
	val editableTimes by vm.editableTimes.collectAsState()
	val refs by vm.refs.collectAsState()
	val clients by vm.clients.collectAsState()
	val companies by vm.companies.collectAsState()
	val roles by vm.roles.collectAsState()
	val presets by vm.presets.collectAsState()
	val error by vm.error.collectAsState()
	val proposed by vm.proposedLocality.collectAsState()
	val none = stringResource(R.string.field_none)
	val zone = ZoneId.of(initial.zoneId)
	val absence = initial.kind == ActivityKind.ABSENCE
	/* follows the state: a TODO started here stops showing its plan */
	val todo = !absence && e.status == ActivityStatus.TODO

	var companyId by remember { mutableStateOf(initial.companyId) }
	var clientId by remember { mutableStateOf(initial.clientId) }
	var roleId by remember { mutableStateOf(initial.roleId) }
	var activity by remember { mutableStateOf(initial.activity.orEmpty()) }
	/* the client's person the work was for (issue #44) */
	var contactId by remember { mutableStateOf(initial.contactId) }
	var locality by remember { mutableStateOf(initial.locality.orEmpty()) }
	var locationKind by remember { mutableStateOf(initial.locationKind) }
	var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
	/* a TODO activity's plan, deadline and priority */
	var planDate by remember { mutableStateOf(initial.planEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
	var planTime by remember { mutableStateOf(initial.planStartMin?.let(::formatMin).orEmpty()) }
	var duration by remember { mutableStateOf(initial.durationMin?.toString().orEmpty()) }
	var dueDate by remember { mutableStateOf(initial.dueEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
	var dueTime by remember { mutableStateOf(initial.dueTimeMin?.let(::formatMin).orEmpty()) }
	var priority by remember { mutableStateOf(Priority.of(initial.priority)) }
	var absenceKind by remember { mutableStateOf(initial.absenceKind ?: AbsenceKind.ABSENCE) }
	/* quick add of master data (issue #37) */
	var addClient by remember { mutableStateOf(false) }
	var addPreset by remember { mutableStateOf(false) }
	var addRole by remember { mutableStateOf(false) }
	var adding by remember { mutableStateOf<SegmentKind?>(null) }
	var editingSegment by remember { mutableStateOf<SegmentEntity?>(null) }
	var editingNote by remember { mutableStateOf<SegmentEntity?>(null) }
	/* the "add" row asks the sections below (issue #37) */
	var addRequest by remember { mutableStateOf<AddRequest?>(null) }
	var deletingRef by remember { mutableStateOf<ExternalRefEntity?>(null) }
	var newRef by remember { mutableStateOf("") }

	/* the place proposed (site, else municipality) while the field is still empty */
	LaunchedEffect(proposed) {
		proposed?.let { if (locality.isBlank()) locality = it }
	}

	val clientCompany = clients.firstOrNull { it.id == clientId }?.companyId
	fun details() = EntryDetails(clientCompany ?: companyId, clientId, roleId, activity, locality, locationKind, notes, contactId)
	fun save() {
		vm.clearError()
		when {
			absence -> vm.saveAbsence(companyId, absenceKind, notes, onBack)
			todo -> {
				val pd = planDate.takeIf { it.isNotBlank() }?.let { parseDate(it) ?: return vm.invalid(Reason.INVALID_DATE) }
				val pt = planTime.takeIf { it.isNotBlank() }?.let { parseHhMm(it)?.takeIf { m -> m < 1440 } ?: return vm.invalid(Reason.INVALID_TIME) }
				val du = duration.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return vm.invalid(Reason.INVALID_TIME_RANGE) }
				val dd = dueDate.takeIf { it.isNotBlank() }?.let { parseDate(it) ?: return vm.invalid(Reason.INVALID_DATE) }
				val dt = dueTime.takeIf { it.isNotBlank() }?.let { parseHhMm(it)?.takeIf { m -> m < 1440 } ?: return vm.invalid(Reason.INVALID_TIME) }
				vm.saveTodo(details(), TodoFields(pd?.toEpochDay(), pt, du, dd?.toEpochDay(), dt, priority.value), onBack)
			}
			else -> vm.save(details(), onBack)
		}
	}

	val compact = LocalCompact.current
	/* read here: the lazy list's builder is not composable (issue #48) */
	val policy = LocalPolicy.current
	val travelOn = enabled(Feature.TRAVEL)
	val entryPlugin = if (absence) null else rememberEntryPlugin(e)
	val listState = rememberLazyListState()
	val scope = rememberCoroutineScope()
	val actions = detailActions(e, segments, vm)
	val pad = if (compact) 10.dp else 16.dp
	val gap = if (compact) 4.dp else 8.dp
	/* the kinds a segment typed by hand may take: work only once the activity is no longer running */
	val kinds = when {
		absence -> listOf(SegmentKind.ABSENCE)
		e.status == ActivityStatus.RUNNING -> listOf(SegmentKind.TRAVEL, SegmentKind.PAUSE)
		else -> listOf(SegmentKind.WORK, SegmentKind.TRAVEL, SegmentKind.PAUSE)
	}.filter { it != SegmentKind.TRAVEL || travelOn }
	Column(modifier = modifier.fillMaxSize()) {
		LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
			item(key = "header") {
				Box(Modifier.padding(start = pad, end = pad, top = pad, bottom = gap)) {
					DetailHeader(e, segments, editableTimes, onTimes = { scope.launch { listState.animateScrollToItem(2) } }, actions)
				}
			}
			item(key = "fields") {
				Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(gap)) {
					if (absence) {
						ChoiceField(
							stringResource(R.string.field_kind),
							AbsenceKind.entries.map { it to absenceLabel(it) },
							absenceKind,
							importance = Importance.REQUIRED,
						) { absenceKind = it }
						ChoiceField(
							stringResource(R.string.field_company),
							listOf<Pair<Long?, String>>(null to stringResource(R.string.todo_filter_all)) +
								companies.filter { !it.archived || it.id == companyId }.map { it.id to it.name },
							companyId,
							importance = Importance.OPTIONAL,
						) { companyId = it }
					} else
						ActivityFields(
							initial, companyId, { companyId = it }, clientId, { clientId = it }, contactId, { contactId = it },
							activity, { activity = it }, roleId, { roleId = it },
							companies, clients, roles, presets, none, policy,
							onAddClient = { addClient = true }, onAddPreset = { addPreset = true }, onAddRole = { addRole = true },
						)
					if (todo) {
						DateField(stringResource(R.string.todo_field_plan_date), planDate, { planDate = it }, clearable = true)
						TimeField(stringResource(R.string.todo_plan_time_optional), planTime, { planTime = it }, clearable = true)
						if (planTime.isNotBlank())
							TextInput(stringResource(R.string.todo_field_duration), duration, { duration = it }, KeyboardType.Number, importance = Importance.OPTIONAL)
						DateField(stringResource(R.string.todo_field_due), dueDate, { dueDate = it }, clearable = true)
						if (dueDate.isNotBlank())
							TimeField(stringResource(R.string.todo_field_time), dueTime, { dueTime = it }, clearable = true)
						PriorityField(priority) { priority = it }
					}
				}
			}

			/* segments ("Tratti"; the days of an absence), with the split button that adds one */
			item(key = "segments") {
				SectionHeader(stringResource(if (absence) R.string.detail_days else R.string.segments))
				if (e.deletedUtcMs == null)
					Box(Modifier.padding(horizontal = pad)) {
						SegmentSplitButton(kinds, enabled = absence || kinds.isNotEmpty()) { k -> vm.clearError(); adding = k }
					}
			}
			if (segments.isEmpty())
				item { EmptyText() }
			items(segments.sortedBy { it.startUtcMs }, key = { "s${it.id}" }) { s ->
				SegmentRow(s, zone) {
					/*
					 * segments can be edited or deleted with editable times on
					 * (backlog 11, M17), the running one too, its start only
					 * (issue #57); a planned one and an absence day always (they
					 * are declared, not recorded); the description of a pause
					 * always (issue #28)
					 */
					vm.clearError()
					when {
						s.planned || s.kind == SegmentKind.ABSENCE || editableTimes -> editingSegment = s
						s.kind == SegmentKind.PAUSE -> editingNote = s
					}
				}
			}

			if (!absence) {
				/* expense, photo, voice note, file, material: one row with the sections they fill (issue #94) */
				val choices = listOfNotNull(
					AddChoice(AddRequest.EXPENSE, R.string.short_expense, R.drawable.ic_euro, MAGENTA).takeIf { policy.enabled(Feature.EXPENSES) },
					AddChoice(AddRequest.PHOTO, R.string.short_photo, R.drawable.ic_camera, PURPLE).takeIf { policy.enabled(Feature.ATTACHMENTS) },
					AddChoice(AddRequest.VOICE, R.string.short_voice, R.drawable.ic_mic, TEAL).takeIf { policy.enabled(Feature.VOICE_NOTES) },
					AddChoice(AddRequest.FILE, R.string.short_file, R.drawable.ic_attach, INDIGO).takeIf { policy.enabled(Feature.ATTACHMENTS) },
					AddChoice(AddRequest.MATERIAL, R.string.short_material, R.drawable.ic_menu_inventory, TEAL_DARK).takeIf { policy.enabled(Feature.MATERIALS) },
				)
				if (choices.isNotEmpty())
					item(key = "add") {
						SectionHeader(stringResource(R.string.entry_add_section))
						Row(Modifier.padding(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							for ((req, label, icon, color) in choices)
								MiniActionButton(stringResource(label), icon, color, { addRequest = req }, Modifier.weight(1f))
						}
					}
				if (policy.enabled(Feature.EXPENSES))
					item(key = "expenses") { ExpensesSection(initial.id) }
				if (policy.enabled(Feature.MATERIALS))
					item(key = "materials") { MaterialsSection(initial.id) }
				if (policy.enabled(Feature.ATTACHMENTS) || policy.enabled(Feature.VOICE_NOTES))
					item(key = "attachments") { AttachmentsSection(initial.id, zone) }

				/* place: the locality (pre-filled from a site or the municipality) and where */
				item(key = "place") {
					Column(Modifier.padding(horizontal = pad, vertical = gap), verticalArrangement = Arrangement.spacedBy(gap)) {
						PolicyField(Field.LOCALITY) {
							TextInput(fieldLabel(Field.LOCALITY, R.string.field_locality), locality, { locality = it }, importance = fieldImportance(Field.LOCALITY, Importance.OPTIONAL))
						}
						PolicyField(Field.PLACE) {
							ChoiceField(
								fieldLabel(Field.PLACE, R.string.field_location_kind),
								listOf<Pair<LocationKind?, String>>(null to none) + LocationKind.entries.map { it to locationLabel(it) },
								locationKind,
								importance = fieldImportance(Field.PLACE, Importance.OPTIONAL),
							) { locationKind = it }
						}
					}
				}
			}
			item(key = "notes") {
				Column(Modifier.padding(horizontal = pad, vertical = gap)) {
					if (absence)
						TextInput(stringResource(R.string.field_notes), notes, { notes = it }, singleLine = false, importance = Importance.OPTIONAL)
					else
						PolicyField(Field.NOTES) {
							TextInput(fieldLabel(Field.NOTES, R.string.field_notes), notes, { notes = it }, singleLine = false, importance = fieldImportance(Field.NOTES, Importance.OPTIONAL))
						}
				}
			}
			if (!absence) {
				if (policy.field(Field.TAGS).visible)
					item(key = "tags") { TagsSection(vm) }
				if (policy.field(Field.REFS).visible) {
					item(key = "refsHeader") { SectionHeader(fieldLabel(Field.REFS, R.string.external_refs)) }
					items(refs, key = { "r${it.id}" }) { r ->
						ClickableRow(r.value, refKindLabel(r.kind)) { if (policy.field(Field.REFS).enabled) deletingRef = r }
					}
					if (policy.field(Field.REFS).enabled)
						item(key = "refsNew") {
							Row(Modifier.padding(horizontal = 16.dp)) {
								Column(Modifier.weight(1f)) {
									TextInput(stringResource(R.string.external_ref_new), newRef, { newRef = it })
								}
								AddButton { vm.addRef(newRef) { newRef = "" } }
							}
						}
				}
				/* the fields of the company's management system (issue #48) */
				entryPlugin?.let { (co, plugin) -> item(key = "plugin") { PluginFieldsSection(e, co, plugin) } }
			}
		}

		/* outside the list: a validation error must stay visible above the buttons */
		error?.let {
			Text(reasonText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
		}
		Row(
			modifier = Modifier.fillMaxWidth().padding(16.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.action_cancel)) }
			Button(onClick = ::save, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.action_save)) }
		}
	}

	CommandDialogs(vm, zone)
	adding?.let { kind ->
		ManualSegmentDialog(kind, kinds.ifEmpty { listOf(kind) }, todo, defaultSegmentDay(e, segments, zone), zone, vm) { adding = null }
	}
	editingSegment?.let { s -> SegmentDialog(s, zone, vm) { editingSegment = null } }
	/* a segment saved over others: which ones, then confirm (issue #40) */
	val overlap by vm.overlap.collectAsState()
	overlap?.let { p ->
		AlertDialog(
			onDismissRequest = vm::cancelOverlap,
			title = { Text(stringResource(R.string.overlap_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(stringResource(R.string.overlap_text))
					for (c in p.conflicts)
						Text("• ${kindLabel(c.kind)}  ${clockOf(c.startUtcMs, zone)} – ${c.endUtcMs?.let { clockOf(it, zone) } ?: "…"}")
					Text(stringResource(R.string.overlap_hint), style = MaterialTheme.typography.bodySmall)
				}
			},
			confirmButton = { TextButton(onClick = vm::confirmOverlap) { Text(stringResource(R.string.action_confirm)) } },
			dismissButton = { TextButton(onClick = vm::cancelOverlap) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	val deletion by vm.deletion.collectAsState()
	deletion?.let { d ->
		AlertDialog(
			onDismissRequest = vm::cancelDelete,
			title = { Text(stringResource(R.string.entry_delete_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(stringResource(R.string.detail_delete_text))
					if (d.sent)
						Text(stringResource(R.string.entry_delete_sent))
				}
			},
			confirmButton = {
				TextButton(onClick = { vm.deleteEntry(onBack) }) {
					Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = { TextButton(onClick = vm::cancelDelete) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	/* outside the lazy list: always composed, whatever is scrolled into view */
	ExpenseAdder(initial.id, addRequest) { addRequest = null }
	MaterialAdder(initial.id, addRequest) { addRequest = null }
	AttachmentAdder(initial.id, addRequest) { addRequest = null }
	if (addClient)
		QuickClientDialog(clientCompany ?: companyId, onDone = { client, _ -> addClient = false; clientId = client }, onDismiss = { addClient = false })
	if (addPreset)
		QuickPresetDialog(activity, clientCompany ?: companyId, onDone = { addPreset = false; activity = it }, onDismiss = { addPreset = false })
	if (addRole)
		QuickRoleDialog(onDone = { addRole = false; roleId = it }, onDismiss = { addRole = false })
	editingNote?.let { s ->
		var note by remember(s.id) { mutableStateOf(s.note.orEmpty()) }
		EditDialog(
			title = stringResource(R.string.segment_note),
			error = error,
			onDismiss = { vm.clearError(); editingNote = null },
			onConfirm = { vm.setSegmentNote(s.id, note) { editingNote = null } },
		) {
			TextInput(stringResource(R.string.segment_note), note, { note = it }, importance = Importance.OPTIONAL)
		}
	}
	deletingRef?.let { r ->
		ConfirmDialog(stringResource(R.string.confirm_delete, r.value), { deletingRef = null }) {
			deletingRef = null
			vm.removeRef(r.id)
		}
	}
}

/*
 * Azienda, Cliente (and its contact), Descrizione (with the quick pick),
 * Ruolo: the order of the creation dialogs. A company narrows the clients;
 * a client sets its company.
 */
@Composable
private fun ActivityFields(
	initial: TimeEntryEntity,
	companyId: Long?,
	setCompany: (Long?) -> Unit,
	clientId: Long?,
	setClient: (Long?) -> Unit,
	contactId: Long?,
	setContact: (Long?) -> Unit,
	activity: String,
	setActivity: (String) -> Unit,
	roleId: Long?,
	setRole: (Long?) -> Unit,
	companies: List<it.infraconsulting.activitytrack.data.db.CompanyEntity>,
	clients: List<it.infraconsulting.activitytrack.data.db.ClientEntity>,
	roles: List<it.infraconsulting.activitytrack.data.db.RoleEntity>,
	presets: List<it.infraconsulting.activitytrack.data.db.ActivityPresetEntity>,
	none: String,
	policy: it.infraconsulting.activitytrack.domain.policy.Policy,
	onAddClient: () -> Unit,
	onAddPreset: () -> Unit,
	onAddRole: () -> Unit,
) {
	val coNames = companies.associate { it.id to it.name }
	/* by company in the company order, then in the client order (M16; stable sort) */
	val coIndex = companies.withIndex().associate { (i, c) -> c.id to i }
	val company = clients.firstOrNull { it.id == clientId }?.companyId ?: companyId
	if (fieldShown(Field.COMPANY))
		PolicyField(Field.COMPANY) {
			SearchableChoiceField(
				fieldLabel(Field.COMPANY, R.string.field_company),
				listOf<Pair<Long?, String>>(null to none) + companies.filter { !it.archived || it.id == company }.map { it.id to it.name },
				company,
				importance = fieldImportance(Field.COMPANY, Importance.IMPORTANT),
				colorOf = { id -> companies.firstOrNull { it.id == id }?.color },
			) { id ->
				setCompany(id)
				if (id != null && clients.firstOrNull { it.id == clientId }?.companyId != id)
					setClient(null)
			}
		}
	if (fieldShown(Field.CLIENT)) WithAddButton(stringResource(R.string.quick_client), onAddClient, masterData = true) { PolicyField(Field.CLIENT) {
		SearchableChoiceField(
			fieldLabel(Field.CLIENT, R.string.field_client),
			listOf<Pair<Long?, String>>(null to none) +
				clients.filter { !it.archived || it.id == clientId }
					.filter { company == null || it.companyId == company || it.id == initial.clientId && clientId == initial.clientId }
					.sortedBy { coIndex[it.companyId] ?: Int.MAX_VALUE }
					.map { it.id to if (policy.field(Field.COMPANY).visible && company == null) "${coNames[it.companyId].orEmpty()} · ${it.name}" else it.name },
			clientId,
			importance = Importance.REQUIRED,
			colorOf = { id -> clients.firstOrNull { it.id == id }?.color },
		) { id ->
			setClient(id)
			clients.firstOrNull { it.id == id }?.let { setCompany(it.companyId) }
		}
	} }
	val people by appViewModel(key = "contacts") { c -> ContactsViewModel(c.calls) }.contacts.collectAsState()
	val ofClient = people.filter { it.contact.clientId == clientId }
	if ((ofClient.isNotEmpty() || contactId != null) && fieldShown(Field.CONTACT))
		PolicyField(Field.CONTACT) {
			ChoiceField(
				fieldLabel(Field.CONTACT, R.string.field_contact),
				listOf<Pair<Long?, String>>(null to none) + ofClient.map { it.contact.id to it.contact.name },
				contactId.takeIf { id -> ofClient.any { it.contact.id == id } },
				importance = fieldImportance(Field.CONTACT, Importance.OPTIONAL),
			) { setContact(it) }
		}
	val usable = presets.filter { it.companyId == null || it.companyId == company }
	if (fieldShown(Field.QUICK_PICK)) WithAddButton(stringResource(R.string.quick_preset), onAddPreset) { PolicyField(Field.QUICK_PICK) {
		ChoiceField(
			fieldLabel(Field.QUICK_PICK, R.string.field_preset),
			listOf<Pair<Long?, String>>(null to none) + usable.map { it.id to it.text },
			null,
			importance = fieldImportance(Field.QUICK_PICK, Importance.OPTIONAL),
		) { id ->
			usable.firstOrNull { it.id == id }?.let { p ->
				setActivity(p.text)
				if (roleId == null) setRole(p.defaultRoleId)
			}
		}
	} }
	PolicyField(Field.DESCRIPTION) {
		DictationInput(fieldLabel(Field.DESCRIPTION, R.string.field_activity), activity, setActivity, Importance.REQUIRED)
	}
	if (fieldShown(Field.ROLE)) WithAddButton(stringResource(R.string.quick_role), onAddRole) { PolicyField(Field.ROLE) {
		ChoiceField(
			fieldLabel(Field.ROLE, R.string.field_role),
			listOf<Pair<Long?, String>>(null to none) + roles.filter { !it.archived || it.id == roleId }.map { it.id to it.name },
			roleId,
			importance = fieldImportance(Field.ROLE, Importance.IMPORTANT),
		) { setRole(it) }
	} }
}

/*
 * "+ Lavoro · Viaggio · Pausa" as one split button (8): each part opens the
 * segment dialog with its kind preset; an absence adds a day.
 */
@Composable
private fun SegmentSplitButton(kinds: List<SegmentKind>, enabled: Boolean, onAdd: (SegmentKind) -> Unit) {
	if (!enabled || kinds.isEmpty())
		return
	val compact = LocalCompact.current
	Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
		for (k in kinds) {
			val style = segmentStyle(k)
			Button(
				onClick = { onAdd(k) },
				modifier = Modifier.weight(1f).height(if (compact) 40.dp else 44.dp),
				shape = androidx.compose.ui.graphics.RectangleShape,
				colors = ButtonDefaults.buttonColors(containerColor = style.container, contentColor = style.content),
				contentPadding = PaddingValues(horizontal = 6.dp),
			) {
				Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
				Spacer(Modifier.width(4.dp))
				Text(if (k == SegmentKind.ABSENCE) stringResource(R.string.detail_add_day) else kindLabel(k), maxLines = 1)
			}
		}
	}
}

/* A segment: kind, times, length, km, manual, note; a planned one with a dashed badge (1.2). */
@Composable
private fun SegmentRow(s: SegmentEntity, zone: ZoneId, onClick: () -> Unit) {
	val end = s.endUtcMs
	val start = java.time.Instant.ofEpochMilli(s.startUtcMs).atZone(zone).toLocalDate()
	val day = if (start == LocalDate.now(zone)) "" else start.format(java.time.format.DateTimeFormatter.ofPattern("d/MM "))
	val range = day + clockOf(s.startUtcMs, zone) + " – " + (end?.let { clockOf(it, zone) } ?: "…")
	val extra = listOfNotNull(
		end?.let { hmOf(it - s.startUtcMs) },
		(s.kmDeclared ?: s.kmEstimated)?.let { "%.1f km".format(it) },
		if (s.planned) stringResource(R.string.detail_planned) else null,
		if (!s.planned && s.source == SegmentSource.MANUAL && s.kind != SegmentKind.ABSENCE) stringResource(R.string.segment_manual) else null,
		s.note,
	).joinToString(" · ")
	ClickableRow("${kindLabel(s.kind)}  $range", extra, leading = { SegmentBadge(s.kind, s.planned) }, onClick = onClick)
}

/*
 * Tags of the activity (backlog 13): chips with a remove button, a field to
 * add one or more ("#urgente remoto"), suggestions from the master data's
 * tags first, then the free ones in use (issue #133). Without free tags
 * (setting, policy) only the master data's are taken; a free tag already
 * on the activity can be kept in the master data with a colour.
 */
@Composable
private fun TagsSection(vm: EntryViewModel) {
	val tags by vm.tags.collectAsState()
	val all by vm.allTags.collectAsState()
	val master by vm.masterTags.collectAsState()
	val free by vm.tagsFreeText.collectAsState()
	val error by vm.error.collectAsState()
	var text by remember { mutableStateOf("") }
	var refused by remember { mutableStateOf(emptyList<String>()) }
	var keeping by remember { mutableStateOf<String?>(null) }
	val known = master.filter { !it.archived }.map { it.name }
	val names = master.map { it.name }.toSet()
	val typed = Tags.normalize(text.substringAfterLast(' '))
	val pool = if (free) known + all.filter { it !in names } else known
	val suggestions = pool.filter { it !in tags && (typed == null || it.startsWith(typed)) }.take(8)
	val canKeep = canCreateMasterData()
	fun add(new: List<String>) {
		val (ok, no) = new.partition { free || it in known }
		refused = no
		if (ok.isNotEmpty()) vm.setTags(tags + ok)
	}
	/* the header carries its own padding, like the other sections */
	SectionHeader(stringResource(R.string.tags))
	Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		if (tags.isNotEmpty())
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				for (t in tags)
					Row(verticalAlignment = Alignment.CenterVertically) {
						/* a free tag: a tap keeps it in the master data */
						val keep = canKeep && t !in names
						TagLabel(t, if (keep) Modifier.clickable { vm.clearError(); keeping = t } else Modifier)
						IconButton(onClick = { vm.setTags(tags - t) }, modifier = Modifier.size(32.dp)) {
							Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.action_delete), modifier = Modifier.size(16.dp))
						}
					}
			}
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				TextInput(stringResource(if (free) R.string.tag_new else R.string.tag_pick), text, { text = it; refused = emptyList() }, importance = Importance.OPTIONAL)
			}
			AddButton {
				add(Tags.parse(text))
				text = ""
			}
		}
		if (refused.isNotEmpty())
			Text(stringResource(R.string.tag_only_master, Tags.show(refused)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
		if (suggestions.isNotEmpty())
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				for (t in suggestions)
					SuggestionChip(
						onClick = { add(listOf(t)); text = "" },
						label = { Text("#$t") },
						icon = { Box(Modifier.size(10.dp).clip(CircleShape).background(tagColor(t))) },
					)
			}
	}
	keeping?.let { t ->
		TagDialog(stringResource(R.string.tag_keep), t, null, false, error, { keeping = null }, null) { n, c, a ->
			vm.saveTag(it.infraconsulting.activitytrack.data.db.TagEntity(name = n, color = c, archived = a)) { keeping = null }
		}
	}
}

@Composable
private fun absenceLabel(k: AbsenceKind) = stringResource(
	when (k) {
		AbsenceKind.ABSENCE -> R.string.detail_absence_absence
		AbsenceKind.HOLIDAY -> R.string.detail_absence_holiday
		AbsenceKind.LEAVE -> R.string.detail_absence_leave
		AbsenceKind.SICK -> R.string.detail_absence_sick
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

/* What the "add" row asks the expense and attachment sections for. */
enum class AddRequest { EXPENSE, PHOTO, VOICE, FILE, MATERIAL }

/* the material button: none of the command or other add colours */
private val TEAL_DARK = androidx.compose.ui.graphics.Color(0xFF5D4037)

private data class AddChoice(val request: AddRequest, val label: Int, val icon: Int, val color: androidx.compose.ui.graphics.Color)
