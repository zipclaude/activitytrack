/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.ui.ActivityTrackTheme
import it.infraconsulting.activitytrack.ui.ScopedEntry
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.ui.entry.EntryScreen
import it.infraconsulting.activitytrack.ui.home.HomeScreen
import it.infraconsulting.activitytrack.ui.masterdata.ClientScreen
import it.infraconsulting.activitytrack.ui.masterdata.ClientsScreen
import it.infraconsulting.activitytrack.ui.masterdata.CompaniesScreen
import it.infraconsulting.activitytrack.ui.masterdata.CompanyScreen
import it.infraconsulting.activitytrack.ui.masterdata.MasterDataScreen
import it.infraconsulting.activitytrack.ui.masterdata.PlacesScreen
import it.infraconsulting.activitytrack.ui.masterdata.PresetsScreen
import it.infraconsulting.activitytrack.ui.masterdata.RolesScreen
import it.infraconsulting.activitytrack.ui.masterdata.ScheduleScreen
import it.infraconsulting.activitytrack.ui.rememberBackStack
import it.infraconsulting.activitytrack.ui.report.DayScreen
import it.infraconsulting.activitytrack.ui.report.ExportScreen
import it.infraconsulting.activitytrack.ui.settings.SettingsScreen
import it.infraconsulting.activitytrack.ui.setup.SetupScreen
import it.infraconsulting.activitytrack.ui.start.StartScreen

class MainActivity : ComponentActivity() {
	/* entry to open, from a notification (consumed by the navigator) */
	private val deepLink = mutableStateOf<Long?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		if (savedInstanceState == null)
			deepLink.value = EntryLinks.entryOf(intent)
		setContent {
			ActivityTrackTheme { Root(deepLink.value) { deepLink.value = null } }
		}
	}

	/*
	 * From API 34 the user can dismiss ongoing notifications; re-post them
	 * whenever the app comes to the foreground (ONLY_ALERT_ONCE keeps it silent).
	 */
	override fun onStart() {
		super.onStart()
		(application as ActivityTrackApp).notificationSync.refresh()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		EntryLinks.entryOf(intent)?.let { deepLink.value = it }
	}
}

/* The data folder must be chosen (and still accessible) before anything else. */
@Composable
private fun Root(deepLink: Long?, consumeDeepLink: () -> Unit) {
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
private fun Navigator(deepLink: Long?, consumeDeepLink: () -> Unit) {
	val stack = rememberBackStack()
	val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
	val open: (Screen) -> Unit = { stack.add(it) }
	LaunchedEffect(deepLink) {
		if (deepLink != null) {
			val target = Screen.Entry(deepLink)
			if (stack.last() != target)
				stack.add(target)
			consumeDeepLink()
		}
	}
	BackHandler(enabled = stack.size > 1, onBack = back)
	RequestNotificationPermission()

	ScopedEntry(stack) { top -> Destination(top, back, open) }
}

/* POST_NOTIFICATIONS is a runtime permission from API 33; asked once per launch while missing. */
@Composable
private fun RequestNotificationPermission() {
	val context = LocalContext.current
	val app = context.applicationContext as ActivityTrackApp
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted) app.notificationSync.refresh()
	}
	LaunchedEffect(Unit) {
		val perm = Manifest.permission.POST_NOTIFICATIONS
		if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED)
			launcher.launch(perm)
	}
}

@Composable
private fun Destination(top: Screen, back: () -> Unit, open: (Screen) -> Unit) {
	when (top) {
		Screen.Home -> HomeScreen(
			onStart = { open(Screen.Start) },
			onOpenEntry = { open(Screen.Entry(it)) },
			onDay = { open(Screen.Day) },
			onMasterData = { open(Screen.MasterData) },
			onSettings = { open(Screen.Settings) },
		)
		Screen.Start -> StartScreen(back)
		is Screen.Entry -> EntryScreen(top.id, back)
		Screen.Day -> DayScreen(back, onOpenEntry = { open(Screen.Entry(it)) }, onExport = { open(Screen.Export) })
		Screen.Export -> ExportScreen(back)
		Screen.Settings -> SettingsScreen(back)
		Screen.MasterData -> MasterDataScreen(back, open)
		Screen.Companies -> CompaniesScreen(back) { open(Screen.Company(it)) }
		is Screen.Company -> CompanyScreen(top.id, back)
		Screen.Clients -> ClientsScreen(back) { open(Screen.Client(it)) }
		is Screen.Client -> ClientScreen(top.id, back)
		Screen.Roles -> RolesScreen(back)
		Screen.Presets -> PresetsScreen(back)
		Screen.Places -> PlacesScreen(back)
		Screen.Schedule -> ScheduleScreen(back)
	}
}
