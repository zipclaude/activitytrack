/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.plugin

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
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.plugin.Auth
import it.infraconsulting.activitytrack.domain.plugin.Plugin
import it.infraconsulting.activitytrack.domain.plugin.eval
import it.infraconsulting.activitytrack.domain.plugin.text
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.notify.NotificationChannels
import it.infraconsulting.activitytrack.ui.Screen
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * A company's plugin in the background (issue #48 B5; issue #34 for
 * Antares, now from the descriptor's "schedule"): the send when an entry
 * closes, queued until the network is back and retried; the periodic
 * download every 15 min to once a day; new to-dos notified.
 */
class PluginSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val app = applicationContext as ActivityTrackApp
		val companyId = inputData.getLong(KEY_COMPANY, 0)
		val send = inputData.getBoolean(KEY_SEND, false)
		val entryId = inputData.getLong(KEY_ENTRY, 0).takeIf { it != 0L }
		val company = app.container.masterData.company(companyId) ?: return Result.success()
		val plugin = company.connectorId?.let { app.plugins.plugin(it) } ?: return Result.success()
		if (!PluginState.ready(app, company, plugin))
			return Result.success()
		return try {
			val report = PluginSync(app).sync(companyId, send, entryId)
			PluginNotices.newTodos(app, company, report.newTodos)
			if (entryId != null && report.skipped.isNotEmpty())
				PluginNotices.notSent(app, entryId, report.skipped.first())
			Result.success()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "sync of company $companyId failed (attempt $runAttemptCount)", e)
			when {
				!send -> Result.success()	/* the next period tries again */
				runAttemptCount < MAX_ATTEMPTS -> Result.retry()
				else -> {
					PluginNotices.failed(app, company, e.message)
					Result.failure()
				}
			}
		}
	}

	companion object {
		private const val TAG = "PluginSync"
		private const val KEY_COMPANY = "company"
		private const val KEY_SEND = "send"
		private const val KEY_ENTRY = "entry"
		private const val MAX_ATTEMPTS = 10
		private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

		/* Send now, or as soon as the network is back: the whole sync, or one entry ([entryId]). */
		fun enqueueSend(context: Context, companyId: Long, entryId: Long?) {
			val request = OneTimeWorkRequestBuilder<PluginSyncWorker>()
				.setInputData(workDataOf(KEY_COMPANY to companyId, KEY_SEND to true, KEY_ENTRY to (entryId ?: 0L)))
				.setConstraints(online)
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork("plugin-send-$companyId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
		}

		/* The periodic download of every company with a usable plugin; off otherwise. */
		fun scheduleFetch(app: ActivityTrackApp, companies: List<CompanyEntity>, plugins: Map<String, Plugin>) {
			val wm = WorkManager.getInstance(app)
			for (c in companies) {
				val name = "plugin-fetch-${c.id}"
				val plugin = c.connectorId?.let { plugins[it] }
				if (plugin == null || c.archived || !PluginState.ready(app, c, plugin)) {
					wm.cancelUniqueWork(name)
					continue
				}
				val request = PeriodicWorkRequestBuilder<PluginSyncWorker>(PluginState.fetchMinutes(app, c, plugin).toLong(), TimeUnit.MINUTES)
					.setInputData(workDataOf(KEY_COMPANY to c.id, KEY_SEND to false))
					.setConstraints(online)
					.build()
				wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
			}
		}
	}
}

/* Whether a company's plugin can run, and its schedule choices. */
object PluginState {
	/* logged in (OAuth) or with every required option set */
	fun ready(app: ActivityTrackApp, company: CompanyEntity, plugin: Plugin): Boolean {
		val env = app.plugins.env(company, plugin)
		if (env.missingOptions().isNotEmpty())
			return false
		return plugin.auth !is Auth.OAuth2Pkce || app.oauth.loggedIn(company.id, plugin.id)
	}

	private fun value(app: ActivityTrackApp, company: CompanyEntity, plugin: Plugin, pick: (it.infraconsulting.activitytrack.domain.plugin.Schedule) -> it.infraconsulting.activitytrack.domain.plugin.Expr?): String? =
		plugin.schedule?.let(pick)?.let { text(it.eval(null, app.plugins.env(company, plugin))) }

	/* WorkManager runs periodic work at most every 15 minutes; default 1 h */
	fun fetchMinutes(app: ActivityTrackApp, company: CompanyEntity, plugin: Plugin): Int =
		value(app, company, plugin) { it.fetchEveryMin }?.toDoubleOrNull()?.toInt()?.coerceIn(15, 1440) ?: 60

	/* "ask" (default), "always" or "off" */
	fun closeMode(app: ActivityTrackApp, company: CompanyEntity, plugin: Plugin): String =
		value(app, company, plugin) { it.onCloseMode }?.takeIf { it in setOf("ask", "always", "off") } ?: if (plugin.export == null) "off" else "ask"

	/* "todos" (default: entries started from an imported to-do) or "all" */
	fun closeScope(app: ActivityTrackApp, company: CompanyEntity, plugin: Plugin): String =
		value(app, company, plugin) { it.onCloseScope } ?: "todos"

	/* "full" sync (default) or the closed "entry" only */
	fun closeEntryOnly(app: ActivityTrackApp, company: CompanyEntity, plugin: Plugin): Boolean =
		value(app, company, plugin) { it.onCloseSync } == "entry"
}

/* An entry just closed whose send the user is asked about, inside the app. */
data class PluginClosePrompt(val companyId: Long, val entryId: Long, val title: String, val service: String, val entryOnly: Boolean)

/*
 * Watches the entries that leave the open list: one that closed in a
 * company with a plugin is sent, or the send is offered (a dialog when the
 * app is in front, a notification otherwise). Also keeps the periodic
 * download in line with the companies and the plugins loaded.
 */
class PluginWatcher(private val app: ActivityTrackApp) {
	private val _prompt = MutableStateFlow<PluginClosePrompt?>(null)
	val prompt: StateFlow<PluginClosePrompt?> = _prompt
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
			app.container.masterData.companies.combine(app.plugins.plugins) { c, p -> c to p }
				.collect { (companies, plugins) -> runCatching { PluginSyncWorker.scheduleFetch(app, companies, plugins) } }
		}
		scope.launch {
			var open: Set<Long>? = null
			app.container.entries.openEntries.collect { list ->
				val now = list.map { it.id }.toSet()
				open?.let { before -> (before - now).forEach { runCatching { closed(it) }.onFailure { e -> Log.w("PluginWatcher", "entry $it", e) } } }
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
		val plugin = company.connectorId?.let { app.plugins.plugin(it) } ?: return
		if (plugin.export == null || !PluginState.ready(app, company, plugin))
			return
		val mode = PluginState.closeMode(app, company, plugin)
		val fromTodo = e.todoId?.let { c.pluginData.todo(it) }?.let { it.companyId == company.id && it.externalKey != null } ?: false
		if (mode == "off" || (!fromTodo && PluginState.closeScope(app, company, plugin) != "all"))
			return
		val entryOnly = PluginState.closeEntryOnly(app, company, plugin)
		val title = e.clientId?.let { c.masterData.client(it)?.name } ?: company.name
		val p = PluginClosePrompt(company.id, entryId, title, plugin.name, entryOnly)
		when {
			mode == "always" -> PluginSyncWorker.enqueueSend(app, company.id, entryId.takeIf { entryOnly })
			started > 0 -> _prompt.value = p
			else -> PluginNotices.ask(app, p)
		}
	}

	/* The answer to the in-app question. */
	fun answer(send: Boolean) {
		val p = _prompt.value ?: return
		_prompt.value = null
		if (send)
			PluginSyncWorker.enqueueSend(app, p.companyId, p.entryId.takeIf { p.entryOnly })
	}
}

/* The question after an entry closed with the app in front: send now (queued when offline) or not. */
@androidx.compose.runtime.Composable
fun PluginCloseDialog(watcher: PluginWatcher) {
	val p = watcher.prompt.collectAsState().value
	p?.let {
		androidx.compose.material3.AlertDialog(
			onDismissRequest = { watcher.answer(false) },
			title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.plugin_send_question, it.service)) },
			text = { androidx.compose.material3.Text(it.title) },
			confirmButton = {
				androidx.compose.material3.TextButton(onClick = { watcher.answer(true) }) {
					androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.plugin_send_now))
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
class PluginSendReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val company = intent.getLongExtra(EXTRA_COMPANY, 0)
		val entry = intent.getLongExtra(EXTRA_ENTRY, 0)
		PluginSyncWorker.enqueueSend(context, company, entry.takeIf { intent.getBooleanExtra(EXTRA_ENTRY_ONLY, false) })
		context.getSystemService(NotificationManager::class.java).cancel(PluginNotices.askId(entry))
	}

	companion object {
		const val EXTRA_COMPANY = "company"
		const val EXTRA_ENTRY = "entry"
		const val EXTRA_ENTRY_ONLY = "entry_only"
	}
}

internal object PluginNotices {
	private const val CHANNEL = NotificationChannels.REMOTE

	fun askId(entryId: Long) = (0x4A000000 + (entryId and 0xFFFFFF)).toInt()

	fun ask(app: ActivityTrackApp, p: PluginClosePrompt) {
		val send = PendingIntent.getBroadcast(
			app, askId(p.entryId),
			Intent(app, PluginSendReceiver::class.java)
				.putExtra(PluginSendReceiver.EXTRA_COMPANY, p.companyId)
				.putExtra(PluginSendReceiver.EXTRA_ENTRY, p.entryId)
				.putExtra(PluginSendReceiver.EXTRA_ENTRY_ONLY, p.entryOnly),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		notify(
			app, askId(p.entryId), app.getString(R.string.plugin_send_question, p.service), p.title,
			EntryLinks.openScreen(app, Screen.Entry(p.entryId), askId(p.entryId)),
			Notification.Action.Builder(null, app.getString(R.string.plugin_send_now), send).build(),
		)
	}

	fun newTodos(app: ActivityTrackApp, company: CompanyEntity, todos: List<Pair<Long, String>>) {
		/* the first download finds them all: one summary instead */
		if (todos.size > 3) {
			val id = (0x4B000000 + (company.id and 0xFFFFFF)).toInt()
			notify(
				app, id, app.getString(R.string.plugin_new_todo, company.name),
				app.resources.getQuantityString(R.plurals.plugin_new_todos, todos.size, todos.size),
				EntryLinks.openScreen(app, Screen.Todos, id),
			)
			return
		}
		for ((todoId, title) in todos) {
			val id = (0x4B000000 + (todoId and 0xFFFFFF)).toInt()
			notify(app, id, app.getString(R.string.plugin_new_todo, company.name), title, EntryLinks.openScreen(app, Screen.Todo(todoId), id))
		}
	}

	fun notSent(app: ActivityTrackApp, entryId: Long, skipped: Pair<String, String>) {
		notify(
			app, askId(entryId), app.getString(R.string.plugin_not_sent_title), app.getString(R.string.plugin_missing_field, skipped.first, skipped.second),
			EntryLinks.openScreen(app, Screen.Entry(entryId), askId(entryId)),
		)
	}

	fun failed(app: ActivityTrackApp, company: CompanyEntity, message: String?) {
		val id = (0x4C000000 + (company.id and 0xFFFFFF)).toInt()
		notify(
			app, id, app.getString(R.string.plugin_send_failed, company.name), message.orEmpty(),
			EntryLinks.openScreen(app, Screen.Company(company.id), id),
		)
	}

	private fun notify(app: ActivityTrackApp, id: Int, title: String, text: String, open: PendingIntent, vararg actions: Notification.Action) {
		val b = Notification.Builder(app, CHANNEL)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(title)
			.setContentText(text)
			.setStyle(Notification.BigTextStyle().bigText(text))
			.setContentIntent(open)
			.setAutoCancel(true)
		actions.forEach { b.addAction(it) }
		app.getSystemService(NotificationManager::class.java).notify(id, b.build())
	}
}
