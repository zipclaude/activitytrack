/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * "Annulla" after an action that is easy to make by mistake (issue #97): a
 * swipe or a tap marks a to-do done, ignores, postpones or un-plans it. The
 * screen that did it posts here; the snackbar host above the bottom bar
 * (MainActivity) shows the message and runs [undo] on "Annulla".
 */
class UndoEvent(@StringRes val message: Int, val arg: String, val undo: suspend () -> Unit)

object Undo {
	private val _events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
	val events = _events.asSharedFlow()

	fun post(event: UndoEvent) {
		_events.tryEmit(event)
	}
}
