/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import it.infraconsulting.activitytrack.R
import java.util.Locale

/*
 * Multi-line text field with dictation (M3, DESIGN §9): the system speech
 * recognizer (offline when the language pack is installed) appends what was
 * said. Without a recognizer the microphone is not shown. Free offline STT
 * (Vosk / whisper.cpp) is M4.
 */
@Composable
fun DictationInput(label: String, value: String, onChange: (String) -> Unit) {
	val context = LocalContext.current
	val intent = remember {
		Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
			.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
			.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
			.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
	}
	val available = remember { intent.resolveActivity(context.packageManager) != null }
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
		val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
		if (r.resultCode == Activity.RESULT_OK && !said.isNullOrBlank())
			onChange(appendDictation(value, said))
	}
	TextInput(
		label = label,
		value = value,
		onChange = onChange,
		singleLine = false,
		trailing = if (!available) null else {
			{
				IconButton(onClick = { launcher.launch(intent.putExtra(RecognizerIntent.EXTRA_PROMPT, label)) }) {
					Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.action_dictate))
				}
			}
		},
	)
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
