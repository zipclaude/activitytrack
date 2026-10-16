/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class OpenEntryRow(
	val id: Long,
	val client: String?,
	val company: String?,
	val status: EntryStatus,
	val mode: SegmentKind?,
)

class HomeViewModel(entries: EntryRepository, master: MasterDataRepository) : ViewModel() {
	val open = combine(entries.openEntries, master.clients, master.companies) { list, clients, companies ->
		val cl = clients.associateBy { it.id }
		val co = companies.associateBy { it.id }
		list.map { e ->
			OpenEntryRow(e.id, e.clientId?.let { cl[it]?.name }, e.companyId?.let { co[it]?.name }, e.status, e.mode)
		}
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/*
 * Open entries (ACTIVE, SUSPENDED, TO_COMPLETE). Start, commands and the
 * completion form arrive with the next M1 steps.
 */
@Composable
fun HomeScreen(onMasterData: () -> Unit, onSettings: () -> Unit) {
	val vm = appViewModel { HomeViewModel(it.entries, it.masterData) }
	val rows by vm.open.collectAsState()

	ScreenScaffold(
		title = stringResource(R.string.app_name),
		onBack = null,
		actions = {
			TextButton(onClick = onMasterData) { Text(stringResource(R.string.md_title)) }
			TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) }
		},
	) { padding ->
		LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
			if (rows.isEmpty())
				item { EmptyText(stringResource(R.string.home_no_open_entries)) }
			items(rows, key = { it.id }) { r ->
				ListItem(
					headlineContent = { Text(r.client ?: stringResource(R.string.entry_no_client)) },
					overlineContent = r.company?.let { { Text(it) } },
					supportingContent = { Text(statusText(r.status, r.mode)) },
				)
			}
		}
	}
}

@Composable
private fun statusText(status: EntryStatus, mode: SegmentKind?): String = when (status) {
	EntryStatus.ACTIVE -> stringResource(
		when (mode) {
			SegmentKind.TRAVEL -> R.string.mode_travel
			SegmentKind.PAUSE -> R.string.mode_pause
			else -> R.string.mode_work
		},
	)
	EntryStatus.SUSPENDED -> stringResource(R.string.status_suspended)
	EntryStatus.TO_COMPLETE -> stringResource(R.string.status_to_complete)
	EntryStatus.CLOSED -> stringResource(R.string.status_closed)
}
