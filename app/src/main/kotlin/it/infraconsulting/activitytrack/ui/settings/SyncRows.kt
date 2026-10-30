/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.sync.PairError
import it.infraconsulting.activitytrack.domain.sync.PairException
import it.infraconsulting.activitytrack.domain.sync.PairLink
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/*
 * Settings → Sync (issue #113): pairing with ActivityTrack Web by its QR
 * code (camera, pasted link, or a link that opened the app), the server and
 * user it is paired with, unpairing.
 */
@Composable
fun SyncRows() {
	val context = LocalContext.current
	val app = context.applicationContext as ActivityTrackApp
	val account = app.syncAccount
	val info by account.info.collectAsState()
	val pending by app.container.sync.observePending().collectAsState(initial = 0)
	val scope = rememberCoroutineScope()
	/* debug builds accept http, for a development server */
	val allowHttp = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

	var scanning by remember { mutableStateOf(false) }
	var pasting by remember { mutableStateOf(false) }
	var proposed by remember { mutableStateOf<PairLink?>(null) }
	var busy by remember { mutableStateOf(false) }
	var message by remember { mutableStateOf<String?>(null) }
	/* the message reports a failure (shown in the error colour) */
	var failed by remember { mutableStateOf(true) }
	var confirmUnpair by remember { mutableStateOf(false) }

	val errorText = errorTexts()
	fun offer(text: String) {
		message = null
		proposed = try {
			PairLink.parse(text, allowHttp)
		} catch (e: PairException) {
			message = errorText(e)
			failed = true
			null
		}
	}

	val link by account.pendingLink.collectAsState()
	LaunchedEffect(link) {
		link?.let {
			account.pendingLink.value = null
			offer(it)
		}
	}
	val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted) {
			scanning = true
		} else {
			message = context.getString(R.string.sync_camera_denied)
			failed = true
		}
	}

	val paired = info
	if (paired == null) {
		Text(stringResource(R.string.sync_intro))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(enabled = !busy, onClick = {
				if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
				else askCamera.launch(Manifest.permission.CAMERA)
			}) { Text(stringResource(R.string.sync_scan)) }
			OutlinedButton(enabled = !busy, onClick = { pasting = true }) { Text(stringResource(R.string.sync_paste)) }
		}
	} else {
		Info(stringResource(R.string.sync_server), paired.server + if (paired.pinned) " · " + stringResource(R.string.sync_pinned) else "")
		Info(stringResource(R.string.sync_user), if (paired.userName.isBlank()) paired.username else "${paired.userName} (${paired.username})")
		Info(stringResource(R.string.sync_org), paired.org)
		Info(stringResource(R.string.sync_paired_since), format(paired.pairedUtcMs))
		Info(stringResource(R.string.sync_pending), pending.toString())
		OutlinedButton(onClick = { confirmUnpair = true }) { Text(stringResource(R.string.sync_unpair)) }
	}
	if (busy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		CircularProgressIndicator()
		Text(stringResource(R.string.sync_pairing))
	}
	message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }

	if (scanning) QrScanner(onCode = { scanning = false; offer(it) }, onDismiss = { scanning = false })
	if (pasting) {
		var text by remember { mutableStateOf("") }
		AlertDialog(
			onDismissRequest = { pasting = false },
			title = { Text(stringResource(R.string.sync_paste)) },
			text = { OutlinedTextField(text, { text = it }, placeholder = { Text("activitytrack://pair?…") }, modifier = Modifier.fillMaxWidth()) },
			confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { pasting = false; offer(text) }) { Text(stringResource(R.string.action_ok)) } },
			dismissButton = { TextButton(onClick = { pasting = false }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	proposed?.let { p ->
		AlertDialog(
			onDismissRequest = { proposed = null },
			title = { Text(stringResource(R.string.sync_confirm_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(p.server, style = MaterialTheme.typography.titleMedium)
					if (paired != null) Text(stringResource(R.string.sync_confirm_replace, paired.server))
					if (p.pin != null) Text(stringResource(R.string.sync_confirm_pinned))
				}
			},
			confirmButton = {
				TextButton(onClick = {
					proposed = null
					busy = true
					scope.launch {
						try {
							val done = account.pair(p)
							message = context.getString(R.string.sync_paired_ok, done.org)
							failed = false
						} catch (e: PairException) {
							message = errorText(e)
							failed = true
						} finally {
							busy = false
						}
					}
				}) { Text(stringResource(R.string.sync_pair)) }
			},
			dismissButton = { TextButton(onClick = { proposed = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	if (confirmUnpair) AlertDialog(
		onDismissRequest = { confirmUnpair = false },
		title = { Text(stringResource(R.string.sync_unpair)) },
		text = { Text(stringResource(R.string.sync_unpair_confirm)) },
		confirmButton = {
			TextButton(onClick = {
				confirmUnpair = false
				message = null
				scope.launch { account.unpair() }
			}) { Text(stringResource(R.string.sync_unpair)) }
		},
		dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.action_cancel)) } },
	)
}

@Composable
private fun Info(label: String, value: String) {
	Column {
		Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(value)
	}
}

private fun format(utcMs: Long): String =
	DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(utcMs).atZone(ZoneId.systemDefault()))

/* the user's words for a failed pairing */
@Composable
private fun errorTexts(): (PairException) -> String {
	val texts = PairError.entries.associateWith {
		stringResource(
			when (it) {
				PairError.NOT_A_LINK -> R.string.sync_err_link
				PairError.VERSION -> R.string.sync_err_version
				PairError.NOT_HTTPS -> R.string.sync_err_https
				PairError.BAD_PIN -> R.string.sync_err_pin
				PairError.INVALID_CODE -> R.string.sync_err_code
				PairError.USER_DISABLED -> R.string.sync_err_user
				PairError.NETWORK -> R.string.sync_err_network
				PairError.CERTIFICATE -> R.string.sync_err_cert
				PairError.SERVER -> R.string.sync_err_server
			},
		)
	}
	return { e -> texts.getValue(e.error) + (e.message?.takeIf { m -> e.error in DETAILED && m != e.error.name }?.let { " ($it)" } ?: "") }
}

private val DETAILED = setOf(PairError.NETWORK, PairError.SERVER, PairError.CERTIFICATE)
