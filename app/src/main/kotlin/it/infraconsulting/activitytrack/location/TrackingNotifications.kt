/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.location

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.start.Arrival
import it.infraconsulting.activitytrack.notify.ActionReceiver
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.notify.NotificationChannels
import java.time.Instant
import java.time.ZoneId

/* Movement and arrival prompts (SPEC §5, DESIGN §7). */
object TrackingNotifications {
	private fun action(label: String, intent: PendingIntent) =
		Notification.Action.Builder(null, label, intent).build()

	/* Moved away during WORK: Travel from <departure> / Ignore / Stop at <departure>. */
	fun movement(context: Context, entryId: Long, title: String, segmentId: Long, departureMs: Long): Notification {
		val hhmm = clock(departureMs)
		return Notification.Builder(context, NotificationChannels.MOVEMENT)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.notif_moved_title, title))
			.setContentText(context.getString(R.string.notif_moved_text, hhmm))
			.setContentIntent(EntryLinks.openEntry(context, entryId))
			.setAutoCancel(true)
			.setCategory(Notification.CATEGORY_REMINDER)
			.addAction(
				action(
					context.getString(R.string.notif_travel_from, hhmm),
					ActionReceiver.commandAt(context, entryId, EntryCommand.TRAVEL, departureMs),
				),
			)
			.addAction(action(context.getString(R.string.notif_ignore), ActionReceiver.ignoreMovement(context, entryId, segmentId)))
			.addAction(
				action(
					context.getString(R.string.notif_stop_at, hhmm),
					ActionReceiver.commandAt(context, entryId, EntryCommand.STOP, departureMs),
				),
			)
			.build()
	}

	/*
	 * Stopped during TRAVEL: the proposal decides the first action (work at
	 * the client, work, or stop at home); "still travelling" just dismisses.
	 */
	fun arrival(context: Context, entryId: Long, title: String, arrival: Arrival, clientName: String?, atMs: Long): Notification {
		val hhmm = clock(atMs)
		val b = Notification.Builder(context, NotificationChannels.ARRIVAL)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.notif_arrived_title))
			.setContentText(context.getString(R.string.notif_arrived_text, title, hhmm))
			.setContentIntent(EntryLinks.openEntry(context, entryId))
			.setAutoCancel(true)
			.setCategory(Notification.CATEGORY_REMINDER)
		when (arrival) {
			is Arrival.AtClient -> b.addAction(
				action(
					context.getString(R.string.notif_work_at_client, clientName.orEmpty(), hhmm),
					ActionReceiver.arriveAtClient(context, entryId, arrival.clientId, atMs),
				),
			)
			Arrival.Home -> b.addAction(
				action(
					context.getString(R.string.notif_stop_at, hhmm),
					ActionReceiver.commandAt(context, entryId, EntryCommand.STOP, atMs, arrival = true),
				),
			)
			Arrival.Work, Arrival.Unknown -> {}
		}
		if (arrival !is Arrival.AtClient)
			b.addAction(
				action(
					context.getString(R.string.notif_work_from, hhmm),
					ActionReceiver.commandAt(context, entryId, EntryCommand.WORK, atMs),
				),
			)
		b.addAction(action(context.getString(R.string.notif_still_travelling), ActionReceiver.dismissArrival(context, entryId)))
		return b.build()
	}

	private fun clock(ms: Long): String {
		val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
		return "%02d:%02d".format(t.hour, t.minute)
	}
}
