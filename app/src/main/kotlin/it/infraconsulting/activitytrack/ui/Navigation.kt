/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/*
 * Destinations. The app owns its back stack (a state list), the same model
 * as Navigation 3, without the extra dependency while the graph is small.
 */
sealed interface Screen {
	data object Home : Screen
	data object Settings : Screen
	data object MasterData : Screen
	data object Companies : Screen
	data class Company(val id: Long) : Screen
	data object Clients : Screen
	data class Client(val id: Long) : Screen
	data object Roles : Screen
	data object Presets : Screen
	data object Places : Screen
	data object Schedule : Screen

	/* Stable string form, used to save the back stack across process death. */
	fun key(): String = when (this) {
		is Company -> "company/$id"
		is Client -> "client/$id"
		else -> this::class.simpleName!!
	}

	companion object {
		private val objects = listOf(Home, Settings, MasterData, Companies, Clients, Roles, Presets, Places, Schedule)
			.associateBy { it.key() }

		fun parse(key: String): Screen? {
			objects[key]?.let { return it }
			val (kind, id) = key.split('/').takeIf { it.size == 2 } ?: return null
			val n = id.toLongOrNull() ?: return null
			return when (kind) {
				"company" -> Company(n)
				"client" -> Client(n)
				else -> null
			}
		}
	}
}

private val BackStackSaver = Saver<SnapshotStateList<Screen>, List<String>>(
	save = { stack -> stack.map { it.key() } },
	restore = { keys -> keys.mapNotNull(Screen::parse).ifEmpty { listOf(Screen.Home) }.toMutableStateList() },
)

@Composable
fun rememberBackStack(): SnapshotStateList<Screen> =
	rememberSaveable(saver = BackStackSaver) { listOf<Screen>(Screen.Home).toMutableStateList() }
