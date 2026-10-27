/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import it.infraconsulting.activitytrack.ui.common.Undo
import kotlinx.coroutines.flow.collectLatest
import it.infraconsulting.activitytrack.policy.PolicyLoader
import it.infraconsulting.activitytrack.ui.start.CallPrefill
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import it.infraconsulting.activitytrack.notify.EntryLinks
import kotlinx.coroutines.flow.map
import it.infraconsulting.activitytrack.ui.ActivityTrackTheme
import it.infraconsulting.activitytrack.ui.LocalBottomBar
import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.ui.common.LocalPolicy
import it.infraconsulting.activitytrack.ui.ScopedEntry
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.ui.TABS
import it.infraconsulting.activitytrack.ui.agenda.AgendaScreen
import it.infraconsulting.activitytrack.ui.entry.EntryScreen
import it.infraconsulting.activitytrack.ui.entry.QuickExpenseScreen
import it.infraconsulting.activitytrack.ui.home.HomeScreen
import it.infraconsulting.activitytrack.ui.home.LateEntryScreen
import it.infraconsulting.activitytrack.ui.home.MoreScreen
import it.infraconsulting.activitytrack.ui.masterdata.ClientScreen
import it.infraconsulting.activitytrack.ui.masterdata.ClientsScreen
import it.infraconsulting.activitytrack.ui.masterdata.CompaniesScreen
import it.infraconsulting.activitytrack.ui.masterdata.CompanyScreen
import it.infraconsulting.activitytrack.ui.masterdata.DaysOffScreen
import it.infraconsulting.activitytrack.ui.masterdata.MasterDataScreen
import it.infraconsulting.activitytrack.ui.masterdata.PlacesScreen
import it.infraconsulting.activitytrack.ui.masterdata.PresetsScreen
import it.infraconsulting.activitytrack.ui.masterdata.RolesScreen
import it.infraconsulting.activitytrack.ui.masterdata.ScheduleScreen
import it.infraconsulting.activitytrack.ui.rememberBackStack
import it.infraconsulting.activitytrack.ui.report.DayScreen
import it.infraconsulting.activitytrack.ui.report.ExportScreen
import it.infraconsulting.activitytrack.ui.settings.GesturesScreen
import it.infraconsulting.activitytrack.ui.settings.SettingsScreen
import it.infraconsulting.activitytrack.ui.settings.SettingsSection
import it.infraconsulting.activitytrack.ui.setup.SetupScreen
import it.infraconsulting.activitytrack.ui.start.StartScreen
import it.infraconsulting.activitytrack.ui.todo.TodosScreen

class MainActivity : ComponentActivity() {
	/* entry to open, from a notification (consumed by the navigator) */
	private val deepLink = mutableStateOf<Screen?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		if (savedInstanceState == null)
			deepLink.value = EntryLinks.screenOf(intent)
		val settingsRepo = (application as ActivityTrackApp).container.settings
		val compactFlow = settingsRepo.settings.map { it.compactDisplay }
		val learnedFlow = settingsRepo.settings.map { it.swipeLearned }
		/* the first swipe drops the cards' ‹ › for good (issue #77) */
		val hints = it.infraconsulting.activitytrack.ui.common.SwipeHints(show = true) {
			lifecycleScope.launch { settingsRepo.update { s -> if (s.swipeLearned) s else s.copy(swipeLearned = true) } }
		}
		setContent {
			val compact by compactFlow.collectAsState(initial = false)
			val learned by learnedFlow.collectAsState(initial = true)
			val policy by (application as ActivityTrackApp).container.policies.current.collectAsState()
			ActivityTrackTheme(compact) {
				androidx.compose.runtime.CompositionLocalProvider(
					it.infraconsulting.activitytrack.ui.common.LocalPolicy provides policy,
					it.infraconsulting.activitytrack.ui.common.LocalSwipeHints provides hints.copy(show = !learned),
				) {
					Root(deepLink.value) { deepLink.value = null }
				}
				it.infraconsulting.activitytrack.connect.plugin.PluginCloseDialog((application as ActivityTrackApp).pluginWatcher)
			}
		}
	}

	/*
	 * From API 34 the user can dismiss ongoing notifications; re-post them
	 * whenever the app comes to the foreground (ONLY_ALERT_ONCE keeps it silent).
	 */
	override fun onStart() {
		super.onStart()
		(application as ActivityTrackApp).notificationSync.refresh()
		/* a policy.json dropped while the app was running (issue #48) */
		(application as ActivityTrackApp).let { app -> app.appScope.launch { PolicyLoader.check(app) } }
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		EntryLinks.screenOf(intent)?.let { deepLink.value = it }
	}
}

/* The data folder must be chosen (and still accessible) before anything else. */
@Composable
private fun Root(deepLink: Screen?, consumeDeepLink: () -> Unit) {
	val container = (LocalContext.current.applicationContext as ActivityTrackApp).container
	val settings by container.settings.settings.collectAsState(initial = null)
	val s = settings ?: return
	val root = s.rootUri
	/* bumped after a choice: re-picking the same folder leaves rootUri unchanged */
	var epoch by remember { mutableIntStateOf(0) }
	val accessible = remember(root, epoch) { root != null && container.userDataDir.isAccessible(root.toUri()) }
	if (!accessible)
		SetupScreen(lost = root != null, onChosen = { epoch++ })
	else
		Navigator(deepLink, consumeDeepLink)
}

@Composable
private fun Navigator(deepLink: Screen?, consumeDeepLink: () -> Unit) {
	val stack = rememberBackStack()
	val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
	val open: (Screen) -> Unit = { stack.add(it) }
	/* to another tab's root, as the bottom bar does */
	val selectTab: (Screen) -> Unit = { stack.clear(); stack.add(it) }
	LaunchedEffect(deepLink) {
		if (deepLink != null) {
			if (stack.last() != deepLink)
				stack.add(deepLink)
			consumeDeepLink()
		}
	}
	/* from another tab's root, back goes home before leaving the app */
	val atOtherRoot = stack.size == 1 && stack.last() != Screen.Home
	BackHandler(enabled = stack.size > 1 || atOtherRoot) {
		if (atOtherRoot) stack[0] = Screen.Home else back()
	}
	RequestRuntimePermissions()

	val top = stack.last()
	val tab = top in TABS && stack.size == 1
	Column(Modifier.fillMaxSize()) {
		Box(Modifier.weight(1f)) {
			CompositionLocalProvider(LocalBottomBar provides tab) {
				ScopedEntry(stack) { t -> Destination(t, back, open, selectTab) }
			}
			UndoHost(Modifier.align(Alignment.BottomCenter).then(if (tab) Modifier else Modifier.navigationBarsPadding()))
		}
		if (tab)
			BottomBar(top, selectTab)
	}
}

/* The "Annulla" snackbar of every screen (issue #97), above the bottom bar. */
@Composable
private fun UndoHost(modifier: Modifier) {
	val host = remember { SnackbarHostState() }
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	LaunchedEffect(Unit) {
		Undo.events.collectLatest { e ->
			val result = host.showSnackbar(
				message = context.getString(e.message, e.arg),
				actionLabel = context.getString(R.string.action_undo),
				duration = SnackbarDuration.Short,
			)
			if (result == SnackbarResult.ActionPerformed)
				scope.launch { e.undo() }
		}
	}
	SnackbarHost(host, modifier.padding(bottom = 8.dp))
}

@Composable
private fun BottomBar(current: Screen, onSelect: (Screen) -> Unit) {
	val policy = LocalPolicy.current
	NavigationBar {
		for ((screen, icon, label) in listOfNotNull(
			Triple(Screen.Home, R.drawable.ic_nav_home, R.string.nav_home),
			Triple(Screen.Todos, R.drawable.ic_menu_todo, R.string.todo_title)
				.takeIf { policy.enabled(Feature.TODOS) },
			Triple(Screen.Agenda, R.drawable.ic_nav_agenda, R.string.nav_agenda),
			Triple(Screen.Day, R.drawable.ic_nav_report, R.string.nav_report),
			Triple(Screen.More, R.drawable.ic_nav_more, R.string.nav_more),
		))
			NavigationBarItem(
				selected = current == screen,
				onClick = { if (current != screen) onSelect(screen) },
				icon = { Icon(painterResource(icon), contentDescription = null) },
				label = { Text(stringResource(label)) },
			)
	}
}

/*
 * Notifications (runtime from API 33) and location while in use, asked once
 * per launch while missing; the system shows the dialogs one after the
 * other. "Allow all the time" is a separate step in Settings.
 */
@Composable
private fun RequestRuntimePermissions() {
	val context = LocalContext.current
	val app = context.applicationContext as ActivityTrackApp
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
		if (result.values.any { it }) app.notificationSync.refresh()
	}
	LaunchedEffect(Unit) {
		val missing = listOf(
			Manifest.permission.POST_NOTIFICATIONS,
			Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
		if (missing.isNotEmpty())
			launcher.launch(missing.toTypedArray())
	}
}

@Composable
private fun Destination(top: Screen, back: () -> Unit, open: (Screen) -> Unit, selectTab: (Screen) -> Unit) {
	when (top) {
		Screen.Home -> HomeScreen(
			onStart = { open(Screen.Start) },
			onStartTravel = { open(Screen.StartTravel) },
			onOpenEntry = { open(Screen.Entry(it)) },
			onStartTodo = { open(Screen.StartFromTodo(it)) },
			onOpenGestures = { open(Screen.Gestures) },
			onOpenTodos = { selectTab(Screen.Todos) },
			onOpenImport = { open(Screen.Export) },
		)
		Screen.Agenda -> AgendaScreen(onOpenEntry = { open(Screen.Entry(it)) }, onStartTodo = { open(Screen.StartFromTodo(it)) })
		Screen.More -> MoreScreen(open)
		Screen.Start -> StartScreen(back)
		Screen.StartTravel -> StartScreen(back, travel = true)
		is Screen.StartFor -> StartScreen(back, top.clientId)
		is Screen.StartFromTodo -> StartScreen(back, todoId = top.todoId)
		is Screen.StartFromCall -> StartScreen(back, top.clientId, call = CallPrefill(top.startMs, top.endMs, top.contact))
		Screen.Contacts -> it.infraconsulting.activitytrack.ui.masterdata.ContactsScreen(back)
		Screen.Materials -> it.infraconsulting.activitytrack.ui.masterdata.MaterialsScreen(back)
		/* a tab root, or opened from a notification above another screen */
		Screen.Todos -> TodosScreen(back.takeUnless { LocalBottomBar.current }) { open(Screen.StartFromTodo(it)) }
		is Screen.Todo -> TodosScreen(back, editId = top.id) { open(Screen.StartFromTodo(it)) }
		is Screen.Entry -> EntryScreen(top.id, back)
		Screen.Day -> DayScreen(null, onOpenEntry = { open(Screen.Entry(it)) }, onExport = { open(Screen.Export) })
		Screen.Export -> ExportScreen(back)
		Screen.Settings -> SettingsScreen(back, onOpen = open)
		is Screen.SettingsPage -> SettingsScreen(back, SettingsSection.entries.firstOrNull { it.name == top.name } ?: SettingsSection.DISPLAY)
		Screen.Gestures -> GesturesScreen(back)
		Screen.MasterData -> MasterDataScreen(back, open)
		Screen.Companies -> CompaniesScreen(back) { open(Screen.Company(it)) }
		is Screen.Company -> CompanyScreen(top.id, back)
		Screen.Clients -> ClientsScreen(back) { open(Screen.Client(it)) }
		is Screen.Client -> ClientScreen(top.id, back)
		Screen.Roles -> RolesScreen(back)
		Screen.Presets -> PresetsScreen(back)
		Screen.Places -> PlacesScreen(back)
		Screen.Schedule -> ScheduleScreen(back)
		Screen.DaysOff -> DaysOffScreen(back)
		Screen.QuickExpense -> QuickExpenseScreen(back)
		is Screen.LateEntry -> LateEntryScreen(top.epochDay, back)
	}
}
