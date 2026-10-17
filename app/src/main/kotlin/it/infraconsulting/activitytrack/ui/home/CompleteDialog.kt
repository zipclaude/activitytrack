/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.EntryDetails
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.TextInput

/*
 * Minimal completion (M1 step 4): activity text or preset, role, notes and
 * the client when the entry was a travel without one. The full form with
 * attachments, references and manual segments replaces it in step 7.
 */
@Composable
fun CompleteDialog(card: EntryCard, vm: HomeViewModel, onClose: () -> Unit) {
	val e = card.entry
	val error by vm.error.collectAsState()
	val clients by vm.clients.collectAsState()
	val roles by vm.roles.collectAsState()
	val presets by vm.presets.collectAsState()
	val none = stringResource(R.string.field_none)

	var clientId by remember { mutableStateOf(e.clientId) }
	var activity by remember { mutableStateOf(e.activity.orEmpty()) }
	var roleId by remember { mutableStateOf(e.roleId) }
	var notes by remember { mutableStateOf(e.notes.orEmpty()) }

	val companyId = clients.firstOrNull { it.id == clientId }?.companyId ?: e.companyId

	EditDialog(
		title = stringResource(R.string.complete_title, card.title ?: ""),
		error = error,
		onDismiss = onClose,
		onConfirm = {
			vm.complete(
				e.id,
				EntryDetails(
					companyId = companyId,
					clientId = clientId,
					roleId = roleId,
					activity = activity,
					locality = e.locality,
					locationKind = e.locationKind,
					notes = notes,
				),
				onClose,
			)
		},
	) {
		if (e.clientId == null) {
			/* travel started without a destination: the client is chosen now */
			val choices = clients.filter { !it.archived && (e.companyId == null || it.companyId == e.companyId) }
			ChoiceField(
				stringResource(R.string.field_client),
				listOf<Pair<Long?, String>>(null to none) + choices.map { it.id to it.name },
				clientId,
			) { clientId = it }
		}
		val usable = presets.filter { it.companyId == null || it.companyId == companyId }
		if (usable.isNotEmpty())
			ChoiceField(
				stringResource(R.string.field_preset),
				listOf<Pair<Long?, String>>(null to none) + usable.map { it.id to it.text },
				null,
			) { id ->
				usable.firstOrNull { it.id == id }?.let { p ->
					activity = p.text
					if (roleId == null) roleId = p.defaultRoleId
				}
			}
		TextInput(stringResource(R.string.field_activity), activity, { activity = it }, singleLine = false)
		ChoiceField(
			stringResource(R.string.field_role),
			listOf<Pair<Long?, String>>(null to none) +
				roles.filter { !it.archived || it.id == roleId }.map { it.id to it.name },
			roleId,
		) { roleId = it }
		TextInput(stringResource(R.string.field_notes), notes, { notes = it }, singleLine = false)
	}
}
