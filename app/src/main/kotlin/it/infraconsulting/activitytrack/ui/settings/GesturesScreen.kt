/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.domain.settings.EntrySwipe
import it.infraconsulting.activitytrack.domain.settings.GestureSettings
import it.infraconsulting.activitytrack.domain.settings.TodoSwipe
import it.infraconsulting.activitytrack.ui.appViewModel
import it.infraconsulting.activitytrack.ui.common.ChoiceField
import it.infraconsulting.activitytrack.ui.common.ScreenScaffold
import it.infraconsulting.activitytrack.ui.common.SectionHeader
import it.infraconsulting.activitytrack.ui.common.SwitchRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GesturesViewModel(private val repo: SettingsRepository) : ViewModel() {
	val gestures = repo.settings.map { it.gestures }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	/* each choice is saved at once: there is no form to confirm */
	fun set(transform: (GestureSettings) -> GestureSettings) {
		viewModelScope.launch { repo.update { it.copy(gestures = transform(it.gestures)) } }
	}

	/* the Home hint and the cards' ‹ › again (issue #77) */
	fun showHints() {
		viewModelScope.launch { repo.update { it.copy(gestureHintSeen = false, swipeLearned = false) } }
	}
}

/*
 * Swipe gestures (M10, owner decisions 2026-10-18): what a swipe to the
 * left / right offers on running activities and on to-dos, and whether a
 * long swipe runs the first action at once.
 */
@Composable
fun GesturesScreen(onBack: () -> Unit) {
	val vm = appViewModel { GesturesViewModel(it.settings) }
	val g = vm.gestures.collectAsState().value
	ScreenScaffold(title = stringResource(R.string.gestures_title), onBack = onBack) { padding ->
		if (g == null)
			return@ScreenScaffold
		Column(
			modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			val entryOptions = EntrySwipe.entries.map { it to stringResource(entryLabel(it)) }
			val todoOptions = TodoSwipe.entries.map { it to stringResource(todoLabel(it)) }

			SectionHeader(stringResource(R.string.gestures_entries))
			ChoiceField(stringResource(R.string.gestures_to_start), entryOptions, g.entryToStart) { v -> vm.set { it.copy(entryToStart = v) } }
			ChoiceField(stringResource(R.string.gestures_to_end), entryOptions, g.entryToEnd) { v -> vm.set { it.copy(entryToEnd = v) } }

			SectionHeader(stringResource(R.string.gestures_todos))
			ChoiceField(stringResource(R.string.gestures_to_start), todoOptions, g.todoToStart) { v -> vm.set { it.copy(todoToStart = v) } }
			ChoiceField(stringResource(R.string.gestures_to_end), todoOptions, g.todoToEnd) { v -> vm.set { it.copy(todoToEnd = v) } }

			SectionHeader(stringResource(R.string.gestures_behaviour))
			SwitchRow(stringResource(R.string.gestures_full), g.fullSwipe) { v -> vm.set { it.copy(fullSwipe = v) } }
			TextButton(onClick = vm::showHints) { Text(stringResource(R.string.gesture_hints_again)) }
		}
	}
}

private fun entryLabel(s: EntrySwipe): Int = when (s) {
	EntrySwipe.PAUSE_RESUME -> R.string.swipe_pause_resume
	EntrySwipe.STOP_OR_SUSPEND -> R.string.swipe_stop_or_suspend
	EntrySwipe.STOP -> R.string.cmd_stop
	EntrySwipe.SUSPEND -> R.string.cmd_suspend
	EntrySwipe.NONE -> R.string.swipe_none
}

private fun todoLabel(s: TodoSwipe): Int = when (s) {
	TodoSwipe.START -> R.string.swipe_start
	TodoSwipe.POSTPONE_OR_IGNORE -> R.string.swipe_postpone_or_ignore
	TodoSwipe.POSTPONE -> R.string.swipe_postpone
	TodoSwipe.IGNORE -> R.string.swipe_ignore
	TodoSwipe.DONE -> R.string.todo_mark_done
	TodoSwipe.REPLAN -> R.string.action_replan
	TodoSwipe.NONE -> R.string.swipe_none
}
