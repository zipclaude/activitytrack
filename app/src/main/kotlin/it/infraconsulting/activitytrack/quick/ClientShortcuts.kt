/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.quick

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.MainActivity
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.ui.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * Dynamic launcher shortcuts (DESIGN §10): the 4 clients used most recently.
 * A shortcut opens the start screen with the client picked (owner decision
 * 2026-10-17: nothing starts outside the app, where dialogs can be shown).
 */
class ClientShortcuts(private val context: Context, private val container: AppContainer) {
	fun start(scope: CoroutineScope) {
		scope.launch {
			combine(container.masterData.clients, container.entries.clientLastUse) { clients, uses ->
				val last = uses.associate { it.clientId to it.lastMs }
				clients.filter { !it.archived && it.id in last }
					.sortedByDescending { last[it.id] }
					.take(MAX)
					.map { it.id to it.name }
			}.distinctUntilChanged().conflate().collect { publish(it) }
		}
	}

	private fun publish(clients: List<Pair<Long, String>>) {
		val icon = IconCompat.createWithResource(context, R.drawable.ic_shortcut_start)
		val shortcuts = clients.mapIndexed { rank, (id, name) ->
			ShortcutInfoCompat.Builder(context, "client-$id")
				.setShortLabel(name)
				.setIcon(icon)
				.setRank(rank)
				.setIntent(
					Intent(context, MainActivity::class.java)
						.setAction(Intent.ACTION_VIEW)
						.putExtra(EntryLinks.EXTRA_SCREEN, Screen.StartFor(id).key()),
				)
				.build()
		}
		ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
	}

	private companion object {
		const val MAX = 4
	}
}
