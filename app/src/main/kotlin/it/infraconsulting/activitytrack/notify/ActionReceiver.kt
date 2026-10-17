/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.IllegalTransitionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * Notification actions (DESIGN §6.1): applied straight to the repository,
 * without starting an Activity. Notifications are refreshed by
 * NotificationSync when the database changes.
 */
class ActionReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val entryId = intent.getLongExtra(EXTRA_ENTRY, -1)
		if (entryId < 0)
			return
		val app = context.applicationContext as ActivityTrackApp
		val entries = app.container.entries
		val pending = goAsync()
		scope.launch {
			try {
				when (intent.action) {
					ACTION_COMMAND -> {
						val cmd = EntryCommand.valueOf(intent.getStringExtra(EXTRA_COMMAND) ?: return@launch)
						entries.apply(entryId, cmd)
					}
					ACTION_CONFIRM -> entries.confirm(entryId)
					ACTION_STOP_AT_LAST -> entries.stopAtLastConfirmation(entryId)
				}
				/* the answered reminder goes away; ongoing/to-complete are re-synced */
				val nm = context.getSystemService(NotificationManager::class.java)
				nm.cancel(NotificationIds.of(entryId, NotificationIds.CONFIRM))
				nm.cancel(NotificationIds.of(entryId, NotificationIds.PAUSE))
			} catch (e: IllegalTransitionException) {
				/* stale notification: the entry already changed state */
				Log.i(TAG, "ignored: ${e.message}")
			} catch (e: ValidationException) {
				Log.w(TAG, "rejected: ${e.message}")
			} finally {
				pending.finish()
			}
		}
	}

	companion object {
		private const val TAG = "ActionReceiver"
		private const val ACTION_COMMAND = "it.infraconsulting.activitytrack.action.COMMAND"
		private const val ACTION_CONFIRM = "it.infraconsulting.activitytrack.action.CONFIRM"
		private const val ACTION_STOP_AT_LAST = "it.infraconsulting.activitytrack.action.STOP_AT_LAST"
		private const val EXTRA_ENTRY = "entry"
		private const val EXTRA_COMMAND = "command"

		/* Receivers have ~10 s; repository calls are short single transactions. */
		private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

		private fun intent(context: Context, action: String, entryId: Long, requestCode: Int, cmd: EntryCommand? = null) =
			PendingIntent.getBroadcast(
				context,
				requestCode,
				Intent(context, ActionReceiver::class.java)
					.setAction(action)
					.putExtra(EXTRA_ENTRY, entryId)
					.apply { cmd?.let { putExtra(EXTRA_COMMAND, it.name) } },
				PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
			)

		/* request codes: entryId * 16 + command ordinal (< 8) or 8..9 for confirm/stop-at-last */
		fun command(context: Context, entryId: Long, cmd: EntryCommand): PendingIntent =
			intent(context, ACTION_COMMAND, entryId, (entryId * 16 + cmd.ordinal).toInt(), cmd)

		fun confirm(context: Context, entryId: Long): PendingIntent =
			intent(context, ACTION_CONFIRM, entryId, (entryId * 16 + 8).toInt())

		fun stopAtLastConfirmation(context: Context, entryId: Long): PendingIntent =
			intent(context, ACTION_STOP_AT_LAST, entryId, (entryId * 16 + 9).toInt())
	}
}
