/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.report

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.AppRestart
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.interchange.ExportFilter
import it.infraconsulting.activitytrack.data.interchange.ExportSection
import it.infraconsulting.activitytrack.data.interchange.ImportReport
import it.infraconsulting.activitytrack.data.storage.DatabaseBackup
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import it.infraconsulting.activitytrack.domain.export.CsvExporter
import it.infraconsulting.activitytrack.domain.interchange.FileFormat
import it.infraconsulting.activitytrack.domain.interchange.GenericConnector
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.parseDate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OutFormat(val label: String) { CSV("CSV"), JSON("JSON"), XML("XML") }

sealed interface ExchangeState {
	data object Idle : ExchangeState
	data object Running : ExchangeState
	data class Exported(val file: String, val uri: Uri, val mime: String, val share: Boolean, val rows: Int?) : ExchangeState
	data class Imported(val report: ImportReport) : ExchangeState
	data class RestoreReady(val staged: DatabaseBackup.Staged) : ExchangeState
	data object InvalidRange : ExchangeState
	data class Failed(val detail: String?) : ExchangeState
}

/*
 * Import / export (M4, DESIGN §11): CSV report rows, or the generic
 * interchange (JSON / XML) with sections and filters; files go to
 * <root>/export and optionally to the share sheet. Import reads a generic
 * file and reports what it did; nothing is blocked by conflicts.
 */
class ExportViewModel(private val c: AppContainer) : ViewModel() {
	private val _state = MutableStateFlow<ExchangeState>(ExchangeState.Idle)
	val state = _state.asStateFlow()

	val companies = c.masterData.companies
	val clients = c.masterData.clients

	fun reset() {
		_state.value = ExchangeState.Idle
	}

	fun invalidRange() {
		_state.value = ExchangeState.InvalidRange
	}

	fun export(
		format: OutFormat,
		from: LocalDate?,
		to: LocalDate?,
		companyId: Long?,
		clientId: Long?,
		sections: Set<ExportSection>,
		siteCoordinates: Boolean,
		share: Boolean,
		locale: Locale,
	) {
		/* CSV is a period report; the interchange may be unbounded (full backup) */
		val bounded = from != null && to != null
		if ((format == OutFormat.CSV && !bounded) || (bounded && (to!!.isBefore(from) || from!!.plusYears(1) < to))) {
			_state.value = ExchangeState.InvalidRange
			return
		}
		_state.value = ExchangeState.Running
		viewModelScope.launch {
			_state.value = try {
				val s = c.settings.current()
				val tree = s.rootUri?.toUri() ?: error("no data folder")
				val zone = ZoneId.systemDefault()
				val stamp = LocalDateTime.now().format(STAMP)
				when (format) {
					OutFormat.CSV -> {
						val company = companyId?.let { c.masterData.company(it)?.name }
						val client = clientId?.let { c.masterData.client(it)?.name }
						val rows = c.reports.exportRows(from!!, to!!, zone, s.rounding, s.carryOverMin)
							.filter { (company == null || it.company == company) && (client == null || it.client == client) }
						val exporter = CsvExporter(locale)
						val name = "activitytrack-$stamp.${exporter.extension}"
						val uri = c.userDataDir.createFile(tree, UserDataDir.Sub.EXPORT, name, exporter.mimeType)
						c.userDataDir.openOutput(uri).use { exporter.export(rows, it) }
						ExchangeState.Exported("${UserDataDir.Sub.EXPORT.dirName}/$name", uri, exporter.mimeType, share, rows.size)
					}
					OutFormat.JSON, OutFormat.XML -> {
						val ff = if (format == OutFormat.JSON) FileFormat.JSON else FileFormat.XML
						val data = c.exporter.build(
							ExportFilter(sections, from, to, companyId, clientId, siteCoordinates, zone),
							generator = c.generator,
						)
						val name = "activitytrack-$stamp.${ff.extension}"
						val uri = c.userDataDir.createFile(tree, UserDataDir.Sub.EXPORT, name, ff.mimeType)
						withContext(Dispatchers.IO) { c.userDataDir.openOutput(uri).use { GenericConnector.write(ff, data, it) } }
						ExchangeState.Exported("${UserDataDir.Sub.EXPORT.dirName}/$name", uri, ff.mimeType, share, null)
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				ExchangeState.Failed(e.message)
			}
		}
	}

	/* Generic interchange file; JSON or XML told apart by the first character. */
	fun import(context: Context, uri: Uri) {
		_state.value = ExchangeState.Running
		viewModelScope.launch {
			_state.value = try {
				val bytes = withContext(Dispatchers.IO) {
					context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read")
				}
				val first = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').firstOrNull()
				val format = if (first == '<') FileFormat.XML else FileFormat.JSON
				val data = withContext(Dispatchers.Default) { GenericConnector.read(format, bytes.inputStream()) }
				ExchangeState.Imported(c.importer.import(data))
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				ExchangeState.Failed(e.message)
			}
		}
	}

	/* A backup zip: checked into the cache, then confirmed by the user. */
	fun prepareRestore(context: Context, uri: Uri) {
		_state.value = ExchangeState.Running
		viewModelScope.launch {
			_state.value = try {
				val staged = withContext(Dispatchers.IO) {
					context.contentResolver.openInputStream(uri)?.use { c.backup.stage(it, ActivityTrackDatabase.VERSION) } ?: error("cannot read")
				}
				ExchangeState.RestoreReady(staged)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				ExchangeState.Failed(e.message)
			}
		}
	}

	/*
	 * Saves the current data as a backup first (nothing is lost by mistake),
	 * then swaps the database and restarts the app.
	 */
	fun confirmRestore(context: Context, staged: DatabaseBackup.Staged) {
		_state.value = ExchangeState.Running
		viewModelScope.launch {
			try {
				val tree = c.settings.current().rootUri?.toUri() ?: error("no data folder")
				val now = java.time.Instant.now()
				val name = DatabaseBackup.fileName(now, ZoneId.systemDefault()).replace(".zip", "-before-restore.zip")
				val uri = c.userDataDir.createFile(tree, UserDataDir.Sub.BACKUP, name, "application/zip")
				c.userDataDir.openOutput(uri).use { c.backup.writeZip(c.generator, now, it) }
				withContext(Dispatchers.IO) { AppRestart.replaceDatabase(context, c, staged) }
				AppRestart.now(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				_state.value = ExchangeState.Failed(e.message)
			}
		}
	}

	private companion object {
		val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
	}
}

@Composable
fun ExportScreen(onBack: () -> Unit) {
	val vm = appViewModel { ExportViewModel(it) }
	val state by vm.state.collectAsState()
	val companies by vm.companies.collectAsState(emptyList())
	val clients by vm.clients.collectAsState(emptyList())
	val context = LocalContext.current
	val locale = LocalConfiguration.current.locales[0]
	val today = LocalDate.now()

	var format by rememberSaveable { mutableStateOf(OutFormat.CSV) }
	var from by rememberSaveable { mutableStateOf(today.withDayOfMonth(1).toString()) }
	var to by rememberSaveable { mutableStateOf(today.toString()) }
	var companyId by rememberSaveable { mutableStateOf<Long?>(null) }
	var clientId by rememberSaveable { mutableStateOf<Long?>(null) }
	var sections by remember { mutableStateOf(ExportSection.entries.toSet()) }
	var coordinates by rememberSaveable { mutableStateOf(false) }
	val all = stringResource(R.string.export_all)

	val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri?.let { vm.import(context, it) }
	}
	val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri?.let { vm.prepareRestore(context, it) }
	}
	val run = { share: Boolean ->
		/* an empty date means "no limit", a malformed one is an error, never "no limit" */
		val f = from.takeIf { it.isNotBlank() }?.let(::parseDate)
		val t = to.takeIf { it.isNotBlank() }?.let(::parseDate)
		if ((from.isNotBlank() && f == null) || (to.isNotBlank() && t == null))
			vm.invalidRange()
		else
			vm.export(format, f, t, companyId, clientId, sections, coordinates, share, locale)
	}

	/* the share sheet opens once per export */
	LaunchedEffect(state) {
		val s = state
		if (s is ExchangeState.Exported && s.share)
			context.startActivity(
				Intent.createChooser(
					Intent(Intent.ACTION_SEND).setType(s.mime).putExtra(Intent.EXTRA_STREAM, s.uri)
						.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
					null,
				),
			)
	}

	ScreenScaffold(title = stringResource(R.string.export_title), onBack = onBack) { padding ->
		Column(
			modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			SectionHeader(stringResource(R.string.export_section))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				for (f in OutFormat.entries)
					FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
			}
			TextInput(stringResource(R.string.export_from), from, { from = it })
			TextInput(stringResource(R.string.export_to), to, { to = it })
			ChoiceField(
				stringResource(R.string.field_company),
				listOf<Pair<Long?, String>>(null to all) + companies.map { it.id to it.name },
				companyId,
			) { companyId = it; clientId = null }
			ChoiceField(
				stringResource(R.string.field_client),
				listOf<Pair<Long?, String>>(null to all) +
					clients.filter { companyId == null || it.companyId == companyId }.map { it.id to it.name },
				clientId,
			) { clientId = it }
			if (format == OutFormat.CSV)
				Text(stringResource(R.string.export_hint), style = MaterialTheme.typography.bodySmall)
			else {
				Text(stringResource(R.string.export_sections), style = MaterialTheme.typography.labelLarge)
				FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					for (sec in ExportSection.entries)
						FilterChip(
							selected = sec in sections,
							onClick = { sections = if (sec in sections) sections - sec else sections + sec },
							label = { Text(sectionLabel(sec)) },
						)
				}
				SwitchRow(stringResource(R.string.export_site_coordinates), coordinates) { coordinates = it }
				Text(stringResource(R.string.export_interchange_hint), style = MaterialTheme.typography.bodySmall)
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(onClick = { run(false) }, enabled = state != ExchangeState.Running, modifier = Modifier.weight(1f)) {
					Text(stringResource(R.string.export_action))
				}
				OutlinedButton(onClick = { run(true) }, enabled = state != ExchangeState.Running, modifier = Modifier.weight(1f)) {
					Text(stringResource(R.string.export_share))
				}
			}

			SectionHeader(stringResource(R.string.import_section))
			Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodySmall)
			OutlinedButton(
				onClick = { pick.launch(arrayOf("application/json", "application/xml", "text/xml", "text/plain", "application/octet-stream")) },
				enabled = state != ExchangeState.Running,
				modifier = Modifier.fillMaxWidth(),
			) { Text(stringResource(R.string.import_action)) }

			SectionHeader(stringResource(R.string.restore_section))
			Text(stringResource(R.string.restore_hint), style = MaterialTheme.typography.bodySmall)
			OutlinedButton(
				onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
				enabled = state != ExchangeState.Running,
				modifier = Modifier.fillMaxWidth(),
			) { Text(stringResource(R.string.restore_action)) }

			when (val s = state) {
				is ExchangeState.Exported -> Text(
					if (s.rows != null) pluralStringResource(R.plurals.export_done, s.rows, s.rows, s.file)
					else stringResource(R.string.export_done_file, s.file),
				)
				ExchangeState.InvalidRange -> Text(stringResource(R.string.export_invalid), color = MaterialTheme.colorScheme.error)
				is ExchangeState.Failed -> Text(
					stringResource(R.string.exchange_failed, s.detail.orEmpty()),
					color = MaterialTheme.colorScheme.error,
				)
				else -> {}
			}
		}
	}

	(state as? ExchangeState.Imported)?.let { ImportReportDialog(it.report, vm::reset) }
	(state as? ExchangeState.RestoreReady)?.let { r ->
		AlertDialog(
			onDismissRequest = vm::reset,
			title = { Text(stringResource(R.string.restore_section)) },
			text = { Text(stringResource(R.string.restore_confirm, r.staged.createdUtc, r.staged.appVersion)) },
			confirmButton = {
				TextButton(onClick = { vm.confirmRestore(context, r.staged) }) {
					Text(stringResource(R.string.restore_do), color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = { TextButton(onClick = vm::reset) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
}

@Composable
private fun sectionLabel(s: ExportSection) = stringResource(
	when (s) {
		ExportSection.MASTER_DATA -> R.string.export_sec_master
		ExportSection.TODOS -> R.string.export_sec_todos
		ExportSection.ENTRIES -> R.string.export_sec_entries
		ExportSection.CALENDAR -> R.string.export_sec_calendar
	},
)

/* Counts per section, then conflicts (app data kept) and skipped records with their reason. */
@Composable
private fun ImportReportDialog(r: ImportReport, onClose: () -> Unit) {
	AlertDialog(
		onDismissRequest = onClose,
		title = { Text(stringResource(R.string.import_report_title)) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				val sections = (r.inserted.keys + r.unchanged.keys).sorted()
				if (sections.isEmpty())
					Text(stringResource(R.string.import_nothing))
				for (sec in sections)
					Text(stringResource(R.string.import_counts, sec, r.inserted[sec] ?: 0, r.unchanged[sec] ?: 0))
				if (r.conflicts.isNotEmpty()) {
					Text(stringResource(R.string.import_conflicts, r.conflicts.size), style = MaterialTheme.typography.titleSmall)
					for (n in r.conflicts.take(MAX_LINES))
						Text("${n.section} · ${n.key}: ${n.detail}", style = MaterialTheme.typography.bodySmall)
				}
				if (r.skipped.isNotEmpty()) {
					Text(
						stringResource(R.string.import_skipped, r.skipped.size),
						style = MaterialTheme.typography.titleSmall,
						color = MaterialTheme.colorScheme.error,
					)
					for (n in r.skipped.take(MAX_LINES))
						Text("${n.section} · ${n.key}: ${n.detail}", style = MaterialTheme.typography.bodySmall)
				}
				if (r.conflicts.size > MAX_LINES || r.skipped.size > MAX_LINES)
					Text("…")
			}
		},
		confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
	)
}

private const val MAX_LINES = 50
