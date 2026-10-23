/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.quick

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/*
 * The elapsed time of the 4×2 widget moves once a minute (issue #62,
 * owner answer 3): while an entry runs, one inexact alarm at the next
 * whole minute refreshes the widgets and re-arms itself; QuickWidgets
 * cancels it when nothing runs. RTC, not a wakeup: with the screen off
 * the widget is not visible and Doze would throttle it anyway; the
 * pending alarm fires as soon as the device wakes. Every QuickWidgets
 * refresh re-arms it, so a process restart (boot, notification sync)
 * brings it back too.
 */
object WidgetTicker {
	fun arm(context: Context, active: Boolean) {
		val am = context.getSystemService(AlarmManager::class.java) ?: return
		val pi = PendingIntent.getBroadcast(
			context, 3,
			Intent(context, WidgetTickReceiver::class.java),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		if (!active) {
			am.cancel(pi)
			return
		}
		am.setAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() / 60_000 * 60_000 + 60_000, pi)
	}
}

class WidgetTickReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val pending = goAsync()
		CoroutineScope(Dispatchers.Default).launch {
			try {
				QuickWidgets.refresh(context)
			} finally {
				pending.finish()
			}
		}
	}
}
