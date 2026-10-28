/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * The company and client last chosen in a dialog (issue #106, owner: one
 * works for a company the whole day): the dialogs start from them instead
 * of the first in the list, and every save there updates them. Kept across
 * restarts; not used by "Nuova attività", which has its own proposal.
 */
class LastPickViewModel(private val settings: SettingsRepository) : ViewModel() {
	val pick = settings.settings.map { it.lastCompanyId to it.lastClientId }
		.stateIn(viewModelScope, SharingStarted.Eagerly, null to null)

	/* a null leaves that one as it was */
	fun remember(companyId: Long?, clientId: Long? = null) {
		if (companyId == null && clientId == null)
			return
		viewModelScope.launch {
			settings.update { it.copy(lastCompanyId = companyId ?: it.lastCompanyId, lastClientId = clientId ?: it.lastClientId) }
		}
	}
}

@Composable
fun lastPickViewModel(): LastPickViewModel = appViewModel(key = "last-pick") { LastPickViewModel(it.settings) }

/* (company, client) last chosen in a dialog */
@Composable
fun lastPick(): Pair<Long?, Long?> = lastPickViewModel().pick.collectAsState().value
