/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.report

import it.infraconsulting.activitytrack.ui.common.TagFilterChip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.Report
import it.infraconsulting.activitytrack.data.repo.ReportRepository
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.domain.report.EntryDayLine
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.clockOf
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(reports: ReportRepository, settings: SettingsRepository) : ActionViewModel() {
	val zone: ZoneId = ZoneId.systemDefault()
	private val _day = MutableStateFlow(LocalDate.now(zone))
	val day = _day.asStateFlow()

	/* reloaded on day, settings or any entry change */
	val report = combine(_day, settings.settings, reports.changes) { d, s, _ -> d to s }
		.mapLatest { (d, s) -> d to reports.report(d, d, zone, s.rounding, s.carryOverMin) }
		.state(null)

	fun shift(days: Long) {
		_day.value = _day.value.plusDays(days)
	}

	fun today() {
		_day.value = LocalDate.now(zone)
	}
}

/* Solar vs reported totals of a day and its entry lines (SPEC §4.4). */
@Composable
fun DayScreen(onBack: (() -> Unit)?, onOpenEntry: (Long) -> Unit, onExport: () -> Unit) {
	val vm = appViewModel { DayViewModel(it.reports, it.settings) }
	val day by vm.day.collectAsState()
	val loaded by vm.report.collectAsState()
	/* tag filter (M19): lines and totals of the entries with that tag */
	var tag by rememberSaveable { mutableStateOf<String?>(null) }
	val locale = LocalConfiguration.current.locales[0]
	val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)

	ScreenScaffold(
		title = stringResource(R.string.day_title),
		onBack = onBack,
		actions = { TextButton(onClick = onExport) { Text(stringResource(R.string.export_title)) } },
	) { padding ->
		Column(modifier = Modifier.fillMaxSize().padding(padding)) {
			Row(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				TextButton(onClick = { vm.shift(-1) }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
				Text(
					day.format(fmt),
					modifier = Modifier.weight(1f),
					textAlign = TextAlign.Center,
					style = MaterialTheme.typography.titleMedium,
				)
				TextButton(onClick = { vm.shift(1) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
			}
			if (day != LocalDate.now(vm.zone))
				TextButton(onClick = vm::today, modifier = Modifier.align(Alignment.CenterHorizontally)) {
					Text(stringResource(R.string.day_today))
				}
			val report = loaded?.takeIf { it.first == day }?.second
			if (report != null) {
				Row(Modifier.padding(horizontal = 16.dp)) {
					TagFilterChip(report.meta.values.flatMap { it.tags }.distinct(), tag) { tag = it }
				}
				DayContent(report, tag, vm.zone, onOpenEntry)
			}
		}
	}
}

@Composable
private fun DayContent(report: Report, tag: String?, zone: ZoneId, onOpenEntry: (Long) -> Unit) {
	val totals = report.days.firstOrNull()
	val lines = totals?.lines.orEmpty().filter { tag == null || tag in report.meta[it.entryId]?.tags.orEmpty() }
	LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		item {
			Row(
				modifier = Modifier.fillMaxWidth().padding(16.dp),
				horizontalArrangement = Arrangement.SpaceEvenly,
			) {
				/* one total, the reported one (owner, issue #99: the solar time interested only him) */
				Total(
					stringResource(R.string.day_reported),
					if (tag == null) totals?.reported ?: Duration.ZERO else lines.fold(Duration.ZERO) { a, l -> a + l.reported },
				)
			}
		}
		if (lines.isEmpty())
			item { EmptyText(stringResource(R.string.day_empty)) }
		items(lines, key = { it.entryId }) { line ->
			LineCard(line, report, zone) { onOpenEntry(line.entryId) }
		}
	}
}

@Composable
private fun Total(label: String, d: Duration) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Text(hm(d), style = MaterialTheme.typography.headlineMedium)
		Text(label, style = MaterialTheme.typography.labelMedium)
	}
}

@Composable
private fun LineCard(line: EntryDayLine, report: Report, zone: ZoneId, onClick: () -> Unit) {
	val m = report.meta[line.entryId]
	Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
		androidx.compose.foundation.layout.Box {
		/* the tags' colours in the top right corner (issue #133) */
		it.infraconsulting.activitytrack.ui.common.TagDots(m?.tags.orEmpty(), Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp))
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			if (m != null && m.client.isNotEmpty())
				Text(m.company, style = MaterialTheme.typography.labelMedium)
			Text(
				m?.client?.ifEmpty { m.company }.orEmpty().ifEmpty { stringResource(R.string.entry_no_client) },
				style = MaterialTheme.typography.titleMedium,
			)
			if (!m?.activity.isNullOrEmpty())
				Text(m!!.activity, style = MaterialTheme.typography.bodyMedium)
			Text(
				clockOf(line.firstStart.toEpochMilli(), zone) + " – " + clockOf(line.lastEnd.toEpochMilli(), zone),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.primary,
			)
			/* exact -> rounded, the reported figure */
			Text(
				stringResource(
					R.string.day_line_fmt,
					hm(line.work), hm(line.roundedWork),
					hm(line.travel), hm(line.roundedTravel),
					hm(line.pause), hm(line.roundedPause),
				),
				style = MaterialTheme.typography.bodySmall,
			)
		}
		}
	}
}

private fun hm(d: Duration): String = "%d:%02d".format(d.toHours(), d.toMinutesPart())
