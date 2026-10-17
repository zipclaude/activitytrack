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
						entries.apply(entryId, cmd, intent.at())
					}
					ACTION_CONFIRM -> entries.confirm(entryId)
					ACTION_STOP_AT_LAST -> entries.stopAtLastConfirmation(entryId)
					ACTION_IGNORE_MOVEMENT ->
						app.tracking.ignoreMovement(intent.getLongExtra(EXTRA_SEGMENT, -1), System.currentTimeMillis())
					ACTION_ARRIVE_CLIENT ->
						entries.arriveAtClient(entryId, intent.getLongExtra(EXTRA_CLIENT, -1), intent.at() ?: System.currentTimeMillis())
					ACTION_DISMISS -> {}
				}
				/* the answered reminder or prompt goes away; ongoing/to-complete are re-synced */
				val nm = context.getSystemService(NotificationManager::class.java)
				for (slot in ANSWERED)
					nm.cancel(NotificationIds.of(entryId, slot))
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
		private const val ACTION_IGNORE_MOVEMENT = "it.infraconsulting.activitytrack.action.IGNORE_MOVEMENT"
		private const val ACTION_ARRIVE_CLIENT = "it.infraconsulting.activitytrack.action.ARRIVE_CLIENT"
		private const val ACTION_DISMISS = "it.infraconsulting.activitytrack.action.DISMISS"
		private const val EXTRA_ENTRY = "entry"
		private const val EXTRA_COMMAND = "command"
		private const val EXTRA_AT = "at"
		private const val EXTRA_CLIENT = "client"
		private const val EXTRA_SEGMENT = "segment"

		/* any answer settles the reminders and prompts of the entry */
		private val ANSWERED = listOf(
			NotificationIds.CONFIRM, NotificationIds.PAUSE, NotificationIds.MOVEMENT, NotificationIds.ARRIVAL,
		)

		private fun Intent.at(): Long? = getLongExtra(EXTRA_AT, -1).takeIf { it >= 0 }

		/* Receivers have ~10 s; repository calls are short single transactions. */
		private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

		private fun intent(
			context: Context,
			action: String,
			entryId: Long,
			requestCode: Int,
			extras: Intent.() -> Unit = {},
		) =
			PendingIntent.getBroadcast(
				context,
				requestCode,
				Intent(context, ActionReceiver::class.java)
					.setAction(action)
					.putExtra(EXTRA_ENTRY, entryId)
					.apply(extras),
				PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
			)

		/*
		 * request codes: entryId * 16 + slot; 0..6 command ordinals, 7 dismiss,
		 * 8 confirm, 9 stop at last confirmation, 10..15 movement/arrival answers
		 */
		fun command(context: Context, entryId: Long, cmd: EntryCommand): PendingIntent =
			intent(context, ACTION_COMMAND, entryId, (entryId * 16 + cmd.ordinal).toInt()) { putExtra(EXTRA_COMMAND, cmd.name) }

		/* Backdated command: departure (TRAVEL, STOP) or arrival (WORK, STOP at home). */
		fun commandAt(context: Context, entryId: Long, cmd: EntryCommand, atMs: Long, arrival: Boolean = false): PendingIntent {
			val slot = when (cmd) {
				EntryCommand.TRAVEL -> 10
				EntryCommand.WORK -> 13
				else -> if (arrival) 14 else 11
			}
			return intent(context, ACTION_COMMAND, entryId, (entryId * 16 + slot).toInt()) {
				putExtra(EXTRA_COMMAND, cmd.name)
				putExtra(EXTRA_AT, atMs)
			}
		}

		fun ignoreMovement(context: Context, entryId: Long, segmentId: Long): PendingIntent =
			intent(context, ACTION_IGNORE_MOVEMENT, entryId, (entryId * 16 + 12).toInt()) { putExtra(EXTRA_SEGMENT, segmentId) }

		fun arriveAtClient(context: Context, entryId: Long, clientId: Long, atMs: Long): PendingIntent =
			intent(context, ACTION_ARRIVE_CLIENT, entryId, (entryId * 16 + 15).toInt()) {
				putExtra(EXTRA_CLIENT, clientId)
				putExtra(EXTRA_AT, atMs)
			}

		fun dismissArrival(context: Context, entryId: Long): PendingIntent =
			intent(context, ACTION_DISMISS, entryId, (entryId * 16 + 7).toInt())

		fun confirm(context: Context, entryId: Long): PendingIntent =
			intent(context, ACTION_CONFIRM, entryId, (entryId * 16 + 8).toInt())

		fun stopAtLastConfirmation(context: Context, entryId: Long): PendingIntent =
			intent(context, ACTION_STOP_AT_LAST, entryId, (entryId * 16 + 9).toInt())
	}
}
