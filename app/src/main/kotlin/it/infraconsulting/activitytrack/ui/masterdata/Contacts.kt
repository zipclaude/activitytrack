/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

import it.infraconsulting.activitytrack.ui.common.QuickClientDialog
import it.infraconsulting.activitytrack.ui.common.WithAddButton
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.CallDirection
import it.infraconsulting.activitytrack.data.db.ContactEntity
import it.infraconsulting.activitytrack.data.db.ContactWithPhones
import it.infraconsulting.activitytrack.data.repo.CallRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.AddButton
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.EmptyText
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SearchableChoiceField
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.TextInput
import java.text.DateFormat
import java.util.Date

class ContactsViewModel(private val calls: CallRepository) : ActionViewModel() {
	val contacts = calls.contacts.state(emptyList())

	fun save(c: ContactEntity, numbers: List<String>, done: () -> Unit) = run(done) { calls.saveContact(c, numbers) }
	fun delete(c: ContactEntity, done: () -> Unit) = run(done) { calls.deleteContact(c) }
	fun callsOf(clientId: Long) = calls.callsOf(clientId)
}

/* Contacts of the clients (issue #28): name, numbers, client; imported from the phone book. */
@Composable
fun ContactsScreen(onBack: () -> Unit) {
	val vm = appViewModel { ContactsViewModel(it.calls) }
	val md = masterDataViewModel()
	val contacts by vm.contacts.collectAsState()
	val clients by md.clients.collectAsState()
	val error by vm.error.collectAsState()
	var editing by remember { mutableStateOf<Pair<ContactEntity, List<String>>?>(null) }
	val context = LocalContext.current
	/* a phone of the address book: picking it grants reading that row only (no READ_CONTACTS) */
	val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
		val uri = r.data?.data ?: return@rememberLauncherForActivityResult
		context.contentResolver.query(
			uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
		)?.use { c ->
			if (c.moveToFirst()) {
				vm.clearError()
				editing = ContactEntity(clientId = clients.firstOrNull()?.id ?: 0, name = c.getString(0).orEmpty()) to listOf(c.getString(1).orEmpty())
			}
		}
	}

	ScreenScaffold(
		title = stringResource(R.string.md_contacts),
		onBack = onBack,
		actions = { AddButton { vm.clearError(); editing = ContactEntity(clientId = clients.firstOrNull()?.id ?: 0, name = "") to emptyList() } },
	) { padding ->
		LazyColumn(Modifier.fillMaxSize().padding(padding)) {
			item {
				OutlinedButton(
					onClick = { pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
					modifier = Modifier.padding(16.dp),
				) { Text(stringResource(R.string.contacts_import)) }
			}
			if (contacts.isEmpty())
				item { EmptyText() }
			items(contacts, key = { it.contact.id }) { c: ContactWithPhones ->
				val client = clients.firstOrNull { it.id == c.contact.clientId }?.name.orEmpty()
				ClickableRow(c.contact.name, (listOf(client) + c.phones.map { it.number }).joinToString(" · ")) {
					vm.clearError()
					editing = c.contact to c.phones.map { it.number }
				}
			}
		}
	}

	editing?.let { (c, numbers) ->
		var name by remember(c) { mutableStateOf(c.name) }
		var phones by remember(c) { mutableStateOf(numbers.joinToString("\n")) }
		var clientId by remember(c) { mutableStateOf(c.clientId) }
		var addClient by remember(c) { mutableStateOf(false) }
		val delete = if (c.id != 0L) confirmDelete(c.name) { vm.delete(c) { editing = null } } else null
		EditDialog(
			stringResource(R.string.contact_edit), error.takeIf { it != Reason.BLANK_VALUE }, { editing = null },
			{ vm.save(c.copy(name = name, clientId = clientId), phones.lines()) { editing = null } }, delete,
		) {
			TextInput(stringResource(R.string.field_name), name, { name = it }, importance = Importance.REQUIRED)
			/* the client may not exist yet, e.g. a number imported from the phone book (issue #37) */
			WithAddButton(stringResource(R.string.quick_client), { addClient = true }) {
				SearchableChoiceField(stringResource(R.string.field_client), clients.map { it.id to it.name }, clientId, importance = Importance.REQUIRED) { clientId = it }
			}
			TextInput(stringResource(R.string.contact_numbers), phones, { phones = it }, singleLine = false, importance = Importance.REQUIRED)
			if (error == Reason.BLANK_VALUE)
				Text(stringResource(R.string.contact_numbers_missing), color = MaterialTheme.colorScheme.error)
		}
		if (addClient)
			QuickClientDialog(null, onDone = { client, _ -> addClient = false; clientId = client }, onDismiss = { addClient = false })
	}
}

/* The calls with a client and their length (issue #28), in the client screen. */
@Composable
fun ClientCalls(clientId: Long) {
	val vm = appViewModel(key = "calls/$clientId") { ContactsViewModel(it.calls) }
	val calls by remember(clientId) { vm.callsOf(clientId) }.collectAsState(emptyList())
	if (calls.isEmpty())
		return
	val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
	Column {
		SectionHeader(stringResource(R.string.client_calls))
		for (r in calls.take(50)) {
			val min = (r.call.endUtcMs - r.call.startUtcMs) / 60_000
			val sec = (r.call.endUtcMs - r.call.startUtcMs) / 1000 % 60
			val arrow = if (r.call.direction == CallDirection.INCOMING) "↙" else "↗"
			ClickableRow("$arrow ${r.contactName}", fmt.format(Date(r.call.startUtcMs)) + " · %d:%02d".format(min, sec)) {}
		}
	}
}
