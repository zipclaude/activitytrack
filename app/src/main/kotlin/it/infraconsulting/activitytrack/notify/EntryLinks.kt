/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import it.infraconsulting.activitytrack.MainActivity

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

	/* Entry id carried by [intent], if any. */
	fun entryOf(intent: Intent?): Long? =
		intent?.getLongExtra(EXTRA_ENTRY, -1)?.takeIf { it >= 0 }
}
