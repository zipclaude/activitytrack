/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.domain.model.IllegalTransitionException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * ViewModel built from the application container (manual DI), stored in the
 * current back-stack entry. The container comes from the context because
 * per-entry owners provide no CreationExtras.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
	key: String? = null,
	crossinline create: (AppContainer) -> VM,
): VM {
	val container = (LocalContext.current.applicationContext as ActivityTrackApp).container
	return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/*
 * Base for screens that write through repositories: runs an action, exposes
 * the validation failure (if any) and reports success to the caller, which
 * typically closes its dialog.
 */
abstract class ActionViewModel : ViewModel() {
	private val _error = MutableStateFlow<ValidationException.Reason?>(null)
	val error: StateFlow<ValidationException.Reason?> = _error.asStateFlow()

	fun clearError() {
		_error.value = null
	}

	protected fun run(onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
		viewModelScope.launch {
			try {
				action()
				_error.value = null
				onSuccess()
			} catch (e: ValidationException) {
				_error.value = e.reason
			} catch (e: IllegalTransitionException) {
				/* a repeated tap raced the list update: the state already changed */
			}
		}
	}

	protected fun <T> Flow<T>.state(initial: T): StateFlow<T> =
		stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}
