/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import it.infraconsulting.activitytrack.R

/*
 * Notification channels. Importance cannot be raised by the app after creation,
 * so each reminder class has its own channel and the user tunes it in settings.
 */
object NotificationChannels {
	const val ONGOING = "ongoing"			/* running entries, chronometer + actions */
	const val CONFIRM = "confirm"			/* "still working on X?" */
	const val PAUSE = "pause"			/* "still on pause?" */
	const val MOVEMENT = "movement"			/* movement during an entry, audible */
	const val ARRIVAL = "arrival"			/* stopped while travelling */
	const val DAY_START = "day_start"		/* no entry started at day start */
	const val DAY_SUMMARY = "day_summary"		/* end-of-day totals */
	const val TO_COMPLETE = "to_complete"		/* entries missing details */
	const val PLANNED = "planned"			/* planned to-do about to start */

	/* channels in the order of the Notifications settings, with their names */
	val LIST: List<Pair<String, Int>> by lazy {
		listOf(
			CONFIRM to R.string.ch_confirm, PAUSE to R.string.ch_pause, PLANNED to R.string.ch_planned,
			MOVEMENT to R.string.ch_movement, ARRIVAL to R.string.ch_arrival, DAY_START to R.string.ch_day_start,
			DAY_SUMMARY to R.string.ch_day_summary, TO_COMPLETE to R.string.ch_to_complete, ONGOING to R.string.ch_ongoing,
		)
	}

	fun register(context: Context) {
		val nm = context.getSystemService(NotificationManager::class.java)
		val channels = listOf(
			channel(context, ONGOING, R.string.ch_ongoing, NotificationManager.IMPORTANCE_LOW),
			channel(context, CONFIRM, R.string.ch_confirm, NotificationManager.IMPORTANCE_DEFAULT),
			channel(context, PAUSE, R.string.ch_pause, NotificationManager.IMPORTANCE_DEFAULT),
			channel(context, MOVEMENT, R.string.ch_movement, NotificationManager.IMPORTANCE_HIGH),
			channel(context, ARRIVAL, R.string.ch_arrival, NotificationManager.IMPORTANCE_DEFAULT),
			channel(context, DAY_START, R.string.ch_day_start, NotificationManager.IMPORTANCE_DEFAULT),
			channel(context, DAY_SUMMARY, R.string.ch_day_summary, NotificationManager.IMPORTANCE_LOW),
			channel(context, TO_COMPLETE, R.string.ch_to_complete, NotificationManager.IMPORTANCE_DEFAULT),
			channel(context, PLANNED, R.string.ch_planned, NotificationManager.IMPORTANCE_HIGH),
		)
		nm.createNotificationChannels(channels)
	}

	private fun channel(context: Context, id: String, name: Int, importance: Int) =
		NotificationChannel(id, context.getString(name), importance).apply {
			setShowBadge(id != ONGOING)
		}
}
