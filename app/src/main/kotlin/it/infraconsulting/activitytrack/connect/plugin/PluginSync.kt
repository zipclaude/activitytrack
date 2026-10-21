/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.plugin

import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.ContactEntity
import it.infraconsulting.activitytrack.data.db.ContactPhoneEntity
import it.infraconsulting.activitytrack.data.db.RemoteItemEntity
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.domain.plugin.ImportTarget
import it.infraconsulting.activitytrack.domain.plugin.ImportedRecord
import it.infraconsulting.activitytrack.domain.plugin.PluginImport
import it.infraconsulting.activitytrack.domain.plugin.PluginSession
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/* What a sync did, for the report shown to the user. */
data class PluginSyncReport(
	val items: Int = 0,
	val clients: Int = 0,
	val contacts: Int = 0,
	val todos: Int = 0,
	/* to-dos created by this sync: id → title (notified) */
	val newTodos: List<Pair<Long, String>> = emptyList(),
	/* records not taken, with the reason */
	val skipped: List<Pair<String, String>> = emptyList(),
)

/*
 * Download of a company's plugin (issue #48 B3), in this order: identity
 * and lookups, remote lists (replaced at once), clients (new ones created,
 * existing ones left as the user keeps them), contacts (new ones, under
 * the client of their clientCode), to-dos (created when open, updated from
 * the remote side, done when the remote says so; ignored ones stay
 * ignored). Matching by external code, so a repeated sync changes nothing.
 */
class PluginSync(private val app: ActivityTrackApp) {
	private val c get() = app.container
	private val dao get() = c.pluginData

	suspend fun download(companyId: Long): PluginSyncReport = lock.withLock {
		val company = c.masterData.company(companyId) ?: error("company $companyId")
		val plugin = company.connectorId?.let { app.plugins.plugin(it) } ?: error("no plugin for ${company.name}")
		val env = app.plugins.env(company, plugin)
		env.missingOptions().takeIf { it.isNotEmpty() }?.let { m -> error("options missing: ${m.joinToString { it.key }}") }
		val client = app.plugins.client(company, plugin, env)
		PluginSession.prepare(plugin, client, env)
		var report = PluginSyncReport()

		if (plugin.lists.isNotEmpty()) {
			val items = PluginImport.lists(plugin, client, env)
			dao.replaceItems(companyId, plugin.id, items.map {
				RemoteItemEntity(companyId, plugin.id, it.list, it.parentId, it.id, it.name, it.clientCode, it.kind, it.active, it.order, it.extra.toString())
			})
			report = report.copy(items = items.size)
		}

		val clients = dao.clientsOf(companyId).toMutableList()
		fun clientOf(code: String?) = code?.lowercase()?.let { k -> clients.firstOrNull { it.externalCode?.lowercase() == k } }

		for (r in PluginImport.records(plugin, ImportTarget.CLIENTS, client, env)) {
			val code = r.text("code") ?: r.key
			if (clientOf(code) != null)
				continue
			val name = r.text("name") ?: continue
			val id = dao.insertClient(ClientEntity(companyId = companyId, name = name, externalCode = code))
			clients += ClientEntity(id = id, companyId = companyId, name = name, externalCode = code)
			report = report.copy(clients = report.clients + 1)
		}

		for (r in PluginImport.records(plugin, ImportTarget.CONTACTS, client, env)) {
			if (dao.contactByKey(companyId, r.key) != null)
				continue
			val owner = clientOf(r.text("clientCode"))
			val name = r.text("name")
			if (owner == null || name == null) {
				report = report.copy(skipped = report.skipped + ((name ?: r.key) to "client"))
				continue
			}
			val id = dao.insertContact(ContactEntity(clientId = owner.id, name = name, email = r.text("email"), externalKey = r.key))
			r.text("phone")?.let { dao.insertPhone(ContactPhoneEntity(id, it)) }
			report = report.copy(contacts = report.contacts + 1)
		}

		val zone = env.zone
		for (r in PluginImport.records(plugin, ImportTarget.TODOS, client, env)) {
			val existing = dao.todoByKey(companyId, r.key)
			if (existing == null && r.skipIfNew)
				continue
			val now = System.currentTimeMillis()
			val base = existing ?: TodoEntity(uid = UUID.randomUUID().toString(), companyId = companyId, title = "", createdUtcMs = now, updatedUtcMs = now)
			val title = r.text("title") ?: base.title.ifEmpty { r.key }
			val due = r.text("due")?.let { day(it, zone) }
			val plan = r.text("planStart")?.let { dateTime(it, zone) }
			val t = base.copy(
				externalKey = r.key,
				title = title,
				notes = r.text("notes") ?: base.notes,
				refs = r.text("refs") ?: base.refs,
				clientId = clientOf(r.text("clientCode"))?.id ?: base.clientId,
				priority = r.text("priority")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: base.priority,
				dueEpochDay = due?.first ?: base.dueEpochDay,
				dueTimeMin = due?.second ?: base.dueTimeMin,
				planEpochDay = plan?.toLocalDate()?.toEpochDay() ?: base.planEpochDay,
				planStartMin = plan?.let { it.hour * 60 + it.minute } ?: base.planStartMin,
				/* the remote closes a to-do; it never reopens one the user marked done */
				done = base.done || r.bool("done") == true,
				pluginFields = r.fields.takeIf { it.isNotEmpty() }?.let { JsonObject(it).toString() } ?: base.pluginFields,
				updatedUtcMs = if (existing == null) now else base.updatedUtcMs,
			)
			if (t.copy(updatedUtcMs = 0) != existing?.copy(updatedUtcMs = 0)) {
				if (existing == null) {
					val id = dao.insertTodo(t)
					report = report.copy(newTodos = report.newTodos + (id to t.title))
				} else {
					dao.updateTodo(t.copy(updatedUtcMs = now))
				}
			}
			report = report.copy(todos = report.todos + 1)
		}
		report
	}

	/* a due value: a date, or a date-time (day and minute of the day) */
	private fun day(s: String, zone: ZoneId): Pair<Long, Int?>? {
		runCatching { LocalDate.parse(s.take(10)) }.getOrNull()?.let { d ->
			val t = dateTime(s, zone)
			return d.toEpochDay() to t?.takeIf { s.length > 10 }?.let { it.hour * 60 + it.minute }
		}
		return null
	}

	private fun dateTime(s: String, zone: ZoneId): LocalDateTime? =
		runCatching { OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
			?: runCatching { LocalDateTime.parse(s) }.getOrNull()

	private companion object {
		val lock = Mutex()
	}
}
