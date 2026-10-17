/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import it.infraconsulting.activitytrack.MainActivity
import it.infraconsulting.activitytrack.ui.Screen

/* Intents that open the app on a given entry (notification content intents). */
object EntryLinks {
	const val EXTRA_ENTRY = "it.infraconsulting.activitytrack.extra.ENTRY"

	fun openEntry(context: Context, entryId: Long): PendingIntent =
		PendingIntent.getActivity(
			context,
			(entryId * 16 + 10).toInt(),
			Intent(context, MainActivity::class.java)
				.putExtra(EXTRA_ENTRY, entryId)
				.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)

	const val EXTRA_SCREEN = "it.infraconsulting.activitytrack.extra.SCREEN"

	/* Any screen (by its back-stack key); [requestCode] keeps distinct links apart. */
	fun openScreen(context: Context, screen: Screen, requestCode: Int): PendingIntent =
		PendingIntent.getActivity(
			context,
			requestCode,
			Intent(context, MainActivity::class.java)
				.putExtra(EXTRA_SCREEN, screen.key())
				.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)

	/* Screen carried by [intent], if any. */
	fun screenOf(intent: Intent?): Screen? {
		intent ?: return null
		intent.getLongExtra(EXTRA_ENTRY, -1).takeIf { it >= 0 }?.let { return Screen.Entry(it) }
		return intent.getStringExtra(EXTRA_SCREEN)?.let(Screen::parse)
	}
}
