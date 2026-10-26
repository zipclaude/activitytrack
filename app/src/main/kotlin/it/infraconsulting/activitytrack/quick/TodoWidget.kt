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
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.data.repo.StartRequest
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.ui.common.RED
import it.infraconsulting.activitytrack.ui.home.plannedSplit
import it.infraconsulting.activitytrack.ui.todo.overdue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/*
 * 4×2 widget with the planned to-dos (issue #62): the next ones (window a
 * setting, default 24 h + the overdue) each with a Start button — an
 * approved exception to the 2026-10-17 "no start outside the app" rule,
 * owner 2026-10-23. A row whose activity runs shows Pause and Stop
 * instead; a done to-do leaves the widget. Resizable from 4×2; the rows
 * scroll when they do not fit.
 */

private val TODO = ActionParameters.Key<Long>("todo")

/* rows shown at most; a 4×2 fits about four, taller widgets show more */
private const val MAX_ROWS = 6

private data class TodoRowState(
	val todo: TodoEntity,
	val time: String,
	val client: String?,
	val late: Boolean,
	/* the entry started from this to-do, when it runs */
	val runningEntryId: Long?,
	/* the to-do has a client: Start can act at once, else the start screen asks */
	val directStart: Boolean,
)

private fun todoTime(t: TodoEntity, zone: ZoneId): String {
	val today = LocalDate.now(zone)
	val locale = Locale.getDefault()
	t.plan()?.let { p ->
		val day = if (p.day == today) "" else p.day.format(DateTimeFormatter.ofPattern("EEE d/MM ", locale))
		return day + (p.startMin?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "")
	}
	val due = t.dueEpochDay?.let(LocalDate::ofEpochDay) ?: return ""
	return (if (due == today) "" else due.format(DateTimeFormatter.ofPattern("EEE d/MM ", locale))) +
		(t.dueTimeMin?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "")
}

/*
 * The rows, from the database flows (and the minute ticks for the window
 * and the overdue colour): read inside the composition, since an update
 * reaching a Glance session still open does not call provideGlance again
 * (device test 2026-10-26: a to-do marked done stayed on the widget).
 */
private fun todoStates(context: Context): Flow<List<TodoRowState>> {
	val c = (context.applicationContext as ActivityTrackApp).container
	return combine(
		c.todos.open, c.entries.openEntries, c.masterData.clients,
		c.settings.settings.map { it.widgetTodoWindowHours }.distinctUntilChanged(), minuteTicks(),
	) { open, entries, clients, window, now -> todoRows(open, entries, clients.associateBy { it.id }, window, Instant.ofEpochMilli(now)) }
}

private fun todoRows(open: List<TodoEntity>, entries: List<TimeEntryEntity>, clients: Map<Long, ClientEntity>, window: Int, now: Instant): List<TodoRowState> {
	val zone = ZoneId.systemDefault()
	val running = entries
		.filter { it.status == EntryStatus.ACTIVE && it.todoId != null }
		.associate { it.todoId!! to it.id }
	/* the same split as the Home Pianificate tab: overdue first, by moment */
	return plannedSplit(open, null, window, now, zone).dated
		.take(MAX_ROWS)
		.map { t ->
			TodoRowState(
				t, todoTime(t, zone), t.clientId?.let { clients[it]?.name }, overdue(t, now),
				running[t.id], t.clientId != null,
			)
		}
}

/*
 * Start from the widget, at once (owner-approved exception): the to-do's
 * client, company, title. A to-do without a client cannot start headlessly
 * (the start screen asks for it) and never offers this action. The app
 * asks what to do with the activities running; a widget cannot ask, so
 * they are suspended (owner, 2026-10-26: resumable with one tap, no time
 * counted twice); paused ones stay as they are.
 */
class WidgetTodoStartAction : ActionCallback {
	override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
		val c = (context.applicationContext as ActivityTrackApp).container
		val t = c.todos.find(parameters[TODO] ?: return) ?: return
		for (e in c.entries.openEntries.first())
			if (e.status == EntryStatus.ACTIVE && e.mode != SegmentKind.PAUSE)
				try {
					c.entries.apply(e.id, EntryCommand.SUSPEND)
				} catch (x: Exception) {
					Log.i("Widget", "entry ${e.id} not suspended: ${x.message}")
				}
		try {
			c.entries.start(StartRequest(SegmentKind.WORK, clientId = t.clientId, companyId = t.companyId, activity = t.title, todoId = t.id), emptyMap())
		} catch (e: ValidationException) {
			Log.i("Widget", "to-do ${t.id} not started: ${e.message}")
		}
	}
}

class TodoWidget : GlanceAppWidget() {
	override suspend fun provideGlance(context: Context, id: GlanceId) {
		if (!widgetsAllowed(context))
			return provideContent { GlanceTheme { DisabledWidget(context) } }
		val states = todoStates(context)
		val first = states.first()
		provideContent {
			val rows by states.collectAsState(first)
			GlanceTheme { TodoContent(context, rows) }
		}
	}

	@Composable
	private fun TodoContent(context: Context, rows: List<TodoRowState>) {
		val text = GlanceTheme.colors.onSurface
		val dim = GlanceTheme.colors.onSurfaceVariant
		Column(
			modifier = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(16.dp)
				.background(GlanceTheme.colors.widgetBackground).padding(horizontal = 12.dp, vertical = 8.dp),
		) {
			if (rows.isEmpty()) {
				Column(
					modifier = GlanceModifier.fillMaxSize().clickable(widgetOpenScreen(context, Screen.Todos)),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(context.getString(R.string.widget_todo_empty), style = TextStyle(color = text, fontSize = 14.sp))
				}
				return@Column
			}
			/* the rows scroll when they do not fit; at most MAX_ROWS */
			LazyColumn {
				items(rows, itemId = { it.todo.id }) { r ->
					Row(
						modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
							.clickable(widgetOpenScreen(context, Screen.Todo(r.todo.id))),
					) {
						Column(modifier = GlanceModifier.defaultWeight()) {
							Row {
								Text(
									r.time, maxLines = 1,
									style = TextStyle(
										color = if (r.late) ColorProvider(RED) else dim, fontSize = 11.sp,
									),
								)
								if (r.time.isNotBlank()) Spacer(GlanceModifier.width(4.dp))
								Text(
									r.todo.title, maxLines = 1,
									style = TextStyle(color = text, fontWeight = FontWeight.Medium, fontSize = 13.sp),
								)
							}
							r.client?.let { Text(it, maxLines = 1, style = TextStyle(color = dim, fontSize = 10.sp)) }
						}
						Spacer(GlanceModifier.width(6.dp))
						if (r.runningEntryId != null) {
							CommandCircle(
								EntryCommand.PAUSE, 34, context.getString(R.string.cmd_pause),
								actionRunCallback<WidgetCommandAction>(actionParametersOf(ENTRY to r.runningEntryId, COMMAND to EntryCommand.PAUSE.name)),
							)
							Spacer(GlanceModifier.width(6.dp))
							CommandCircle(
								EntryCommand.STOP, 34, context.getString(R.string.cmd_stop),
								actionRunCallback<WidgetCommandAction>(actionParametersOf(ENTRY to r.runningEntryId, COMMAND to EntryCommand.STOP.name)),
							)
						} else if (r.directStart) {
							CommandCircle(
								EntryCommand.WORK, 34, context.getString(R.string.todo_start),
								actionRunCallback<WidgetTodoStartAction>(actionParametersOf(TODO to r.todo.id)),
							)
						} else {
							CommandCircle(
								EntryCommand.WORK, 34, context.getString(R.string.todo_start),
								widgetOpenScreen(context, Screen.StartFromTodo(r.todo.id)),
							)
						}
					}
				}
			}
		}
	}
}

class TodoWidgetReceiver : GlanceAppWidgetReceiver() {
	override val glanceAppWidget: GlanceAppWidget = TodoWidget()
}
