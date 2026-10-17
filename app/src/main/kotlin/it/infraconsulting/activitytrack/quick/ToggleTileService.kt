/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.quick

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.MainActivity
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.model.IllegalTransitionException
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.ui.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Quick Settings tile (active mode): shows the most recent ACTIVE entry and
 * toggles WORK/PAUSE; with none it opens the start screen. The app asks
 * for a refresh whenever the open entries change ([refresh]).
 */
class ToggleTileService : TileService() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val container get() = (application as ActivityTrackApp).container

	override fun onStartListening() {
		super.onStartListening()
		scope.launch { render() }
	}

	override fun onClick() {
		super.onClick()
		scope.launch {
			val toggled = try {
				withContext(Dispatchers.Default) { QuickToggle.toggle(container) }
			} catch (e: IllegalTransitionException) {
				Log.i(TAG, "ignored: ${e.message}")
				true
			}
			if (!toggled)
				openStart()
			render()
		}
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}

	@SuppressLint("StartActivityAndCollapseDeprecated")
	private fun openStart() {
		val pi = QuickToggle.startScreen(this)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
			startActivityAndCollapse(pi)
		else
			@Suppress("DEPRECATION")
			startActivityAndCollapse(
				Intent(this, MainActivity::class.java)
					.putExtra(EntryLinks.EXTRA_SCREEN, Screen.Start.key())
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			)
	}

	private suspend fun render() {
		val tile = qsTile ?: return
		val e = withContext(Dispatchers.Default) { QuickToggle.target(container) }
		val title = e?.let { withContext(Dispatchers.Default) { titleOf(it.clientId, it.companyId) } }
		when {
			e == null -> {
				tile.state = Tile.STATE_INACTIVE
				tile.label = getString(R.string.tile_start)
				tile.subtitle = null
				tile.icon = Icon.createWithResource(this, R.drawable.ic_cmd_play)
			}
			e.mode == SegmentKind.PAUSE -> {
				tile.state = Tile.STATE_INACTIVE
				tile.label = title
				tile.subtitle = getString(R.string.mode_pause)
				tile.icon = Icon.createWithResource(this, R.drawable.ic_cmd_play)
			}
			else -> {
				tile.state = Tile.STATE_ACTIVE
				tile.label = title
				tile.subtitle = getString(if (e.mode == SegmentKind.TRAVEL) R.string.mode_travel else R.string.mode_work)
				tile.icon = Icon.createWithResource(this, R.drawable.ic_cmd_pause)
			}
		}
		tile.updateTile()
	}

	private suspend fun titleOf(clientId: Long?, companyId: Long?): String =
		clientId?.let { container.masterData.client(it)?.name }
			?: companyId?.let { container.masterData.company(it)?.name }
			?: getString(R.string.app_name)

	companion object {
		private const val TAG = "ToggleTile"

		/* Active tile: the system binds it to read the new state. */
		fun refresh(context: Context) {
			requestListeningState(context, ComponentName(context, ToggleTileService::class.java))
		}
	}
}
