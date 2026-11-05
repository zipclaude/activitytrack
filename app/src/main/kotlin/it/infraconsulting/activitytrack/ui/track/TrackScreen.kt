/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.track

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.TrackFilter
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.domain.settings.AppSettings
import it.infraconsulting.activitytrack.domain.track.GpxWriter
import it.infraconsulting.activitytrack.maps.InstalledMap
import it.infraconsulting.activitytrack.maps.OfflineMaps
import it.infraconsulting.activitytrack.domain.track.KmlWriter
import it.infraconsulting.activitytrack.domain.track.TrackEntry
import it.infraconsulting.activitytrack.domain.track.TrackFormat
import it.infraconsulting.activitytrack.domain.track.TrackGeometry
import it.infraconsulting.activitytrack.domain.track.TrackPeriod
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.DateField
import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import it.infraconsulting.activitytrack.ui.common.parseDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrackUi(
	val period: TrackPeriod = TrackPeriod.DAY,
	val anchor: LocalDate = LocalDate.now(),
	val travelOnly: Boolean = false,
	val tracks: List<TrackEntry> = emptyList(),
	val loading: Boolean = true,
)

sealed interface TrackAction {
	data object Idle : TrackAction
	data object Running : TrackAction
	data class Exported(val file: String, val uri: android.net.Uri, val mime: String, val share: Boolean, val points: Int, val deleted: Int) : TrackAction
	data class Cleaned(val deleted: Int) : TrackAction
	data class Failed(val detail: String?) : TrackAction
}

/* What the clean-up deletes. */
enum class CleanScope { ALL, OLDER_THAN_MONTH, DAY }

/*
 * Map, export and clean-up of the recorded tracks (issue #155): the track of
 * one activity ([entryId]), or of a day, week or month.
 */
class TrackViewModel(private val c: AppContainer, private val entryId: Long?) : ViewModel() {
	private val _ui = MutableStateFlow(TrackUi())
	val ui = _ui.asStateFlow()
	private val _action = MutableStateFlow<TrackAction>(TrackAction.Idle)
	val action = _action.asStateFlow()
	val settings = c.settings.settings
	val offlineMaps = c.offlineMaps
	private var job: Job? = null

	init {
		reload()
	}

	fun setPeriod(p: TrackPeriod) = change { it.copy(period = p) }

	fun setAnchor(d: LocalDate) = change { it.copy(anchor = d) }

	fun shift(n: Long) = change { it.copy(anchor = it.period.shift(it.anchor, n)) }

	fun setTravelOnly(v: Boolean) = change { it.copy(travelOnly = v) }

	fun clearAction() {
		_action.value = TrackAction.Idle
	}

	private fun change(f: (TrackUi) -> TrackUi) {
		_ui.update(f)
		reload()
	}

	fun reload() {
		job?.cancel()
		job = viewModelScope.launch { load() }
	}

	private suspend fun load() {
		val u = _ui.value
		val tracks = if (entryId != null)
			c.locations.trackOfEntry(entryId)
		else {
			val (from, to) = u.period.bounds(u.anchor, ZoneId.systemDefault())
			c.locations.track(from, to, TrackFilter(travelOnly = u.travelOnly))
		}
		_ui.update { it.copy(tracks = tracks, loading = false) }
	}

	/* Writes the file into <root>/export; with [deleteAfter], deletes exactly the exported samples once it is written. */
	fun export(format: TrackFormat, deleteAfter: Boolean, share: Boolean, title: String) {
		val tracks = _ui.value.tracks
		if (tracks.isEmpty())
			return
		_action.value = TrackAction.Running
		viewModelScope.launch {
			_action.value = try {
				val tree = c.settings.current().rootUri?.toUri() ?: error("no data folder")
				val text = when (format) {
					TrackFormat.GPX -> GpxWriter.write(tracks, title, System.currentTimeMillis(), c.generator)
					TrackFormat.KML -> KmlWriter.write(tracks, title)
				}
				val name = "activitytrack-track-${LocalDateTime.now().format(STAMP)}.${format.extension}"
				val uri = c.userDataDir.createFile(tree, UserDataDir.Sub.EXPORT, name, format.mimeType)
				withContext(Dispatchers.IO) { c.userDataDir.openOutput(uri).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
				val deleted = if (deleteAfter) c.locations.deleteSamplesOf(tracks) else 0
				load()
				TrackAction.Exported("${UserDataDir.Sub.EXPORT.dirName}/$name", uri, format.mimeType, share, tracks.sumOf { it.points.size }, deleted)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				TrackAction.Failed(e.message)
			}
		}
	}

	fun clean(scope: CleanScope, day: LocalDate) {
		viewModelScope.launch {
			val zone = ZoneId.systemDefault()
			val deleted = when (scope) {
				CleanScope.ALL -> c.locations.deleteAllSamples()
				CleanScope.OLDER_THAN_MONTH ->
					c.locations.deleteSamplesBefore(LocalDate.now().minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli())
				CleanScope.DAY -> {
					val (from, to) = TrackPeriod.DAY.bounds(day, zone)
					c.locations.deleteSamplesBetween(from, to)
				}
			}
			load()
			_action.value = TrackAction.Cleaned(deleted)
		}
	}

	private companion object {
		val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
	}
}

@Composable
fun TrackScreen(entryId: Long?, onBack: () -> Unit, onOpenEntry: (Long) -> Unit) {
	val vm = appViewModel(key = "track-$entryId") { TrackViewModel(it, entryId) }
	val ui by vm.ui.collectAsState()
	val action by vm.action.collectAsState()
	val settings by vm.settings.collectAsState(AppSettings())
	val policy = LocalPolicy.current
	val context = LocalContext.current
	var selected by remember { mutableStateOf<TrackEntry?>(null) }
	var exportDialog by rememberSaveable { mutableStateOf(false) }
	var cleanDialog by rememberSaveable { mutableStateOf(false) }

	/* the map shows with the setting, a base map (the tile service or the offline map) and the policy; the export is the policy's too, and needs exports at all */
	val offline by vm.offlineMaps.installed.collectAsState()
	val network = remember { OfflineMaps.networkAvailable(context) }
	val basemap = chooseBasemap(settings, vm.offlineMaps, offline, ui.tracks, network)
	val mapShown = policy.enabled(Feature.TRACK_MAP) && settings.trackMapEnabled && basemap != null
	val canExport = policy.enabled(Feature.TRACK_EXPORT) && policy.enabled(Feature.EXPORT)

	LaunchedEffect(ui.tracks) { selected = null }
	LaunchedEffect(action) {
		val a = action
		if (a is TrackAction.Exported && a.share)
			context.startActivity(
				Intent.createChooser(
					Intent(Intent.ACTION_SEND).setType(a.mime).putExtra(Intent.EXTRA_STREAM, a.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
					null,
				),
			)
	}

	val locale = Locale.getDefault()
	val title = periodLabel(ui, entryId != null, locale)
	ScreenScaffold(title = stringResource(R.string.track_title), onBack = onBack) { padding ->
		Column(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			if (entryId == null) {
				Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					FilterChip(ui.period == TrackPeriod.DAY, { vm.setPeriod(TrackPeriod.DAY) }, { Text(stringResource(R.string.track_day)) })
					FilterChip(ui.period == TrackPeriod.WEEK, { vm.setPeriod(TrackPeriod.WEEK) }, { Text(stringResource(R.string.track_week)) })
					FilterChip(ui.period == TrackPeriod.MONTH, { vm.setPeriod(TrackPeriod.MONTH) }, { Text(stringResource(R.string.track_month)) })
				}
				Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
					TextButton({ vm.shift(-1) }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
					Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
					TextButton({ vm.shift(1) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
				}
				Box(Modifier.padding(horizontal = 16.dp)) { SwitchRow(stringResource(R.string.track_travel_only), ui.travelOnly, vm::setTravelOnly) }
			}

			Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (mapShown) 0.dp else 16.dp)) {
				when {
					mapShown && basemap != null -> TrackMap(
						tracks = ui.tracks,
						basemap = basemap,
						toleranceM = if (ui.period == TrackPeriod.DAY || entryId != null) 3.0 else 15.0,
						onTap = { at, tolerance -> selected = TrackGeometry.nearest(ui.tracks, at, tolerance) },
					)
					else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(stringResource(R.string.track_map_off), style = MaterialTheme.typography.bodySmall)
						for (e in ui.tracks)
							EntryRow(e, locale) { onOpenEntry(e.entryId) }
					}
				}
			}

			Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				if (ui.tracks.isEmpty() && !ui.loading)
					Text(stringResource(R.string.track_empty), style = MaterialTheme.typography.bodyMedium)
				else
					Text(summary(ui.tracks), style = MaterialTheme.typography.bodyMedium)
				selected?.let { e -> Card(Modifier.fillMaxWidth()) { EntryRow(e, locale) { onOpenEntry(e.entryId) } } }
				when (val a = action) {
					is TrackAction.Exported -> Text(
						stringResource(R.string.track_exported, a.points, a.file) +
							if (a.deleted > 0) " " + stringResource(R.string.track_deleted, a.deleted) else "",
					)
					is TrackAction.Cleaned -> Text(stringResource(R.string.track_deleted, a.deleted))
					is TrackAction.Failed -> Text(stringResource(R.string.exchange_failed, a.detail.orEmpty()), color = MaterialTheme.colorScheme.error)
					else -> {}
				}
				Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					if (canExport)
						Button({ vm.clearAction(); exportDialog = true }, Modifier.weight(1f), enabled = ui.tracks.isNotEmpty() && action != TrackAction.Running) {
							Text(stringResource(R.string.track_export))
						}
					OutlinedButton({ vm.clearAction(); cleanDialog = true }, Modifier.weight(1f)) { Text(stringResource(R.string.track_cleanup)) }
				}
			}
		}
	}

	if (exportDialog)
		ExportDialog(
			onDismiss = { exportDialog = false },
			onExport = { format, deleteAfter, share ->
				exportDialog = false
				vm.export(format, deleteAfter, share, "ActivityTrack $title")
			},
		)
	if (cleanDialog)
		CleanDialog(ui.anchor, onDismiss = { cleanDialog = false }) { scope, day ->
			cleanDialog = false
			vm.clean(scope, day)
		}
}

@Composable
private fun ExportDialog(onDismiss: () -> Unit, onExport: (TrackFormat, Boolean, Boolean) -> Unit) {
	var format by rememberSaveable { mutableStateOf(TrackFormat.KML) }
	var deleteAfter by rememberSaveable { mutableStateOf(false) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.track_export)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					for (f in TrackFormat.entries)
						FilterChip(format == f, { format = f }, { Text(f.name) })
				}
				Text(stringResource(R.string.track_export_hint), style = MaterialTheme.typography.bodySmall)
				Row(Modifier.selectable(deleteAfter, role = Role.Checkbox) { deleteAfter = !deleteAfter }, verticalAlignment = Alignment.CenterVertically) {
					Checkbox(deleteAfter, null)
					Text(stringResource(R.string.track_delete_after), Modifier.padding(start = 8.dp))
				}
			}
		},
		confirmButton = {
			Row {
				TextButton({ onExport(format, deleteAfter, true) }) { Text(stringResource(R.string.export_share)) }
				TextButton({ onExport(format, deleteAfter, false) }) { Text(stringResource(R.string.export_action)) }
			}
		},
		dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
	)
}

@Composable
private fun CleanDialog(anchor: LocalDate, onDismiss: () -> Unit, onClean: (CleanScope, LocalDate) -> Unit) {
	var scope by rememberSaveable { mutableStateOf(CleanScope.OLDER_THAN_MONTH) }
	var day by rememberSaveable { mutableStateOf(anchor.toString()) }
	val parsed = parseDate(day)
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.track_cleanup)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				for ((s, label) in listOf(
					CleanScope.OLDER_THAN_MONTH to R.string.track_clean_old,
					CleanScope.DAY to R.string.track_clean_day,
					CleanScope.ALL to R.string.track_clean_all,
				))
					Row(Modifier.fillMaxWidth().selectable(scope == s, role = Role.RadioButton) { scope = s }, verticalAlignment = Alignment.CenterVertically) {
						RadioButton(scope == s, null)
						Text(stringResource(label), Modifier.padding(start = 8.dp))
					}
				if (scope == CleanScope.DAY)
					DateField(stringResource(R.string.track_clean_day), day, { day = it })
				Text(stringResource(R.string.track_clean_note), style = MaterialTheme.typography.bodySmall)
			}
		},
		confirmButton = {
			TextButton({ onClean(scope, parsed ?: anchor) }, enabled = scope != CleanScope.DAY || parsed != null) {
				Text(stringResource(R.string.track_clean_do), color = MaterialTheme.colorScheme.error)
			}
		},
		dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
	)
}

@Composable
private fun EntryRow(e: TrackEntry, locale: Locale, onClick: () -> Unit) {
	val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(e.zone)
	Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp)) {
		Text(listOfNotNull(e.client, e.activity?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { e.company.orEmpty() }, style = MaterialTheme.typography.titleSmall)
		val span = listOfNotNull(e.startUtcMs, e.endUtcMs).map { time.format(Instant.ofEpochMilli(it)) }.joinToString(" – ")
		Text(listOfNotNull(span, e.km?.let { "%.1f km".format(locale, it) }, e.locality).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
	}
}

@Composable
private fun summary(tracks: List<TrackEntry>): String {
	val km = tracks.mapNotNull { it.km }.sum()
	return stringResource(R.string.track_summary, tracks.size, tracks.sumOf { it.points.size }, "%.1f".format(Locale.getDefault(), km))
}

@Composable
private fun periodLabel(ui: TrackUi, entry: Boolean, locale: Locale): String {
	if (entry)
		return stringResource(R.string.track_entry)
	val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
	val (a, b) = ui.period.days(ui.anchor)
	return when (ui.period) {
		TrackPeriod.DAY -> date.format(a)
		TrackPeriod.WEEK -> date.format(a) + " – " + date.format(b.minusDays(1))
		TrackPeriod.MONTH -> a.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }
	}
}

/*
 * What goes under the track: an offline map that covers the track when the OSM tiles are off or
 * there is no network; else the OSM tiles when allowed; else nothing (the list of the activities
 * stands in for the map). With no track to place yet, any offline map will do.
 */
private fun chooseBasemap(settings: AppSettings, maps: OfflineMaps, installed: List<InstalledMap>, tracks: List<TrackEntry>, network: Boolean): Basemap? {
	val pts = tracks.flatMap { it.points }
	val centre = if (pts.isEmpty()) null else GeoPoint((pts.minOf { it.lat } + pts.maxOf { it.lat }) / 2, (pts.minOf { it.lon } + pts.maxOf { it.lon }) / 2)
	val usable = if (settings.offlineMapsEnabled) installed else emptyList()
	val offline = if (centre != null) maps.best(centre, usable) else usable.firstOrNull { maps.file(it) != null }
	val file = offline?.let(maps::file)
	return when {
		offline != null && file != null && (!settings.tiles.enabled || !network) -> Basemap.Offline(file.absolutePath, offline.maxZoom)
		settings.tiles.enabled -> Basemap.Online(settings.tiles.endpoint)
		else -> null
	}
}
