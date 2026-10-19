/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.connect.antares.AntaresSync
import it.infraconsulting.activitytrack.data.db.RemoteActivityTypeEntity
import it.infraconsulting.activitytrack.data.db.RemotePortfolioEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.RemoteRepository
import it.infraconsulting.activitytrack.data.repo.TodoRepository
import it.infraconsulting.activitytrack.domain.interchange.AntaresConnector
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/*
 * The entry's Antares data (M24, owner 2026-10-19), for companies on the
 * Antares connector: service or project (filtered on the client), activity
 * type among those enabled there, "included in the contract". An entry from
 * a ticket is reported on the ticket: only the type is asked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AntaresEntryViewModel(
	private val entryId: Long,
	entries: it.infraconsulting.activitytrack.data.repo.EntryRepository,
	private val master: MasterDataRepository,
	private val todos: TodoRepository,
	private val remote: RemoteRepository,
) : ActionViewModel() {
	val entry = entries.entry(entryId).state(null)
	private val company = entries.entry(entryId).filterNotNull().map { e -> e.companyId?.let { master.company(it) } }

	/* null = the company is not on Antares */
	val enabled = company.map { it?.connectorId == AntaresConnector.id }.state(false)

	/* the ticket's portfolio when the entry comes from an Antares ticket */
	val ticketPortfolio = entries.entry(entryId).filterNotNull()
		.map { e -> e.todoId?.let { todos.find(it) }?.remotePortfolioId }.state(null)

	val portfolios = combine(entries.entry(entryId).filterNotNull(), company) { e, co -> e to co }
		.flatMapLatest { (e, co) ->
			if (co == null) flowOf(emptyList())
			else remote.portfolios(co.id).map { list ->
				val code = e.clientId?.let { master.client(it) }?.externalCode?.lowercase()
				list.filter { (it.active || it.id == e.remotePortfolioId) && (code == null || it.customerId?.lowercase() == code || it.id == e.remotePortfolioId) }
			}
		}.state(emptyList())

	val types = combine(entries.entry(entryId).filterNotNull(), ticketPortfolio) { e, tp -> tp ?: e.remotePortfolioId }
		.flatMapLatest { pid -> if (pid == null) flowOf(emptyList()) else remote.types(pid) }.state(emptyList())

	val sent = remote.sentOf(entryId).state(emptyList())

	/* first open: the last service, project and type used on this client */
	fun propose(e: TimeEntryEntity) = viewModelScope.launch {
		if (e.remotePortfolioId != null || e.remoteActivityTypeId != null || e.clientId == null)
			return@launch
		val last = remote.lastOnClient(e.clientId!!) ?: return@launch
		remote.setEntryRemote(entryId, last.remotePortfolioId, last.remoteActivityTypeId, last.remoteIncluded)
	}

	fun setPortfolio(id: String?) = run { remote.setEntryRemote(entryId, id, null, null) }

	fun setType(e: TimeEntryEntity, t: RemoteActivityTypeEntity?) = run {
		remote.setEntryRemote(entryId, e.remotePortfolioId, t?.typeId, t?.included)
	}

	fun setIncluded(e: TimeEntryEntity, included: Boolean) = run {
		remote.setEntryRemote(entryId, e.remotePortfolioId, e.remoteActivityTypeId, included)
	}
}

@Composable
fun AntaresSection(entryId: Long) {
	val vm = appViewModel(key = "antares/$entryId") { AntaresEntryViewModel(entryId, it.entries, it.masterData, it.todos, it.remote) }
	val enabled by vm.enabled.collectAsState()
	val e by vm.entry.collectAsState()
	val portfolios by vm.portfolios.collectAsState()
	val types by vm.types.collectAsState()
	val ticket by vm.ticketPortfolio.collectAsState()
	val sent by vm.sent.collectAsState()
	val entry = e ?: return
	if (!enabled)
		return
	LaunchedEffect(entry.id) { vm.propose(entry) }
	val none = stringResource(R.string.field_none)
	SectionHeader("Antares")
	Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		if (ticket != null)
			Text(stringResource(R.string.antares_on_ticket), style = MaterialTheme.typography.bodyMedium)
		else
			ChoiceField(
				stringResource(R.string.antares_portfolio),
				listOf<Pair<String?, String>>(null to none) + portfolios.map { it.id to label(it) },
				entry.remotePortfolioId,
				importance = Importance.REQUIRED,
			) { vm.setPortfolio(it) }
		ChoiceField(
			stringResource(R.string.antares_activity_type),
			listOf<Pair<String?, String>>(null to none) + types.map { it.typeId to it.name },
			entry.remoteActivityTypeId,
			importance = Importance.REQUIRED,
		) { id -> vm.setType(entry, types.firstOrNull { it.typeId == id }) }
		SwitchRow(stringResource(R.string.antares_included), entry.remoteIncluded ?: false) { vm.setIncluded(entry, it) }
		val last = sent.maxOfOrNull { it.syncedUtcMs }
		Text(
			if (last == null) stringResource(R.string.antares_not_sent)
			else stringResource(R.string.antares_sent_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))),
			style = MaterialTheme.typography.bodySmall,
		)
	}
}

@Composable
private fun label(p: RemotePortfolioEntity): String =
	stringResource(if (p.kind == AntaresSync.SERVICE) R.string.antares_service else R.string.antares_project) + " · " + p.name
