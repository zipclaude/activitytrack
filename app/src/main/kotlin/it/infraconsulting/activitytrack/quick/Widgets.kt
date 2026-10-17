/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.quick

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.MainActivity
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.IllegalTransitionException
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.notify.ongoingActions
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.ui.common.commandStyle
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/*
 * Home-screen widgets (DESIGN §10, Glance). Nothing that needs a dialog runs
 * here: picking a client opens the start screen (owner decision 2026-10-17);
 * commands on a running entry act directly, like the notification actions.
 */

/* What the widgets show, read once per update. */
private data class WidgetState(
	val entryId: Long?,
	val title: String,
	val mode: SegmentKind?,
	val sinceMs: Long?,
	val recent: List<Pair<Long, String>>,
)

private suspend fun loadState(context: Context): WidgetState {
	val c = (context.applicationContext as ActivityTrackApp).container
	val clients = c.masterData.clients.first().associateBy { it.id }
	val companies = c.masterData.companies.first().associateBy { it.id }
	val e = QuickToggle.target(c)
	val recent = c.entries.clientLastUse.first()
		.sortedByDescending { it.lastMs }
		.mapNotNull { u -> clients[u.clientId]?.takeIf { !it.archived }?.let { it.id to it.name } }
		.take(3)
	if (e == null)
		return WidgetState(null, context.getString(R.string.widget_no_activity), null, null, recent)
	val since = c.entries.segments(e.id).first().lastOrNull { it.endUtcMs == null }?.startUtcMs
	val title = e.clientId?.let { clients[it]?.name } ?: e.companyId?.let { companies[it]?.name }
		?: context.getString(R.string.app_name)
	return WidgetState(e.id, title, e.mode, since, recent)
}

private fun openScreen(context: Context, screen: Screen) =
	actionStartActivity(
		Intent(context, MainActivity::class.java)
			.setAction(Intent.ACTION_VIEW)
			/* one intent per screen: the system dedups identical intents */
			.setData("activitytrack://screen/${screen.key()}".toUri())
			.putExtra(EntryLinks.EXTRA_SCREEN, screen.key()),
	)

private val ENTRY = ActionParameters.Key<Long>("entry")
private val COMMAND = ActionParameters.Key<String>("command")

/* Applies a state-machine command to an entry (STOP stops at once, as from a notification). */
class WidgetCommandAction : ActionCallback {
	override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
		val c = (context.applicationContext as ActivityTrackApp).container
		try {
			c.entries.apply(parameters[ENTRY] ?: return, EntryCommand.valueOf(parameters[COMMAND] ?: return))
		} catch (e: IllegalTransitionException) {
			Log.i("Widget", "ignored: ${e.message}")
		}
	}
}

/* 1×1 toggle (same as the tile). */
class WidgetToggleAction : ActionCallback {
	override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
		val c = (context.applicationContext as ActivityTrackApp).container
		try {
			QuickToggle.toggle(c)
		} catch (e: IllegalTransitionException) {
			Log.i("Widget", "ignored: ${e.message}")
		}
	}
}

@Composable
private fun CommandCircle(cmd: EntryCommand, size: Int, label: String, action: Action) {
	val style = commandStyle(cmd) ?: return
	Box(
		modifier = GlanceModifier.size(size.dp).cornerRadius((size / 2).dp)
			.background(ColorProvider(style.container)).clickable(action),
		contentAlignment = Alignment.Center,
	) {
		Image(
			provider = ImageProvider(style.icon),
			contentDescription = label,
			colorFilter = ColorFilter.tint(ColorProvider(style.content)),
			modifier = GlanceModifier.size((size * 0.55).toInt().dp),
		)
	}
}

private fun clock(ms: Long): String {
	val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
	return "%02d:%02d".format(t.hour, t.minute)
}

/* 4×1: running entry with its commands, or recent clients to start from. */
class StatusWidget : GlanceAppWidget() {
	override suspend fun provideGlance(context: Context, id: GlanceId) {
		val s = loadState(context)
		provideContent { GlanceTheme { StatusContent(context, s) } }
	}

	@Composable
	private fun StatusContent(context: Context, s: WidgetState) {
		val text = GlanceTheme.colors.onSurface
		Row(
			modifier = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(16.dp)
				.background(GlanceTheme.colors.widgetBackground).padding(horizontal = 12.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(
				modifier = GlanceModifier.defaultWeight().clickable(
					if (s.entryId != null) openScreen(context, Screen.Entry(s.entryId)) else openScreen(context, Screen.Start),
				),
			) {
				Text(s.title, maxLines = 1, style = TextStyle(color = text, fontWeight = FontWeight.Bold, fontSize = 15.sp))
				if (s.entryId != null) {
					val mode = context.getString(
						when (s.mode) {
							SegmentKind.TRAVEL -> R.string.mode_travel
							SegmentKind.PAUSE -> R.string.mode_pause
							else -> R.string.mode_work
						},
					)
					Text(
						s.sinceMs?.let { context.getString(R.string.widget_since, mode, clock(it)) } ?: mode,
						maxLines = 1,
						style = TextStyle(color = text, fontSize = 12.sp),
					)
				}
			}
			if (s.entryId != null) {
				Spacer(GlanceModifier.width(6.dp))
				Box(
					modifier = GlanceModifier.size(40.dp).cornerRadius(20.dp).background(GlanceTheme.colors.secondaryContainer)
						.clickable(openScreen(context, Screen.QuickExpense)),
					contentAlignment = Alignment.Center,
				) {
					Text("€", style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontWeight = FontWeight.Bold, fontSize = 18.sp))
				}
				for (cmd in ongoingActions(s.mode)) {
					Spacer(GlanceModifier.width(6.dp))
					CommandCircle(
						cmd, 40, context.getString(commandLabel(cmd, s.mode)),
						actionRunCallback<WidgetCommandAction>(actionParametersOf(ENTRY to s.entryId, COMMAND to cmd.name)),
					)
				}
			} else {
				for ((clientId, name) in s.recent) {
					Spacer(GlanceModifier.width(6.dp))
					Box(
						modifier = GlanceModifier.cornerRadius(18.dp).background(GlanceTheme.colors.secondaryContainer)
							.padding(horizontal = 10.dp, vertical = 8.dp)
							.clickable(openScreen(context, Screen.StartFor(clientId))),
					) {
						Text(name, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 12.sp))
					}
				}
				Spacer(GlanceModifier.width(6.dp))
				CommandCircle(EntryCommand.WORK, 40, context.getString(R.string.start_action), openScreen(context, Screen.Start))
			}
		}
	}
}

/* 1×1: one coloured toggle; with nothing ACTIVE it opens the start screen. */
class ToggleWidget : GlanceAppWidget() {
	override suspend fun provideGlance(context: Context, id: GlanceId) {
		val s = loadState(context)
		provideContent {
			GlanceTheme {
				Box(
					modifier = GlanceModifier.fillMaxSize().appWidgetBackground(),
					contentAlignment = Alignment.Center,
				) {
					when {
						s.entryId == null ->
							CommandCircle(EntryCommand.WORK, 52, context.getString(R.string.start_action), openScreen(context, Screen.Start))
						s.mode == SegmentKind.PAUSE ->
							CommandCircle(EntryCommand.WORK, 52, context.getString(R.string.cmd_resume), actionRunCallback<WidgetToggleAction>())
						else ->
							CommandCircle(EntryCommand.PAUSE, 52, context.getString(R.string.cmd_pause), actionRunCallback<WidgetToggleAction>())
					}
				}
			}
		}
	}
}

private fun commandLabel(cmd: EntryCommand, mode: SegmentKind?): Int = when (cmd) {
	EntryCommand.WORK -> if (mode == SegmentKind.PAUSE) R.string.cmd_resume else R.string.cmd_work
	EntryCommand.TRAVEL -> R.string.cmd_travel
	EntryCommand.PAUSE -> R.string.cmd_pause
	EntryCommand.SUSPEND -> R.string.cmd_suspend
	else -> R.string.cmd_stop
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
	override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}

class ToggleWidgetReceiver : GlanceAppWidgetReceiver() {
	override val glanceAppWidget: GlanceAppWidget = ToggleWidget()
}

object QuickWidgets {
	/* Called on every change of the open entries. */
	suspend fun refresh(context: Context) {
		StatusWidget().updateAll(context)
		ToggleWidget().updateAll(context)
	}
}
