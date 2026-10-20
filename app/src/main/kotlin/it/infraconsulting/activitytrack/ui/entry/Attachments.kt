/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.ui.common.INDIGO
import it.infraconsulting.activitytrack.ui.common.TEAL
import it.infraconsulting.activitytrack.ui.common.PURPLE
import it.infraconsulting.activitytrack.ui.common.ColoredActionButton
import it.infraconsulting.activitytrack.data.db.AttachmentEntity
import it.infraconsulting.activitytrack.data.db.AttachmentKind
import it.infraconsulting.activitytrack.data.repo.AttachmentRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ClickableRow
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.clockOf
import it.infraconsulting.activitytrack.ui.common.reasonText
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.delay

/* Attachments of one entry (M3): photo, voice note, file; stored in the user data folder. */
class AttachmentsViewModel(
	private val entryId: Long,
	private val repo: AttachmentRepository,
	private val settings: SettingsRepository,
) : ActionViewModel() {
	val items = repo.observe(entryId).state(emptyList())

	private suspend fun tree(): Uri = settings.current().rootUri?.toUri() ?: throw StorageFailure()

	/* Copies [source] into the folder; [cleanup] runs in any case (cache captures). */
	fun add(kind: AttachmentKind, source: Uri, mime: String, ext: String, cleanup: () -> Unit = {}) = run {
		try {
			storage { repo.add(tree(), entryId, kind, source, mime, ext) }
		} finally {
			cleanup()
		}
	}

	fun delete(a: AttachmentEntity) = run { storage { repo.delete(tree(), a) } }

	fun open(context: Context, a: AttachmentEntity) = run {
		val uri = storage { repo.uriOf(tree(), a) } ?: throw ValidationException(Reason.NOT_FOUND)
		try {
			context.startActivity(
				Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
			)
		} catch (e: ActivityNotFoundException) {
			throw ValidationException(Reason.NO_VIEWER)
		}
	}

	private class StorageFailure : Exception()

	/* I/O on the data folder (moved, full, revoked) is reported, not thrown at the UI. */
	private suspend fun <T> storage(block: suspend () -> T): T =
		try {
			block()
		} catch (e: ValidationException) {
			throw e
		} catch (e: Exception) {
			throw ValidationException(Reason.STORAGE, e.message)
		}
}

@Composable
fun AttachmentsSection(entryId: Long, zone: ZoneId, request: AddRequest? = null, onHandled: () -> Unit = {}) {
	val vm = appViewModel(key = "attachments/$entryId") { AttachmentsViewModel(entryId, it.attachments, it.settings) }
	val items by vm.items.collectAsState()
	val error by vm.error.collectAsState()
	val context = LocalContext.current
	var selected by remember { mutableStateOf<AttachmentEntity?>(null) }
	var recording by remember { mutableStateOf(false) }

	/* camera writes into the app cache through FileProvider, then the file is copied */
	var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
	val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
		val f = pendingPhoto?.let(::File) ?: return@rememberLauncherForActivityResult
		pendingPhoto = null
		if (ok)
			vm.add(AttachmentKind.PHOTO, Uri.fromFile(f), "image/jpeg", "jpg") { f.delete() }
		else
			f.delete()
	}
	val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri ?: return@rememberLauncherForActivityResult
		val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
		val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
		vm.add(AttachmentKind.FILE, uri, mime, ext)
	}
	val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted)
			recording = true
	}

	/* photo, voice note, file from the add row at the top of the screen (issue #37) */
	androidx.compose.runtime.LaunchedEffect(request) {
		when (request) {
			AddRequest.PHOTO -> {
				vm.clearError()
				val f = captureFile(context, "jpg")
				pendingPhoto = f.path
				takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
			}
			AddRequest.VOICE -> {
				vm.clearError()
				if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
					recording = true
				else
					askMic.launch(Manifest.permission.RECORD_AUDIO)
			}
			AddRequest.FILE -> {
				vm.clearError()
				pickFile.launch(arrayOf("*/*"))
			}
			else -> return@LaunchedEffect
		}
		onHandled()
	}

	if (items.isNotEmpty() || error != null)
		Column(modifier = Modifier.padding(bottom = 8.dp)) {
			SectionHeader(stringResource(R.string.attachments))
			for (a in items)
				ClickableRow(kindLabel(a.kind), clockOf(a.createdUtcMs, zone) + " · " + dateOf(a.createdUtcMs, zone)) {
					vm.clearError()
					selected = a
				}
			error?.let { Text(reasonText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
		}

	selected?.let { a ->
		AlertDialog(
			onDismissRequest = { selected = null },
			title = { Text(kindLabel(a.kind)) },
			confirmButton = { TextButton(onClick = { vm.open(context, a); selected = null }) { Text(stringResource(R.string.action_open)) } },
			dismissButton = {
				TextButton(onClick = { vm.delete(a); selected = null }) {
					Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
				}
			},
		)
	}
	if (recording)
		VoiceRecorderDialog(
			onDone = { f -> recording = false; vm.add(AttachmentKind.AUDIO, Uri.fromFile(f), "audio/mp4", "m4a") { f.delete() } },
			onCancel = { recording = false },
		)
}

/*
 * Voice note: AAC in MPEG-4 (.m4a) into the cache, no length limit, explicit
 * stop (owner decision 2026-10-17). Leaving the dialog discards the take.
 */
@Composable
private fun VoiceRecorderDialog(onDone: (File) -> Unit, onCancel: () -> Unit) {
	val context = LocalContext.current
	val file = remember { captureFile(context, "m4a") }
	var startedMs by remember { mutableLongStateOf(0L) }
	var elapsed by remember { mutableLongStateOf(0L) }
	var saved by remember { mutableStateOf(false) }
	val recorder = remember {
		runCatching {
			MediaRecorder(context).apply {
				setAudioSource(MediaRecorder.AudioSource.MIC)
				setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
				setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
				setAudioEncodingBitRate(64_000)
				setAudioSamplingRate(44_100)
				setOutputFile(file.path)
				prepare()
				start()
			}
		}.getOrNull()
	}
	DisposableEffect(Unit) {
		startedMs = System.currentTimeMillis()
		onDispose {
			runCatching { recorder?.stop() }
			recorder?.release()
			if (!saved)
				file.delete()
		}
	}
	LaunchedEffect(Unit) {
		while (true) {
			elapsed = System.currentTimeMillis() - startedMs
			delay(500)
		}
	}
	AlertDialog(
		onDismissRequest = onCancel,
		title = { Text(stringResource(R.string.att_add_voice)) },
		text = {
			Text(
				if (recorder == null) stringResource(R.string.att_record_failed)
				else stringResource(R.string.att_recording, "%d:%02d".format(elapsed / 60_000, elapsed / 1000 % 60)),
			)
		},
		confirmButton = {
			if (recorder != null)
				TextButton(onClick = {
					runCatching { recorder.stop() }
					saved = true
					onDone(file)
				}) { Text(stringResource(R.string.att_stop_save)) }
		},
		dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
	)
}

private fun captureFile(context: Context, ext: String): File =
	File(context.cacheDir, "captures").apply { mkdirs() }.let { File(it, "${UUID.randomUUID()}.$ext") }

private fun dateOf(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

@Composable
private fun kindLabel(kind: AttachmentKind) = stringResource(
	when (kind) {
		AttachmentKind.PHOTO -> R.string.att_photo
		AttachmentKind.AUDIO -> R.string.att_audio
		AttachmentKind.RECEIPT -> R.string.att_receipt
		AttachmentKind.FILE -> R.string.att_file
	},
)
