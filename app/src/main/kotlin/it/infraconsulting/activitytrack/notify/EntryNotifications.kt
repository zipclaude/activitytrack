/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import it.infraconsulting.activitytrack.MainActivity
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind

/* An open entry as notifications need it. */
data class EntryInfo(
	val entry: TimeEntryEntity,
	val title: String,
	val segments: List<SegmentEntity>,
) {
	val id: Long get() = entry.id
	val openSince: Long? get() = segments.lastOrNull { it.endUtcMs == null }?.startUtcMs
}

/*
 * Notification ids: entryId * 16 + slot (DESIGN §7), so every entry owns a
 * small range and per-entry notifications never collide.
 */
object NotificationIds {
	const val ONGOING = 0
	const val CONFIRM = 1
	const val PAUSE = 2
	const val TO_COMPLETE = 6
	const val DAY_SUMMARY = 5	/* global, entry 0 */

	fun of(entryId: Long, slot: Int): Int = (entryId * 16 + slot).toInt()
}

/* Actions shown on the ongoing notification per mode (the state machine stays the authority). */
fun ongoingActions(mode: SegmentKind?): List<EntryCommand> = when (mode) {
	SegmentKind.WORK -> listOf(EntryCommand.PAUSE, EntryCommand.TRAVEL, EntryCommand.STOP)
	SegmentKind.TRAVEL -> listOf(EntryCommand.WORK, EntryCommand.PAUSE, EntryCommand.STOP)
	SegmentKind.PAUSE -> listOf(EntryCommand.WORK, EntryCommand.STOP)
	null -> emptyList()
}

object EntryNotifications {
	fun openAppIntent(context: Context, requestCode: Int = 0): PendingIntent =
		PendingIntent.getActivity(
			context,
			requestCode,
			Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)

	fun commandLabel(context: Context, cmd: EntryCommand, mode: SegmentKind?): String = context.getString(
		when (cmd) {
			EntryCommand.WORK -> if (mode == SegmentKind.PAUSE) R.string.cmd_resume else R.string.cmd_work
			EntryCommand.TRAVEL -> R.string.cmd_travel
			EntryCommand.PAUSE -> R.string.cmd_pause
			EntryCommand.SUSPEND -> R.string.cmd_suspend
			EntryCommand.STOP -> R.string.cmd_stop
			EntryCommand.COMPLETE -> R.string.cmd_complete
			EntryCommand.REOPEN -> R.string.cmd_reopen
		},
	)

	fun modeText(context: Context, mode: SegmentKind?): String = context.getString(
		when (mode) {
			SegmentKind.TRAVEL -> R.string.mode_travel
			SegmentKind.PAUSE -> R.string.mode_pause
			else -> R.string.mode_work
		},
	)

	/* Ongoing notification of an ACTIVE entry: mode, chronometer of the open segment, commands. */
	fun ongoing(context: Context, info: EntryInfo): Notification {
		val e = info.entry
		val b = Notification.Builder(context, NotificationChannels.ONGOING)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(info.title)
			.setContentText(modeText(context, e.mode))
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setShowWhen(true)
			.setUsesChronometer(true)
			.setWhen(info.openSince ?: System.currentTimeMillis())
			.setCategory(Notification.CATEGORY_STOPWATCH)
			.setContentIntent(openAppIntent(context))
		e.activity?.let { b.setSubText(it) }
		for (cmd in ongoingActions(e.mode))
			b.addAction(
				Notification.Action.Builder(
					null,
					commandLabel(context, cmd, e.mode),
					ActionReceiver.command(context, e.id, cmd),
				).build(),
			)
		return b.build()
	}

	/* Persistent reminder while the entry is TO_COMPLETE (SPEC §5). */
	fun toComplete(context: Context, info: EntryInfo): Notification =
		Notification.Builder(context, NotificationChannels.TO_COMPLETE)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.notif_to_complete_title))
			.setContentText(info.title)
			.setOnlyAlertOnce(true)
			.setContentIntent(EntryLinks.openEntry(context, info.id))
			.build()

	/*
	 * Brings the posted notifications in line with the open entries:
	 * ongoing for ACTIVE, to-complete for TO_COMPLETE, everything else
	 * cancelled (including reminders of entries no longer ACTIVE).
	 */
	fun sync(context: Context, infos: List<EntryInfo>, previous: Set<Long>) {
		val nm = context.getSystemService(NotificationManager::class.java)
		if (!nm.areNotificationsEnabled())
			return
		val current = infos.associateBy { it.id }
		for (id in previous - current.keys)
			cancelAll(nm, id)
		for (info in infos) {
			val id = info.id
			when (info.entry.status) {
				EntryStatus.ACTIVE -> {
					nm.notify(NotificationIds.of(id, NotificationIds.ONGOING), ongoing(context, info))
					nm.cancel(NotificationIds.of(id, NotificationIds.TO_COMPLETE))
					/* a mode change answers the pending reminder of the other kind */
					if (info.entry.mode == SegmentKind.PAUSE)
						nm.cancel(NotificationIds.of(id, NotificationIds.CONFIRM))
					else
						nm.cancel(NotificationIds.of(id, NotificationIds.PAUSE))
				}
				EntryStatus.TO_COMPLETE -> {
					cancelAll(nm, id)
					nm.notify(NotificationIds.of(id, NotificationIds.TO_COMPLETE), toComplete(context, info))
				}
				else -> cancelAll(nm, id)
			}
		}
	}

	private fun cancelAll(nm: NotificationManager, entryId: Long) {
		for (slot in listOf(NotificationIds.ONGOING, NotificationIds.CONFIRM, NotificationIds.PAUSE, NotificationIds.TO_COMPLETE))
			nm.cancel(NotificationIds.of(entryId, slot))
	}
}
