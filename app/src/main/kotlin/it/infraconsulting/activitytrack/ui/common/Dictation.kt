/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import it.infraconsulting.activitytrack.R
import java.util.Locale

/*
 * Multi-line text field with dictation (M3, DESIGN §9). SpeechRecognizer with
 * our own dialog: the RECOGNIZE_SPEECH activity is not usable on every device
 * (some devices answer "Voice search isn't available" without the
 * Google app) while recognition services are. On-device recognition first,
 * the default service otherwise. Free offline STT (Vosk / whisper.cpp) is M4.
 */
@Composable
fun DictationInput(label: String, value: String, onChange: (String) -> Unit) {
	val context = LocalContext.current
	val available = remember {
		SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)
	}
	var listening by remember { mutableStateOf(false) }
	val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted)
			listening = true
	}
	TextInput(
		label = label,
		value = value,
		onChange = onChange,
		singleLine = false,
		trailing = if (!available) null else {
			{
				IconButton(onClick = {
					if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
						listening = true
					else
						askMic.launch(Manifest.permission.RECORD_AUDIO)
				}) {
					Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.action_dictate))
				}
			}
		},
	)
	if (listening)
		DictationDialog(
			onText = { said -> listening = false; onChange(appendDictation(value, said)) },
			onCancel = { listening = false },
		)
}

@Composable
private fun DictationDialog(onText: (String) -> Unit, onCancel: () -> Unit) {
	val context = LocalContext.current
	var partial by remember { mutableStateOf("") }
	var error by remember { mutableStateOf<Int?>(null) }
	/* on-device first; the default service once on-device fails for the language */
	var onDevice by remember { mutableStateOf(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) }
	var session by remember { mutableIntStateOf(0) }
	val holder = remember { arrayOfNulls<SpeechRecognizer>(1) }

	/* a new recognizer per session (start, retry, fallback) */
	DisposableEffect(session) {
		val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
		holder[0] = r
		r.setRecognitionListener(object : RecognitionListener {
			override fun onReadyForSpeech(params: Bundle?) {}
			override fun onBeginningOfSpeech() {}
			override fun onRmsChanged(rmsdB: Float) {}
			override fun onBufferReceived(buffer: ByteArray?) {}
			override fun onEndOfSpeech() {}
			override fun onEvent(eventType: Int, params: Bundle?) {}

			override fun onPartialResults(partialResults: Bundle?) {
				firstResult(partialResults)?.let { partial = it }
			}

			override fun onResults(results: Bundle?) {
				val said = firstResult(results) ?: partial
				if (said.isBlank()) error = SpeechRecognizer.ERROR_NO_MATCH else onText(said)
			}

			override fun onError(code: Int) {
				val missingLanguage = code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
					code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
				if (onDevice && (missingLanguage || code == SpeechRecognizer.ERROR_CLIENT) && SpeechRecognizer.isRecognitionAvailable(context)) {
					onDevice = false
					session++
				} else
					error = code
			}
		})
		r.startListening(recognizerIntent(context))
		onDispose {
			r.cancel()
			r.destroy()
			holder[0] = null
		}
	}

	AlertDialog(
		onDismissRequest = onCancel,
		title = { Text(stringResource(R.string.action_dictate)) },
		text = {
			when {
				error != null -> Text(stringResource(errorText(error!!)), color = MaterialTheme.colorScheme.error)
				partial.isNotBlank() -> Text(partial)
				else -> Text(stringResource(R.string.dictation_listening))
			}
		},
		confirmButton = {
			if (error == null)
				TextButton(onClick = { holder[0]?.stopListening() }) { Text(stringResource(R.string.dictation_done)) }
			else
				TextButton(onClick = { error = null; partial = ""; session++ }) {
					Text(stringResource(R.string.dictation_retry))
				}
		},
		dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
	)
}

private fun recognizerIntent(context: Context): Intent =
	Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
		.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
		.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
		.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
		.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
		.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

private fun firstResult(b: Bundle?): String? =
	b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

private fun errorText(code: Int): Int = when (code) {
	SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.dictation_no_match
	SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> R.string.dictation_network
	SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.dictation_language
	else -> R.string.dictation_failed
}

/* Dictated text goes after what is already there, as a new sentence. */
fun appendDictation(current: String, said: String): String {
	val text = said.trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }
	val base = current.trimEnd()
	return when {
		base.isEmpty() -> text
		base.last() in ".!?" -> "$base $text"
		else -> "$base. $text"
	}
}
