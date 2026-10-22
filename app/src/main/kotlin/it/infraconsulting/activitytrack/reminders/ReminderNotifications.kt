/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.reminders

import android.app.Notification
import android.content.Context
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.notify.ActionReceiver
import it.infraconsulting.activitytrack.notify.EntryInfo
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.notify.EntryNotifications
import it.infraconsulting.activitytrack.notify.NotificationChannels
import it.infraconsulting.activitytrack.ui.Screen
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* Reminder notifications (DESIGN §7): confirm, pause, day summary. */
object ReminderNotifications {
	private fun action(label: String, intent: android.app.PendingIntent) =
		Notification.Action.Builder(null, label, intent).build()

	/* "Still on X?" — Yes / Pause / Stop at the last confirmation. */
	fun confirm(context: Context, info: EntryInfo): Notification {
		val e = info.entry
		val last = e.lastConfirmedUtcMs
		val b = Notification.Builder(context, NotificationChannels.CONFIRM)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.notif_confirm_title, info.title))
			.setContentIntent(EntryLinks.openEntry(context, e.id))
			.setAutoCancel(true)
			.setCategory(Notification.CATEGORY_REMINDER)
			.addAction(action(context.getString(R.string.action_yes), ActionReceiver.confirm(context, e.id)))
			.addAction(action(context.getString(R.string.cmd_pause), ActionReceiver.command(context, e.id, EntryCommand.PAUSE)))
		if (last != null) {
			val hhmm = clock(last)
			b.setContentText(context.getString(R.string.notif_confirm_text, hhmm))
			b.addAction(
				action(context.getString(R.string.notif_stop_at, hhmm), ActionReceiver.stopAtLastConfirmation(context, e.id)),
			)
		}
		return b.build()
	}

	/* "X finished?" at the end chosen when starting (M22): stop then, or go on. */
	fun plannedEnd(context: Context, info: EntryInfo, atMs: Long): Notification {
		val id = info.entry.id
		val hhmm = clock(atMs)
		return Notification.Builder(context, NotificationChannels.CONFIRM)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.notif_planned_end_title, info.title))
			.setContentText(context.getString(R.string.notif_planned_end_text, hhmm))
			.setContentIntent(EntryLinks.openEntry(context, id))
			.setAutoCancel(true)
			.setCategory(Notification.CATEGORY_REMINDER)
			.addAction(action(context.getString(R.string.notif_planned_end_stop, hhmm), ActionReceiver.stopAtPlannedEnd(context, id, atMs)))
			.addAction(action(context.getString(R.string.notif_planned_end_go_on), ActionReceiver.goOn(context, id)))
			.build()
	}

	/* "Still on pause?" — Resume / Stop. */
	fun pause(context: Context, info: EntryInfo): Notification {
		val id = info.entry.id
		return Notification.Builder(context, NotificationChannels.PAUSE)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.notif_pause_title))
			.setContentText(info.title)
			.setContentIntent(EntryLinks.openEntry(context, id))
			.setAutoCancel(true)
			.setCategory(Notification.CATEGORY_REMINDER)
			.addAction(action(context.getString(R.string.cmd_resume), ActionReceiver.command(context, id, EntryCommand.WORK)))
			.addAction(action(context.getString(R.string.cmd_stop), ActionReceiver.command(context, id, EntryCommand.STOP)))
			.build()
	}

	/* No activity yet on a working day: absent / late entry… / start (SPEC §5). */
	fun dayStart(context: Context, day: LocalDate): Notification =
		Notification.Builder(context, NotificationChannels.DAY_START)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.notif_day_start_title))
			.setContentText(context.getString(R.string.notif_day_start_text))
			.setContentIntent(EntryNotifications.openAppIntent(context, 2))
			.setAutoCancel(true)
			.setCategory(Notification.CATEGORY_REMINDER)
			.addAction(action(context.getString(R.string.action_absence), ActionReceiver.absentAllDay(context, day)))
			.addAction(
				action(
					context.getString(R.string.action_late_entry),
					EntryLinks.openScreen(context, Screen.LateEntry(day.toEpochDay()), 3),
				),
			)
			.addAction(action(context.getString(R.string.action_start_entry), EntryLinks.openScreen(context, Screen.Start, 4)))
			.build()

	/*
	 * "<title> at HH:MM" before a planned to-do: Start (start screen from the
	 * to-do) / Postpone N min / Re-plan (the to-do editor). No dialog outside
	 * the app.
	 */
	fun planned(context: Context, todoId: Long, title: String, subtitle: String?, startMs: Long, snoozeMin: Int): Notification {
		val rc = (todoId * 4).toInt()
		return Notification.Builder(context, NotificationChannels.PLANNED)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.notif_planned_title, title, clock(startMs)))
			.apply { subtitle?.let(::setContentText) }
			.setContentIntent(EntryLinks.openScreen(context, Screen.Todo(todoId), -(rc + 1)))
			.setAutoCancel(true)
			.setCategory(Notification.CATEGORY_REMINDER)
			.setWhen(startMs)
			.addAction(action(context.getString(R.string.todo_start), EntryLinks.openScreen(context, Screen.StartFromTodo(todoId), -(rc + 2))))
			.addAction(action(context.getString(R.string.action_postpone, snoozeMin), ActionReceiver.postponeTodo(context, todoId)))
			.addAction(action(context.getString(R.string.action_replan), EntryLinks.openScreen(context, Screen.Todo(todoId), -(rc + 3))))
			.build()
	}

	/*
	 * The day's planned to-dos nobody worked on (issue #52, owner: one notice
	 * at the end of the working hours, not one per to-do): re-plan them on
	 * the next working day, or open the to-dos.
	 */
	fun unworked(context: Context, titles: List<String>, ids: List<Long>, next: java.time.LocalDate): Notification {
		val day = next.format(java.time.format.DateTimeFormatter.ofPattern("EEE d/MM", java.util.Locale.getDefault()))
		return Notification.Builder(context, NotificationChannels.PLANNED)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.resources.getQuantityString(R.plurals.unworked_title, titles.size, titles.size))
			.setContentText(titles.joinToString(" · "))
			.setStyle(Notification.BigTextStyle().bigText(titles.joinToString("\n")))
			.setContentIntent(EntryLinks.openScreen(context, Screen.Todos, -6))
			.setAutoCancel(true)
			.addAction(action(context.getString(R.string.unworked_replan, day), ActionReceiver.replanUnworked(context, ids, next)))
			.build()
	}

	fun daySummary(context: Context, solar: Duration, reported: Duration): Notification =
		Notification.Builder(context, NotificationChannels.DAY_SUMMARY)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.notif_summary_title))
			.setContentText(context.getString(R.string.notif_summary_text, hm(solar), hm(reported)))
			.setContentIntent(EntryNotifications.openAppIntent(context, 1))
			.setAutoCancel(true)
			.build()

	private fun clock(ms: Long): String {
		val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
		return "%02d:%02d".format(t.hour, t.minute)
	}

	fun hm(d: Duration): String = "%d:%02d".format(d.toHours(), d.toMinutesPart())
}
