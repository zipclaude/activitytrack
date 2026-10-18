/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.setup

import android.provider.DocumentsContract
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* Persists the chosen SAF tree, creates its layout, releases the previous one. */
class DataDirViewModel(
	private val dir: UserDataDir,
	private val settings: SettingsRepository,
) : ViewModel() {
	private val _failed = MutableStateFlow(false)
	val failed = _failed.asStateFlow()

	fun choose(tree: Uri, onDone: () -> Unit = {}) {
		viewModelScope.launch {
			try {
				dir.persist(tree)
				dir.ensureLayout(tree)
				val old = settings.current().rootUri
				settings.update { it.copy(rootUri = tree.toString()) }
				if (old != null && old != tree.toString())
					dir.release(old.toUri())
				_failed.value = false
				onDone()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				/* SecurityException (no persistable grant) or provider failure */
				dir.release(tree)
				_failed.value = true
			}
		}
	}
}

/* Launcher for ACTION_OPEN_DOCUMENT_TREE bound to [vm]. */
@Composable
fun rememberDataDirPicker(vm: DataDirViewModel, onDone: () -> Unit = {}): () -> Unit {
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
		if (uri != null) vm.choose(uri, onDone)
	}
	/* opens in the root of the main storage, where the suggested folder goes (owner, 2026-10-18) */
	return { launcher.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:")) }
}

/* First run, or access to the data directory lost: the folder must be chosen. */
@Composable
fun SetupScreen(lost: Boolean, onChosen: () -> Unit) {
	val vm = appViewModel { DataDirViewModel(it.userDataDir, it.settings) }
	val failed by vm.failed.collectAsState()
	val pick = rememberDataDirPicker(vm, onChosen)

	ScreenScaffold(title = stringResource(R.string.setup_title), onBack = null) { padding ->
		Column(
			modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			if (lost)
				Text(stringResource(R.string.setup_lost), color = MaterialTheme.colorScheme.error)
			Text(stringResource(R.string.setup_text, stringResource(R.string.data_folder_hint)))
			Button(onClick = pick) { Text(stringResource(R.string.setup_choose)) }
			if (failed)
				Text(stringResource(R.string.setup_error), color = MaterialTheme.colorScheme.error)
		}
	}
}
