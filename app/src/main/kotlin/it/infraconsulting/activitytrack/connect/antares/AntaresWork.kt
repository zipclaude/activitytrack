/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.antares

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.domain.interchange.AntaresConnector
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.notify.NotificationChannels
import it.infraconsulting.activitytrack.ui.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Antares in the background (issue #34, owner 2026-10-19):
 * - when an entry closes, the send is offered (or done at once) per the
 *   company's options; it waits in a WorkManager queue until the network
 *   is there and is retried on errors;
 * - every company logged in to Antares downloads the updates (services,
 *   projects, activity types, tickets) every 15 min to once a day;
 * - new tickets assigned to the user are notified.
 */
class AntaresSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val app = applicationContext as ActivityTrackApp
		val companyId = inputData.getLong(KEY_COMPANY, 0)
		val send = inputData.getBoolean(KEY_SEND, false)
		val entryId = inputData.getLong(KEY_ENTRY, 0).takeIf { it != 0L }
		val company = app.container.masterData.company(companyId)
		if (company?.connectorId != AntaresConnector.id || !app.antares.loggedIn(companyId))
			return Result.success()
		return try {
			val report = AntaresSync(app).sync(companyId, send, entryId)
			AntaresNotices.newTickets(app, company, report.newTickets)
			if (entryId != null && report.skipped.isNotEmpty())
				AntaresNotices.notSent(app, entryId, report.skipped.first())
			Result.success()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "sync of company $companyId failed (attempt $runAttemptCount)", e)
			when {
				!send -> Result.success()	/* the next period tries again */
				runAttemptCount < MAX_ATTEMPTS -> Result.retry()
				else -> {
					AntaresNotices.failed(app, company, e.message)
					Result.failure()
				}
			}
		}
	}

	companion object {
		private const val TAG = "AntaresSync"
		private const val KEY_COMPANY = "company"
		private const val KEY_SEND = "send"
		private const val KEY_ENTRY = "entry"
		private const val MAX_ATTEMPTS = 10
		private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

		/* Send now, or as soon as the network is back: the whole Sync, or one entry ([entryId]). */
		fun enqueueSend(context: Context, companyId: Long, entryId: Long?) {
			val request = OneTimeWorkRequestBuilder<AntaresSyncWorker>()
				.setInputData(workDataOf(KEY_COMPANY to companyId, KEY_SEND to true, KEY_ENTRY to (entryId ?: 0L)))
				.setConstraints(online)
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork("antares-send-$companyId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
		}

		/* The periodic download of every company: on with Antares and a login, off otherwise. */
		fun scheduleFetch(context: Context, companies: List<CompanyEntity>, loggedIn: (Long) -> Boolean) {
			val wm = WorkManager.getInstance(context)
			for (c in companies) {
				val name = "antares-fetch-${c.id}"
				if (c.connectorId != AntaresConnector.id || c.archived || !loggedIn(c.id)) {
					wm.cancelUniqueWork(name)
					continue
				}
				val minutes = AntaresConnector.fetchMinutes(config(c).optString(AntaresConnector.FETCH_MIN))
				val request = PeriodicWorkRequestBuilder<AntaresSyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
					.setInputData(workDataOf(KEY_COMPANY to c.id, KEY_SEND to false))
					.setConstraints(online)
					.build()
				wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
			}
		}
	}
}

internal fun config(c: CompanyEntity): JSONObject =
	runCatching { JSONObject(c.connectorConfig ?: "{}") }.getOrDefault(JSONObject())

/* An entry just closed whose send the user is asked about, inside the app. */
data class AntaresClosePrompt(val companyId: Long, val entryId: Long, val title: String, val entrySync: Boolean)

/*
 * Watches the entries that leave the open list: one that closed in an
 * Antares company is sent, or the send is offered (a dialog when the app
 * is in front, a notification otherwise). Also keeps the periodic
 * download in line with the companies (connector, options, login).
 */
class AntaresWatcher(private val app: ActivityTrackApp) {
	private val _prompt = MutableStateFlow<AntaresClosePrompt?>(null)
	val prompt: StateFlow<AntaresClosePrompt?> = _prompt
	@Volatile private var started = 0

	fun start(scope: CoroutineScope) {
		app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
			override fun onActivityStarted(activity: Activity) { started++ }
			override fun onActivityStopped(activity: Activity) { started-- }
			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
			override fun onActivityResumed(activity: Activity) {}
			override fun onActivityPaused(activity: Activity) {}
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
			override fun onActivityDestroyed(activity: Activity) {}
		})
		scope.launch {
			app.container.masterData.companies.collect { AntaresSyncWorker.scheduleFetch(app, it, app.antares::loggedIn) }
		}
		scope.launch {
			var open: Set<Long>? = null
			app.container.entries.openEntries.collect { list ->
				val now = list.map { it.id }.toSet()
				open?.let { before -> (before - now).forEach { runCatching { closed(it) }.onFailure { e -> Log.w("AntaresWatcher", "entry $it", e) } } }
				open = now
			}
		}
	}

	private suspend fun closed(entryId: Long) {
		val c = app.container
		val e = c.entries.entry(entryId).first() ?: return
		if (e.status != EntryStatus.CLOSED)
			return
		val company = c.masterData.company(e.companyId ?: return) ?: return
		if (company.connectorId != AntaresConnector.id || !app.antares.loggedIn(company.id))
			return
		val json = config(company)
		val fromTicket = e.todoId?.let { c.remote.todo(it) }?.let { it.companyId == company.id && it.externalKey != null } ?: false
		val mode = json.optString(AntaresConnector.CLOSE_MODE).ifEmpty { AntaresConnector.MODE_ASK }
		if (!AntaresConnector.offersSendOnClose(mode, json.optString(AntaresConnector.CLOSE_SCOPE), fromTicket))
			return
		val entrySync = json.optString(AntaresConnector.CLOSE_SYNC) == AntaresConnector.SYNC_ENTRY
		val title = e.clientId?.let { c.masterData.client(it)?.name } ?: company.name
		when {
			mode == AntaresConnector.MODE_ALWAYS -> AntaresSyncWorker.enqueueSend(app, company.id, entryId.takeIf { entrySync })
			started > 0 -> _prompt.value = AntaresClosePrompt(company.id, entryId, title, entrySync)
			else -> AntaresNotices.ask(app, AntaresClosePrompt(company.id, entryId, title, entrySync))
		}
	}

	/* The answer to the in-app question. */
	fun answer(send: Boolean) {
		val p = _prompt.value ?: return
		_prompt.value = null
		if (send)
			AntaresSyncWorker.enqueueSend(app, p.companyId, p.entryId.takeIf { p.entrySync })
	}
}

/* The question after an entry closed with the app in front: send now (queued when offline) or not. */
@androidx.compose.runtime.Composable
fun AntaresCloseDialog(watcher: AntaresWatcher) {
	val p by watcher.prompt.collectAsState()
	p?.let {
		androidx.compose.material3.AlertDialog(
			onDismissRequest = { watcher.answer(false) },
			title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.antares_send_question)) },
			text = { androidx.compose.material3.Text(it.title) },
			confirmButton = {
				androidx.compose.material3.TextButton(onClick = { watcher.answer(true) }) {
					androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.antares_send_now))
				}
			},
			dismissButton = {
				androidx.compose.material3.TextButton(onClick = { watcher.answer(false) }) {
					androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_no))
				}
			},
		)
	}
}

/* "Send" on the question notification, the app being in the background. */
class AntaresSendReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val company = intent.getLongExtra(EXTRA_COMPANY, 0)
		val entry = intent.getLongExtra(EXTRA_ENTRY, 0)
		AntaresSyncWorker.enqueueSend(context, company, entry.takeIf { intent.getBooleanExtra(EXTRA_ENTRY_SYNC, false) })
		context.getSystemService(NotificationManager::class.java).cancel(AntaresNotices.askId(entry))
	}

	companion object {
		const val EXTRA_COMPANY = "company"
		const val EXTRA_ENTRY = "entry"
		const val EXTRA_ENTRY_SYNC = "entry_sync"
	}
}

internal object AntaresNotices {
	private const val CHANNEL = NotificationChannels.REMOTE

	fun askId(entryId: Long) = (0x4A000000 + (entryId and 0xFFFFFF)).toInt()

	fun ask(app: ActivityTrackApp, p: AntaresClosePrompt) {
		val send = PendingIntent.getBroadcast(
			app, askId(p.entryId),
			Intent(app, AntaresSendReceiver::class.java)
				.putExtra(AntaresSendReceiver.EXTRA_COMPANY, p.companyId)
				.putExtra(AntaresSendReceiver.EXTRA_ENTRY, p.entryId)
				.putExtra(AntaresSendReceiver.EXTRA_ENTRY_SYNC, p.entrySync),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		notify(
			app, askId(p.entryId), app.getString(R.string.antares_send_question), p.title,
			EntryLinks.openScreen(app, Screen.Entry(p.entryId), askId(p.entryId)),
			Notification.Action.Builder(null, app.getString(R.string.antares_send_now), send).build(),
		)
	}

	fun newTickets(app: ActivityTrackApp, company: CompanyEntity, tickets: List<Pair<Long, String>>) {
		/* the first download after the login finds them all: one summary instead */
		if (tickets.size > 3) {
			val id = (0x4B000000 + (company.id and 0xFFFFFF)).toInt()
			notify(
				app, id, app.getString(R.string.antares_new_ticket, company.name),
				app.resources.getQuantityString(R.plurals.antares_new_tickets, tickets.size, tickets.size),
				EntryLinks.openScreen(app, Screen.Todos, id),
			)
			return
		}
		for ((todoId, title) in tickets)
			notify(
				app, (0x4B000000 + (todoId and 0xFFFFFF)).toInt(), app.getString(R.string.antares_new_ticket, company.name), title,
				EntryLinks.openScreen(app, Screen.Todo(todoId), (0x4B000000 + (todoId and 0xFFFFFF)).toInt()),
			)
	}

	fun notSent(app: ActivityTrackApp, entryId: Long, skipped: Pair<String, String>) {
		val why = app.getString(if (skipped.second == "activity type") R.string.antares_missing_type else R.string.antares_missing_portfolio)
		notify(
			app, askId(entryId), app.getString(R.string.antares_not_sent_title), "${skipped.first}: $why",
			EntryLinks.openScreen(app, Screen.Entry(entryId), askId(entryId)),
		)
	}

	fun failed(app: ActivityTrackApp, company: CompanyEntity, message: String?) {
		val id = (0x4C000000 + (company.id and 0xFFFFFF)).toInt()
		notify(
			app, id, app.getString(R.string.antares_send_failed, company.name), message.orEmpty(),
			EntryLinks.openScreen(app, Screen.Company(company.id), id),
		)
	}

	private fun notify(app: ActivityTrackApp, id: Int, title: String, text: String, open: PendingIntent, vararg actions: Notification.Action) {
		val b = Notification.Builder(app, CHANNEL)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(title)
			.setContentText(text)
			.setContentIntent(open)
			.setAutoCancel(true)
		actions.forEach { b.addAction(it) }
		app.getSystemService(NotificationManager::class.java).notify(id, b.build())
	}
}
