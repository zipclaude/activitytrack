/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import android.content.Context
import androidx.annotation.StringRes
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

/*
 * An earlier activity's end moved back to where another one started
 * (ACTIVITY-MODEL.md 4.1): the notice's text. Shown by the app-wide snackbar
 * host, wherever the start happened (Home, a widget, the Start screen).
 */
fun pulledBackNotices(context: Context, entries: EntryRepository, master: MasterDataRepository): Flow<String> =
	entries.pulledBack.map { list ->
		val first = list.first()
		val e = entries.find(first.entryId)
		val zone = e?.zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
		val at = Instant.ofEpochMilli(first.newEndUtcMs).atZone(zone).toLocalTime()
		val title = e?.clientId?.let { id -> master.clients.first().firstOrNull { it.id == id }?.name } ?: e?.activity
		if (list.size > 1) context.getString(R.string.home_pulled_back_many, list.size)
		else context.getString(R.string.home_pulled_back, title ?: context.getString(R.string.entry_no_client), formatMin(at.hour * 60 + at.minute))
	}
