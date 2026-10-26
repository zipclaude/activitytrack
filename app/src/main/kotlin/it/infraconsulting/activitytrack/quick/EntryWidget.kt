/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.quick

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.StartRequest
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.ui.common.commandStyle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/*
 * 4×2 widget mirroring the big card of the Home (issue #62): company and
 * client, activity, state ("In lavoro dalle 09:06") and elapsed time, the
 * command buttons of the current state — STOP stops at once, as from the
 * notification (owner answer 1). Without an open entry: Start (start
 * screen, owner answer 4) and Travel, which starts at once headlessly as
 * the empty card does. Only the latest open entry (owner answer 2); the
 * elapsed counter moves once a minute (owner answer 3, WidgetTicker).
 * Resizable from 4×2 up (owner answer 5).
 */

private data class EntryWidgetState(
	val entryId: Long?,
	val company: String?,
	val title: String,
	val activity: String?,
	val status: EntryStatus,
	val mode: SegmentKind?,
	val sinceMs: Long?,
	/* the running segment's length so far; null when nothing runs */
	val runningMs: Long?,
	val workMs: Long,
	val travelMs: Long,
)

/*
 * The state shown, from the database flows and the minute ticks: read
 * inside the composition (issue #62 device test, 2026-10-26) — an update
 * that reaches a Glance session still open recomposes without calling
 * provideGlance again, so data loaded before provideContent stayed stale.
 */
private fun entryStates(context: Context): Flow<EntryWidgetState> {
	val c = (context.applicationContext as ActivityTrackApp).container
	return combine(c.entries.openEntries, c.entries.openEntrySegments, c.masterData.clients, c.masterData.companies, minuteTicks()) { entries, segs, clients, companies, now ->
		entryState(context, entries, segs, clients.associateBy { it.id }, companies.associateBy { it.id }, now)
	}
}

private fun entryState(
	context: Context,
	openEntries: List<TimeEntryEntity>,
	allSegs: List<SegmentEntity>,
	clients: Map<Long, ClientEntity>,
	companies: Map<Long, CompanyEntity>,
	now: Long,
): EntryWidgetState {
	/* the entry the Home would show in its big card: the most recent open one */
	val e = openEntries.firstOrNull()
		?: return EntryWidgetState(null, null, context.getString(R.string.home_none), null, EntryStatus.CLOSED, null, null, null, 0, 0)
	val segs = allSegs.filter { it.entryId == e.id }.sortedBy { it.startUtcMs }
	val open = segs.lastOrNull { it.endUtcMs == null }
	val since = when (e.status) {
		EntryStatus.ACTIVE -> open?.startUtcMs
		else -> segs.mapNotNull { it.endUtcMs }.maxOrNull()
	}
	fun total(kind: SegmentKind) = segs.filter { it.kind == kind }.sumOf { maxOf(0L, (it.endUtcMs ?: now) - it.startUtcMs) }
	return EntryWidgetState(
		entryId = e.id,
		company = e.companyId?.let { companies[it]?.name },
		title = e.clientId?.let { clients[it]?.name } ?: e.companyId?.let { companies[it]?.name } ?: context.getString(R.string.app_name),
		activity = e.activity,
		status = e.status,
		mode = e.mode,
		sinceMs = since,
		runningMs = open?.takeIf { e.status == EntryStatus.ACTIVE }?.let { now - it.startUtcMs },
		workMs = total(SegmentKind.WORK),
		travelMs = total(SegmentKind.TRAVEL),
	)
}

/* the commands of the state, as on the Home card (no dialogs from a widget) */
private fun widgetCommands(status: EntryStatus, mode: SegmentKind?): List<EntryCommand> = when (status) {
	EntryStatus.ACTIVE -> when (mode) {
		SegmentKind.WORK -> listOf(EntryCommand.PAUSE, EntryCommand.TRAVEL, EntryCommand.SUSPEND, EntryCommand.STOP)
		SegmentKind.TRAVEL -> listOf(EntryCommand.WORK, EntryCommand.PAUSE, EntryCommand.SUSPEND, EntryCommand.STOP)
		else -> listOf(EntryCommand.WORK, EntryCommand.STOP)
	}
	EntryStatus.SUSPENDED -> listOf(EntryCommand.WORK, EntryCommand.TRAVEL, EntryCommand.STOP)
	EntryStatus.TO_COMPLETE -> listOf(EntryCommand.REOPEN)
	EntryStatus.CLOSED -> emptyList()
}

private fun hm(ms: Long): String {
	val min = ms / 60_000
	return "%d:%02d".format(min / 60, min % 60)
}

/* Travel from the widget, as from the empty card of the Home: at once, no destination. */
class WidgetTravelAction : ActionCallback {
	override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
		val c = (context.applicationContext as ActivityTrackApp).container
		try {
			val p = c.entries.startProposal(c.masterData.knownSites())
			c.entries.start(StartRequest(SegmentKind.TRAVEL, clientId = null, companyId = p.companyId, locationKind = p.locationKind), emptyMap())
		} catch (e: ValidationException) {
			Log.i("Widget", "travel not started: ${e.message}")
		}
	}
}

class EntryWidget : GlanceAppWidget() {
	override suspend fun provideGlance(context: Context, id: GlanceId) {
		if (!widgetsAllowed(context))
			return provideContent { GlanceTheme { DisabledWidget(context) } }
		val states = entryStates(context)
		val first = states.first()
		provideContent {
			val s by states.collectAsState(first)
			GlanceTheme { EntryContent(context, s) }
		}
	}

	@Composable
	private fun EntryContent(context: Context, s: EntryWidgetState) {
		val text = GlanceTheme.colors.onSurface
		val dim = GlanceTheme.colors.onSurfaceVariant
		val open: Action = if (s.entryId != null) widgetOpenScreen(context, Screen.Entry(s.entryId)) else widgetOpenScreen(context, Screen.Home)
		Column(
			modifier = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(16.dp)
				.background(GlanceTheme.colors.widgetBackground).padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			/* defaultWeight only: fillMaxSize here would take the whole height and clip the buttons out */
			Row(
				modifier = GlanceModifier.defaultWeight().fillMaxWidth().clickable(open),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Column(modifier = GlanceModifier.defaultWeight()) {
					s.company?.let { Text(it, maxLines = 1, style = TextStyle(color = dim, fontSize = 11.sp)) }
					Text(s.title, maxLines = 1, style = TextStyle(color = text, fontWeight = FontWeight.Bold, fontSize = 16.sp))
					s.activity?.let { Text(it, maxLines = 1, style = TextStyle(color = dim, fontSize = 12.sp)) }
				if (s.entryId != null) {
					val state = when (s.status) {
						EntryStatus.ACTIVE -> context.getString(
							R.string.widget_since,
							context.getString(
								when (s.mode) {
									SegmentKind.TRAVEL -> R.string.mode_travel
									SegmentKind.PAUSE -> R.string.mode_pause
									else -> R.string.mode_work
								},
							),
							s.sinceMs?.let { clock(it) } ?: "",
						)
						EntryStatus.SUSPENDED -> context.getString(R.string.status_suspended)
						EntryStatus.TO_COMPLETE -> context.getString(R.string.status_to_complete)
						EntryStatus.CLOSED -> context.getString(R.string.status_closed)
					}
					Text(state, maxLines = 1, style = TextStyle(color = text, fontSize = 12.sp))
					Text(
						context.getString(R.string.totals_fmt, hm(s.workMs), hm(s.travelMs)),
						maxLines = 1, style = TextStyle(color = dim, fontSize = 11.sp),
					)
				}
			}
			s.runningMs?.let {
					Spacer(GlanceModifier.width(8.dp))
					Text(hm(it), style = TextStyle(color = text, fontWeight = FontWeight.Bold, fontSize = 24.sp))
				}
			}
			Spacer(GlanceModifier.height(8.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (s.entryId == null) {
					/* the empty card of the Home: start (start screen) and travel (at once) */
					CommandCircle(EntryCommand.WORK, 40, context.getString(R.string.start_action), widgetOpenScreen(context, Screen.Start))
					Spacer(GlanceModifier.width(8.dp))
					CommandCircle(EntryCommand.TRAVEL, 40, context.getString(R.string.start_travel), actionRunCallback<WidgetTravelAction>())
				} else {
					for ((i, cmd) in widgetCommands(s.status, s.mode).withIndex()) {
						if (i > 0) Spacer(GlanceModifier.width(8.dp))
						CommandCircle(
							cmd, 40, context.getString(commandLabel(cmd, s.mode)),
							actionRunCallback<WidgetCommandAction>(actionParametersOf(ENTRY to s.entryId, COMMAND to cmd.name)),
						)
					}
					if (s.status == EntryStatus.TO_COMPLETE) {
						Spacer(GlanceModifier.width(8.dp))
						Box(
							modifier = GlanceModifier.cornerRadius(24.dp).background(ColorProvider(commandStyle(EntryCommand.WORK)!!.container))
								.padding(horizontal = 14.dp, vertical = 10.dp).clickable(open),
							contentAlignment = Alignment.Center,
						) {
							Text(
								context.getString(R.string.cmd_complete),
								style = TextStyle(color = ColorProvider(commandStyle(EntryCommand.WORK)!!.content), fontSize = 13.sp),
							)
						}
					}
				}
			}
		}
	}
}

class EntryWidgetReceiver : GlanceAppWidgetReceiver() {
	override val glanceAppWidget: GlanceAppWidget = EntryWidget()
}
