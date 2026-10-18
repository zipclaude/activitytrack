/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/*
 * Destinations. The app owns its back stack (a state list), the same model
 * as Navigation 3, without the extra dependency while the graph is small.
 */
sealed interface Screen {
	data object Home : Screen
	/* consultation (M6): list, day, week, month */
	data object Agenda : Screen
	/* secondary destinations behind the bottom bar */
	data object More : Screen
	data object Start : Screen
	/* start flow with Travel picked (Home bar, while other entries run) */
	data object StartTravel : Screen
	/* start flow with a client already picked (widget, shortcut) */
	data class StartFor(val clientId: Long) : Screen
	data class Entry(val id: Long) : Screen
	data object Day : Screen
	data object Export : Screen
	data object Settings : Screen
	/* one settings section (backlog 2): [name] is a SettingsSection */
	data class SettingsPage(val name: String) : Screen
	data object MasterData : Screen
	data object Companies : Screen
	data class Company(val id: Long) : Screen
	data object Clients : Screen
	data class Client(val id: Long) : Screen
	data object Roles : Screen
	data object Presets : Screen
	data object Places : Screen
	data object Schedule : Screen
	data object DaysOff : Screen
	/* expense for the most recent open entry (widget, shortcut) */
	data object QuickExpense : Screen
	data object Todos : Screen
	/* swipe gestures (M10) */
	data object Gestures : Screen
	/* start flow pre-filled from a to-do */
	data class StartFromTodo(val todoId: Long) : Screen
	/* to-dos with one open for editing (re-plan from the reminder) */
	data class Todo(val id: Long) : Screen
	/* late entry for a day, from the day-start prompt */
	data class LateEntry(val epochDay: Long) : Screen

	/* Stable string form, used to save the back stack across process death. */
	fun key(): String = when (this) {
		is Entry -> "entry/$id"
		is Company -> "company/$id"
		is Client -> "client/$id"
		is LateEntry -> "late/$epochDay"
		is StartFor -> "start/$clientId"
		is StartFromTodo -> "start-todo/$todoId"
		is Todo -> "todo/$id"
		is SettingsPage -> "settings/$name"
		else -> this::class.simpleName!!
	}

	companion object {
		/* lazy: an eager map would read the data objects during Screen's own class init (null cycle) */
		private val objects by lazy {
			listOf(Home, Agenda, More, Start, StartTravel, Day, Export, Settings, MasterData, Companies, Clients, Roles, Presets, Places, Schedule, DaysOff, QuickExpense, Todos, Gestures)
				.associateBy { it.key() }
		}

		fun parse(key: String): Screen? {
			objects[key]?.let { return it }
			val (kind, id) = key.split('/').takeIf { it.size == 2 } ?: return null
		if (kind == "settings")
			return SettingsPage(id)
			val n = id.toLongOrNull() ?: return null
			return when (kind) {
				"entry" -> Entry(n)
				"company" -> Company(n)
				"client" -> Client(n)
				"late" -> LateEntry(n)
				"start" -> StartFor(n)
				"start-todo" -> StartFromTodo(n)
				"todo" -> Todo(n)
				else -> null
			}
		}
	}
}

private val BackStackSaver = Saver<SnapshotStateList<Screen>, List<String>>(
	save = { stack -> stack.map { it.key() } },
	restore = { keys -> keys.mapNotNull(Screen::parse).ifEmpty { listOf(Screen.Home) }.toMutableStateList() },
)

/*
 * One ViewModelStore per back-stack position, so a screen's ViewModels are
 * created fresh when it is opened and cleared when it is popped. Held in an
 * Activity-scoped ViewModel: the stores survive configuration changes.
 */
class EntryStores : ViewModel() {
	private val stores = HashMap<String, ViewModelStore>()

	fun owner(id: String): ViewModelStoreOwner {
		val store = stores.getOrPut(id) { ViewModelStore() }
		return object : ViewModelStoreOwner {
			override val viewModelStore = store
		}
	}

	/* Clears the stores of entries no longer on the stack. */
	fun retain(ids: Set<String>) {
		val gone = stores.keys - ids
		gone.forEach { stores.remove(it)?.clear() }
	}

	override fun onCleared() {
		stores.values.forEach { it.clear() }
		stores.clear()
	}
}

/* Identity of a back-stack entry: its position and destination. */
fun entryId(index: Int, screen: Screen) = "$index:${screen.key()}"

/* Renders [content] with the ViewModelStoreOwner of the top entry. */
@Composable
fun ScopedEntry(stack: List<Screen>, content: @Composable (Screen) -> Unit) {
	val stores: EntryStores = viewModel()
	val ids = stack.mapIndexed(::entryId)
	SideEffect { stores.retain(ids.toSet()) }
	val owner = remember(ids.last()) { stores.owner(ids.last()) }
	CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
		content(stack.last())
	}
}

@Composable
fun rememberBackStack(): SnapshotStateList<Screen> =
	rememberSaveable(saver = BackStackSaver) { listOf<Screen>(Screen.Home).toMutableStateList() }

/* Bottom navigation (M6/M7, owner decision 2026-10-17): roots shown with the bar. */
val TABS: List<Screen> by lazy { listOf(Screen.Home, Screen.Agenda, Screen.Day, Screen.More) }

/* Inside a tab root the bottom bar owns the navigation-bar inset. */
val LocalBottomBar = staticCompositionLocalOf { false }

