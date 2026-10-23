/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import it.infraconsulting.activitytrack.ActivityTrackApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/* The reminder alarm fired: post what is due and re-arm. */
class ReminderReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val app = context.applicationContext as ActivityTrackApp
		val pending = goAsync()
		scope.launch {
			try {
				app.reminders.fire()
			} finally {
				pending.finish()
			}
		}
	}

	private companion object {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	}
}

/*
 * Re-arms the reminder alarm after reboot, app update and wall-clock or
 * time-zone changes (alarms are dropped on reboot; RTC times move with the
 * clock). Starting the process also restores the ongoing notifications.
 */
class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			Intent.ACTION_BOOT_COMPLETED,
			Intent.ACTION_MY_PACKAGE_REPLACED,
			Intent.ACTION_TIME_CHANGED,
			Intent.ACTION_TIMEZONE_CHANGED -> {}
			else -> return
		}
		val app = context.applicationContext as ActivityTrackApp
		val pending = goAsync()
		scope.launch {
			try {
				app.reminders.reschedule()
				/* widgets lost their minute ticker with the alarms: redraw and re-arm (issue #62) */
				it.infraconsulting.activitytrack.quick.QuickWidgets.refresh(context)
			} finally {
				pending.finish()
			}
		}
	}

	private companion object {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	}
}
