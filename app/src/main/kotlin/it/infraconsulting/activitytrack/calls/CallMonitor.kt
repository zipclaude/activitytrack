/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.CallDirection
import it.infraconsulting.activitytrack.data.db.CallLogEntity
import it.infraconsulting.activitytrack.domain.calendar.ItalianHolidays
import it.infraconsulting.activitytrack.domain.calls.CallRules
import it.infraconsulting.activitytrack.domain.calls.CallSettings
import it.infraconsulting.activitytrack.domain.calls.RunningEntry
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.notify.NotificationChannels
import it.infraconsulting.activitytrack.ui.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/*
 * Call recognition (issue #28): when the phone goes back to idle after a
 * call, the latest call-log row tells number, direction, start and length.
 * A number of a contact, within working hours (or always, when on call),
 * is recorded, may pause the running entry of another client and may be
 * proposed as an entry.
 * Needs READ_PHONE_STATE (the broadcast) and READ_CALL_LOG (the number).
 */
class CallReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED)
			return
		val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
		val prefs = context.getSharedPreferences("calls", Context.MODE_PRIVATE)
		/* a call happened if the phone was off hook since the last idle */
		if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
			prefs.edit().putBoolean("offhook", true).apply()
			return
		}
		if (state != TelephonyManager.EXTRA_STATE_IDLE || !prefs.getBoolean("offhook", false))
			return
		prefs.edit().putBoolean("offhook", false).apply()
		val pending = goAsync()
		scope.launch {
			try {
				/* the dialer writes the call log a moment after hanging up */
				delay(2_500)
				CallMonitor.process(context.applicationContext as ActivityTrackApp)
			} catch (e: Exception) {
				Log.w("Calls", "call not processed", e)
			} finally {
				pending.finish()
			}
		}
	}

	private companion object {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	}
}

object CallMonitor {
	private const val CHANNEL = NotificationChannels.CONFIRM

	fun permitted(context: Context): Boolean =
		listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
			.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

	suspend fun process(app: ActivityTrackApp) {
		val c = app.container
		val s = c.settings.current()
		if (!(s.callsIncoming || s.callsOutgoing) || !permitted(app))
			return
		val call = lastCall(app) ?: return
		val contact = c.calls.contactOf(call.number) ?: return
		val client = c.masterData.client(contact.contact.clientId) ?: return
		val zone = ZoneId.systemDefault()
		val start = Instant.ofEpochMilli(call.startMs)
		val end = start.plusSeconds(call.seconds)
		val running = c.entries.openEntries.first().filter { it.status == EntryStatus.ACTIVE }
			.map { RunningEntry(it.id, it.clientId, it.mode == SegmentKind.WORK) }
		val outcome = CallRules.decide(
			call.incoming, start, end, client.id, running,
			CallSettings(s.callsIncoming, s.callsOutgoing, s.callAutoPause, s.callProposeEntry),
			s.callsAlways || workingHours(app, start, zone), zone,
		) ?: return
		val fresh = c.calls.record(
			CallLogEntity(
				contactId = contact.contact.id, clientId = client.id,
				direction = if (call.incoming) CallDirection.INCOMING else CallDirection.OUTGOING,
				startUtcMs = start.toEpochMilli(), endUtcMs = end.toEpochMilli(),
			),
		)
		if (!fresh)
			return
		outcome.pauseEntryId?.let {
			c.entries.insertCallPause(it, start.toEpochMilli(), end.toEpochMilli(), app.getString(R.string.call_pause_note, client.name))
		}
		if (outcome.propose)
			propose(app, client.id, contact.contact.name, client.name, outcome.proposedStart!!.toEpochMilli(), outcome.proposedEnd!!.toEpochMilli())
	}

	private data class Call(val number: String, val incoming: Boolean, val startMs: Long, val seconds: Long)

	/* the latest answered call of the last minutes */
	private fun lastCall(context: Context): Call? {
		val cursor = context.contentResolver.query(
			CallLog.Calls.CONTENT_URI,
			arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
			null, null, CallLog.Calls.DATE + " DESC",
		) ?: return null
		cursor.use {
			if (!it.moveToFirst())
				return null
			val type = it.getInt(1)
			val seconds = it.getLong(3)
			val date = it.getLong(2)
			if (seconds <= 0 || System.currentTimeMillis() - (date + seconds * 1000) > 10 * 60_000)
				return null
			return when (type) {
				CallLog.Calls.INCOMING_TYPE -> Call(it.getString(0).orEmpty(), true, date, seconds)
				CallLog.Calls.OUTGOING_TYPE -> Call(it.getString(0).orEmpty(), false, date, seconds)
				else -> null
			}
		}
	}

	/* within a working interval of that weekday, not on a holiday or day off */
	private suspend fun workingHours(app: ActivityTrackApp, t: Instant, zone: ZoneId): Boolean {
		val local = t.atZone(zone)
		val day = local.toLocalDate()
		if (ItalianHolidays.isHoliday(day) || day in app.container.masterData.daysOffBetween(day, day))
			return false
		val minute = local.hour * 60 + local.minute
		return app.container.masterData.schedule.first()
			.any { it.dayOfWeek == day.dayOfWeek.value && minute >= it.startMin && minute < it.endMin }
	}

	private fun propose(app: ActivityTrackApp, clientId: Long, contact: String, client: String, startMs: Long, endMs: Long) {
		val zone = ZoneId.systemDefault()
		fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
		val open = EntryLinks.openScreen(app, Screen.StartFromCall(clientId, startMs, endMs, contact), (startMs / 1000).toInt())
		val n = Notification.Builder(app, CHANNEL)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(app.getString(R.string.call_propose_title, contact, client))
			.setContentText(app.getString(R.string.call_propose_text, hhmm(startMs), hhmm(endMs)))
			.setContentIntent(open)
			.setAutoCancel(true)
			.addAction(Notification.Action.Builder(null, app.getString(R.string.call_propose_action), open).build())
			.build()
		app.getSystemService(NotificationManager::class.java).notify((startMs / 1000).toInt(), n)
	}
}
