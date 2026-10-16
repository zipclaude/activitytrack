/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.ActivityPresetEntity
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.ClientSiteEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.CompanySiteEntity
import it.infraconsulting.activitytrack.data.db.PlaceEntity
import it.infraconsulting.activitytrack.data.db.PlaceKind
import it.infraconsulting.activitytrack.data.db.RoleEntity
import it.infraconsulting.activitytrack.data.db.WorkScheduleEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.AddButton
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
private fun masterDataViewModel() = appViewModel { MasterDataViewModel(it.masterData) }

/* Asks before running [action]; returns the trigger. */
@Composable
private fun confirmDelete(name: String, action: () -> Unit): () -> Unit {
	var ask by remember { mutableStateOf(false) }
	if (ask)
		AlertDialog(
			onDismissRequest = { ask = false },
			text = { Text(stringResource(R.string.confirm_delete, name)) },
			confirmButton = {
				TextButton(onClick = { ask = false; action() }) {
					Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = { TextButton(onClick = { ask = false }) { Text(stringResource(R.string.action_cancel)) } },
		)
	return { ask = true }
}

@Composable
private fun archivedSuffix(archived: Boolean) = if (archived) stringResource(R.string.archived_suffix) else null

@Composable
fun MasterDataScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
	ScreenScaffold(title = stringResource(R.string.md_title), onBack = onBack) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			val sections = listOf(
				Screen.Companies to R.string.md_companies,
				Screen.Clients to R.string.md_clients,
				Screen.Roles to R.string.md_roles,
				Screen.Presets to R.string.md_presets,
				Screen.Places to R.string.md_places,
				Screen.Schedule to R.string.md_schedule,
			)
			items(sections) { (screen, label) -> ClickableRow(stringResource(label)) { onOpen(screen) } }
		}
	}
}

/* Name + archived dialog, shared by companies and roles. */
@Composable
private fun NameDialog(
	title: String,
	name: String,
	archived: Boolean,
	error: Reason?,
	onDismiss: () -> Unit,
	onDelete: (() -> Unit)?,
	onSave: (String, Boolean) -> Unit,
) {
	var n by remember { mutableStateOf(name) }
	var a by remember { mutableStateOf(archived) }
	EditDialog(title, error, onDismiss, { onSave(n, a) }, onDelete) {
		TextInput(stringResource(R.string.field_name), n, { n = it })
		SwitchRow(stringResource(R.string.field_archived), a) { a = it }
	}
}

@Composable
fun CompaniesScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
	val vm = masterDataViewModel()
	val companies by vm.companies.collectAsState()
	val error by vm.error.collectAsState()
	var adding by remember { mutableStateOf(false) }

	ScreenScaffold(
		title = stringResource(R.string.md_companies),
		onBack = onBack,
		actions = { AddButton { vm.clearError(); adding = true } },
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			if (companies.isEmpty())
				item { EmptyText() }
			items(companies, key = { it.id }) { c ->
				ClickableRow(c.name, archivedSuffix(c.archived)) { onOpen(c.id) }
			}
		}
	}
	if (adding)
		NameDialog(stringResource(R.string.company_edit), "", false, error, { adding = false }, null) { n, a ->
			vm.save(CompanyEntity(name = n, archived = a)) { adding = false }
		}
}

@Composable
fun CompanyScreen(id: Long, onBack: () -> Unit) {
	val vm = masterDataViewModel()
	val company = vm.companies.collectAsState().value.firstOrNull { it.id == id }
	val sites by vm.companySites(id).collectAsState()
	val error by vm.error.collectAsState()
	var editing by remember { mutableStateOf(false) }
	var site by remember { mutableStateOf<CompanySiteEntity?>(null) }

	ScreenScaffold(
		title = company?.name.orEmpty(),
		onBack = onBack,
		actions = {
			TextButton(onClick = { vm.clearError(); editing = true }) { Text(stringResource(R.string.action_edit)) }
		},
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			item {
				Row {
					SectionHeader(stringResource(R.string.sites))
					AddButton {
						vm.clearError()
						site = CompanySiteEntity(companyId = id, label = "", lat = 0.0, lon = 0.0)
					}
				}
			}
			if (sites.isEmpty())
				item { EmptyText() }
			items(sites, key = { it.id }) { s ->
				ClickableRow(s.label, s.address ?: "${s.radiusM} m") { vm.clearError(); site = s }
			}
		}
	}

	if (editing && company != null) {
		val delete = confirmDelete(company.name) { vm.delete(company) { editing = false; onBack() } }
		NameDialog(
			stringResource(R.string.company_edit), company.name, company.archived, error,
			{ editing = false }, delete,
		) { n, a -> vm.save(company.copy(name = n, archived = a)) { editing = false } }
	}
	site?.let { s ->
		val delete = if (s.id != 0L) confirmDelete(s.label) { vm.delete(s) { site = null } } else null
		SiteDialog(
			title = stringResource(R.string.site_edit),
			initial = s.takeIf { it.id != 0L }?.let { SiteFields(it.label, it.lat, it.lon, it.radiusM, it.address) },
			error = error,
			onDismiss = { site = null },
			onDelete = delete,
		) { f ->
			vm.save(s.copy(label = f.label, lat = f.lat, lon = f.lon, radiusM = f.radiusM, address = f.address)) { site = null }
		}
	}
}

@Composable
private fun ClientDialog(
	initial: ClientEntity,
	companies: List<CompanyEntity>,
	error: Reason?,
	onDismiss: () -> Unit,
	onDelete: (() -> Unit)?,
	onSave: (ClientEntity) -> Unit,
) {
	var name by remember { mutableStateOf(initial.name) }
	var companyId by remember { mutableStateOf(initial.companyId) }
	var archived by remember { mutableStateOf(initial.archived) }
	EditDialog(
		stringResource(R.string.client_edit), error, onDismiss,
		{ onSave(initial.copy(name = name, companyId = companyId, archived = archived)) }, onDelete,
	) {
		TextInput(stringResource(R.string.field_name), name, { name = it })
		ChoiceField(stringResource(R.string.field_company), companies.map { it.id to it.name }, companyId) { companyId = it }
		SwitchRow(stringResource(R.string.field_archived), archived) { archived = it }
	}
}

@Composable
fun ClientsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
	val vm = masterDataViewModel()
	val clients by vm.clients.collectAsState()
	val companies by vm.companies.collectAsState()
	val error by vm.error.collectAsState()
	var adding by remember { mutableStateOf(false) }
	val active = companies.filter { !it.archived }

	ScreenScaffold(
		title = stringResource(R.string.md_clients),
		onBack = onBack,
		actions = { if (active.isNotEmpty()) AddButton { vm.clearError(); adding = true } },
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			if (companies.isEmpty())
				item { EmptyText(stringResource(R.string.clients_need_company)) }
			else if (clients.isEmpty())
				item { EmptyText() }
			/* grouped by company, in company order */
			for (co in companies) {
				val own = clients.filter { it.companyId == co.id }
				if (own.isEmpty())
					continue
				item(key = "h${co.id}") { SectionHeader(co.name) }
				items(own, key = { it.id }) { c ->
					ClickableRow(c.name, archivedSuffix(c.archived)) { onOpen(c.id) }
				}
			}
		}
	}
	if (adding)
		ClientDialog(ClientEntity(companyId = active.first().id, name = ""), active, error, { adding = false }, null) {
			vm.save(it) { adding = false }
		}
}

@Composable
fun ClientScreen(id: Long, onBack: () -> Unit) {
	val vm = masterDataViewModel()
	val client = vm.clients.collectAsState().value.firstOrNull { it.id == id }
	val companies by vm.companies.collectAsState()
	val sites by vm.clientSites(id).collectAsState()
	val error by vm.error.collectAsState()
	var editing by remember { mutableStateOf(false) }
	var site by remember { mutableStateOf<ClientSiteEntity?>(null) }

	ScreenScaffold(
		title = client?.name.orEmpty(),
		onBack = onBack,
		actions = {
			TextButton(onClick = { vm.clearError(); editing = true }) { Text(stringResource(R.string.action_edit)) }
		},
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			item {
				ListItem(
					headlineContent = { Text(companies.firstOrNull { it.id == client?.companyId }?.name.orEmpty()) },
					overlineContent = { Text(stringResource(R.string.field_company)) },
				)
			}
			item {
				Row {
					SectionHeader(stringResource(R.string.sites))
					AddButton {
						vm.clearError()
						site = ClientSiteEntity(clientId = id, label = "", lat = 0.0, lon = 0.0)
					}
				}
			}
			if (sites.isEmpty())
				item { EmptyText() }
			items(sites, key = { it.id }) { s ->
				ClickableRow(s.label, s.address ?: "${s.radiusM} m") { vm.clearError(); site = s }
			}
		}
	}

	if (editing && client != null) {
		val delete = confirmDelete(client.name) { vm.delete(client) { editing = false; onBack() } }
		/* archived companies stay selectable only if already assigned */
		val choices = companies.filter { !it.archived || it.id == client.companyId }
		ClientDialog(client, choices, error, { editing = false }, delete) { vm.save(it) { editing = false } }
	}
	site?.let { s ->
		val delete = if (s.id != 0L) confirmDelete(s.label) { vm.delete(s) { site = null } } else null
		SiteDialog(
			title = stringResource(R.string.site_edit),
			initial = s.takeIf { it.id != 0L }?.let { SiteFields(it.label, it.lat, it.lon, it.radiusM, it.address) },
			error = error,
			onDismiss = { site = null },
			onDelete = delete,
		) { f ->
			vm.save(s.copy(label = f.label, lat = f.lat, lon = f.lon, radiusM = f.radiusM, address = f.address)) { site = null }
		}
	}
}

@Composable
fun RolesScreen(onBack: () -> Unit) {
	val vm = masterDataViewModel()
	val roles by vm.roles.collectAsState()
	val error by vm.error.collectAsState()
	var editing by remember { mutableStateOf<RoleEntity?>(null) }

	ScreenScaffold(
		title = stringResource(R.string.md_roles),
		onBack = onBack,
		actions = { AddButton { vm.clearError(); editing = RoleEntity(name = "") } },
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			if (roles.isEmpty())
				item { EmptyText() }
			items(roles, key = { it.id }) { r ->
				ClickableRow(r.name, archivedSuffix(r.archived)) { vm.clearError(); editing = r }
			}
		}
	}
	editing?.let { r ->
		val delete = if (r.id != 0L) confirmDelete(r.name) { vm.delete(r) { editing = null } } else null
		NameDialog(stringResource(R.string.role_edit), r.name, r.archived, error, { editing = null }, delete) { n, a ->
			vm.save(r.copy(name = n, archived = a)) { editing = null }
		}
	}
}

@Composable
fun PresetsScreen(onBack: () -> Unit) {
	val vm = masterDataViewModel()
	val presets by vm.presets.collectAsState()
	val roles by vm.roles.collectAsState()
	val companies by vm.companies.collectAsState()
	val error by vm.error.collectAsState()
	var editing by remember { mutableStateOf<ActivityPresetEntity?>(null) }
	val none = stringResource(R.string.field_none)

	ScreenScaffold(
		title = stringResource(R.string.md_presets),
		onBack = onBack,
		actions = { AddButton { vm.clearError(); editing = ActivityPresetEntity(text = "") } },
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			if (presets.isEmpty())
				item { EmptyText() }
			items(presets, key = { it.id }) { p ->
				val extra = listOfNotNull(
					roles.firstOrNull { it.id == p.defaultRoleId }?.name,
					companies.firstOrNull { it.id == p.companyId }?.name,
				).joinToString(" · ").ifEmpty { null }
				ClickableRow(p.text, extra) { vm.clearError(); editing = p }
			}
		}
	}
	editing?.let { p ->
		var text by remember(p) { mutableStateOf(p.text) }
		var roleId by remember(p) { mutableStateOf(p.defaultRoleId) }
		var companyId by remember(p) { mutableStateOf(p.companyId) }
		val delete = if (p.id != 0L) confirmDelete(p.text) { vm.delete(p) { editing = null } } else null
		EditDialog(
			stringResource(R.string.preset_edit), error, { editing = null },
			{ vm.save(p.copy(text = text, defaultRoleId = roleId, companyId = companyId)) { editing = null } },
			delete,
		) {
			TextInput(stringResource(R.string.field_text), text, { text = it }, singleLine = false)
			ChoiceField(
				stringResource(R.string.field_default_role),
				listOf<Pair<Long?, String>>(null to none) + roles.filter { !it.archived || it.id == roleId }.map { it.id to it.name },
				roleId,
			) { roleId = it }
			ChoiceField(
				stringResource(R.string.field_company),
				listOf<Pair<Long?, String>>(null to none) +
					companies.filter { !it.archived || it.id == companyId }.map { it.id to it.name },
				companyId,
			) { companyId = it }
		}
	}
}

@Composable
private fun placeKindLabel(k: PlaceKind) = stringResource(
	when (k) {
		PlaceKind.HOME -> R.string.place_home
		PlaceKind.OTHER -> R.string.place_other
	},
)

@Composable
fun PlacesScreen(onBack: () -> Unit) {
	val vm = masterDataViewModel()
	val places by vm.places.collectAsState()
	val error by vm.error.collectAsState()
	var editing by remember { mutableStateOf<PlaceEntity?>(null) }

	ScreenScaffold(
		title = stringResource(R.string.md_places),
		onBack = onBack,
		actions = {
			AddButton { vm.clearError(); editing = PlaceEntity(kind = PlaceKind.HOME, label = "", lat = 0.0, lon = 0.0) }
		},
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			if (places.isEmpty())
				item { EmptyText() }
			items(places, key = { it.id }) { p ->
				ClickableRow(p.label, placeKindLabel(p.kind)) { vm.clearError(); editing = p }
			}
		}
	}
	editing?.let { p ->
		var kind by remember(p) { mutableStateOf(p.kind) }
		val delete = if (p.id != 0L) confirmDelete(p.label) { vm.delete(p) { editing = null } } else null
		SiteDialog(
			title = stringResource(R.string.place_edit),
			initial = p.takeIf { it.id != 0L }?.let { SiteFields(it.label, it.lat, it.lon, it.radiusM, null) },
			error = error,
			withAddress = false,
			extra = {
				ChoiceField(
					stringResource(R.string.field_kind),
					PlaceKind.entries.map { it to placeKindLabel(it) },
					kind,
				) { kind = it }
			},
			onDismiss = { editing = null },
			onDelete = delete,
		) { f ->
			vm.save(p.copy(kind = kind, label = f.label, lat = f.lat, lon = f.lon, radiusM = f.radiusM)) { editing = null }
		}
	}
}

/* Edit of a working interval (id 0 = new) or of a day's lunch break. */
private sealed interface TimeEdit {
	val day: Int
	val start: Int?
	val end: Int?

	data class Work(val row: WorkScheduleEntity) : TimeEdit {
		override val day get() = row.dayOfWeek
		override val start get() = row.startMin.takeIf { row.id != 0L }
		override val end get() = row.endMin.takeIf { row.id != 0L }
	}

	data class Lunch(override val day: Int, override val start: Int?, override val end: Int?) : TimeEdit
}

@Composable
fun ScheduleScreen(onBack: () -> Unit) {
	val vm = masterDataViewModel()
	val schedule by vm.schedule.collectAsState()
	val lunch by vm.lunchBreaks.collectAsState()
	val error by vm.error.collectAsState()
	var edit by remember { mutableStateOf<TimeEdit?>(null) }
	val locale = LocalConfiguration.current.locales[0]

	ScreenScaffold(title = stringResource(R.string.md_schedule), onBack = onBack) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			for (day in 1..7) {
				val name = DayOfWeek.of(day).getDisplayName(TextStyle.FULL_STANDALONE, locale)
					.replaceFirstChar { it.titlecase(locale) }
				item(key = "d$day") {
					Row {
						SectionHeader(name)
						AddButton {
							vm.clearError()
							edit = TimeEdit.Work(WorkScheduleEntity(dayOfWeek = day, startMin = 0, endMin = 0))
						}
					}
				}
				val rows = schedule.filter { it.dayOfWeek == day }
				items(rows, key = { "w${it.id}" }) { w ->
					ClickableRow("${formatMin(w.startMin)} – ${formatMin(w.endMin)}") {
						vm.clearError()
						edit = TimeEdit.Work(w)
					}
				}
				val l = lunch.firstOrNull { it.dayOfWeek == day }
				item(key = "l$day") {
					ClickableRow(
						if (l != null)
							stringResource(R.string.schedule_lunch, "${formatMin(l.startMin)} – ${formatMin(l.endMin)}")
						else
							stringResource(R.string.schedule_no_lunch),
					) {
						vm.clearError()
						edit = TimeEdit.Lunch(day, l?.startMin, l?.endMin)
					}
				}
			}
		}
	}

	edit?.let { e ->
		var start by remember(e) { mutableStateOf(e.start?.let(::formatMin).orEmpty()) }
		var end by remember(e) { mutableStateOf(e.end?.let(::formatMin).orEmpty()) }
		val close = { edit = null }
		val delete: (() -> Unit)? = when (e) {
			is TimeEdit.Work -> if (e.row.id != 0L) ({ vm.delete(e.row, close) }) else null
			is TimeEdit.Lunch -> if (e.start != null) ({ vm.clearLunch(e.day, close) }) else null
		}
		EditDialog(
			title = stringResource(if (e is TimeEdit.Lunch) R.string.lunch_edit else R.string.interval_edit),
			error = error,
			onDismiss = close,
			onDelete = delete,
			onConfirm = {
				/* -1 makes the repository report an invalid range */
				val s = parseHhMm(start) ?: -1
				val t = parseHhMm(end) ?: -1
				when (e) {
					is TimeEdit.Work -> vm.save(e.row.copy(startMin = s, endMin = t), close)
					is TimeEdit.Lunch -> vm.setLunch(e.day, s, t, close)
				}
			},
		) {
			TextInput(stringResource(R.string.field_start), start, { start = it })
			TextInput(stringResource(R.string.field_end), end, { end = it })
			Text(
				stringResource(R.string.time_hint),
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.padding(top = 4.dp),
			)
		}
	}
}
