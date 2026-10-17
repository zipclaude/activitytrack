/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import it.infraconsulting.activitytrack.domain.export.CsvExporter
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.parseDate
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ExportState {
	data object Idle : ExportState
	data object Running : ExportState
	data class Done(val file: String, val rows: Int) : ExportState
	data object InvalidRange : ExportState
	data object Failed : ExportState
}

/* CSV export of a date range into <root>/export (DESIGN §11). */
class ExportViewModel(private val c: AppContainer) : ViewModel() {
	private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
	val state = _state.asStateFlow()

	fun export(from: LocalDate?, to: LocalDate?, locale: Locale) {
		if (from == null || to == null || to.isBefore(from) || from.plusYears(1) < to) {
			_state.value = ExportState.InvalidRange
			return
		}
		_state.value = ExportState.Running
		viewModelScope.launch {
			_state.value = try {
				val s = c.settings.current()
				val tree = s.rootUri?.toUri() ?: error("no data folder")
				val zone = ZoneId.systemDefault()
				val rows = c.reports.exportRows(from, to, zone, s.rounding, s.carryOverMin)
				val exporter = CsvExporter(locale)
				val name = "activitytrack-${from.format(STAMP)}-${to.format(STAMP)}.${exporter.extension}"
				val uri = c.userDataDir.createFile(tree, UserDataDir.Sub.EXPORT, name, exporter.mimeType)
				c.userDataDir.openOutput(uri).use { exporter.export(rows, it) }
				ExportState.Done("${UserDataDir.Sub.EXPORT.dirName}/$name", rows.size)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				ExportState.Failed
			}
		}
	}

	private companion object {
		val STAMP: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
	}
}

@Composable
fun ExportScreen(onBack: () -> Unit) {
	val vm = appViewModel { ExportViewModel(it) }
	val state by vm.state.collectAsState()
	val today = LocalDate.now()
	var from by remember { mutableStateOf(today.withDayOfMonth(1).toString()) }
	var to by remember { mutableStateOf(today.toString()) }
	val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

	ScreenScaffold(title = stringResource(R.string.export_title), onBack = onBack) { padding ->
		Column(
			modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			TextInput(stringResource(R.string.export_from), from, { from = it })
			TextInput(stringResource(R.string.export_to), to, { to = it })
			Text(stringResource(R.string.export_hint), style = MaterialTheme.typography.bodySmall)
			Button(
				onClick = { vm.export(parseDate(from), parseDate(to), locale) },
				enabled = state != ExportState.Running,
				modifier = Modifier.fillMaxWidth(),
			) { Text(stringResource(R.string.export_csv)) }
			when (val s = state) {
				is ExportState.Done -> Text(pluralStringResource(R.plurals.export_done, s.rows, s.rows, s.file))
				ExportState.InvalidRange -> Text(stringResource(R.string.export_invalid), color = MaterialTheme.colorScheme.error)
				ExportState.Failed -> Text(stringResource(R.string.export_failed), color = MaterialTheme.colorScheme.error)
				else -> {}
			}
		}
	}
}
