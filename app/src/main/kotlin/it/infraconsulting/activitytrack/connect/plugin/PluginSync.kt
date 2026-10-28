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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import it.infraconsulting.activitytrack.data.db.EntryMaterialEntity
import it.infraconsulting.activitytrack.data.db.RemoteRecordEntity
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.plugin.ExportAction
import it.infraconsulting.activitytrack.domain.plugin.ExportSegment
import it.infraconsulting.activitytrack.domain.plugin.PluginDataException
import it.infraconsulting.activitytrack.domain.plugin.PluginEnv
import it.infraconsulting.activitytrack.domain.plugin.PluginExport
import it.infraconsulting.activitytrack.domain.plugin.RemoteItem
import it.infraconsulting.activitytrack.domain.plugin.PluginHttpException
import it.infraconsulting.activitytrack.domain.plugin.SentRecord
import it.infraconsulting.activitytrack.domain.plugin.eval
import it.infraconsulting.activitytrack.domain.plugin.text
import it.infraconsulting.activitytrack.domain.plugin.truthy
import it.infraconsulting.activitytrack.domain.report.DayAttributionPolicy
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/* What a sync did, for the report shown to the user. */
data class PluginSyncReport(
	val items: Int = 0,
	val clients: Int = 0,
	val contacts: Int = 0,
	val todos: Int = 0,
	/* to-dos created by this sync: id → title (notified) */
	val newTodos: List<Pair<Long, String>> = emptyList(),
	/* records or entries not taken, with the reason (an entry: its missing field) */
	val skipped: List<Pair<String, String>> = emptyList(),
	val sent: Int = 0,
	val updated: Int = 0,
	val deleted: Int = 0,
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
				doneUtcMs = base.doneUtcMs ?: now.takeIf { r.bool("done") == true },
				pluginFields = importedFields(r.fields, base.pluginFields, r.bool("done") == true),
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

	/*
	 * Download, then the send of the closed entries (issue #48 B4): one
	 * record per block of the descriptor's grouping, created, updated when
	 * its body changed, deleted when the block vanished. [entryId] sends
	 * that entry only (on closing it); [send] false = download only.
	 * Entries missing a required field are listed, not sent. Stops at the
	 * first remote error; what was sent stays recorded.
	 */
	suspend fun sync(companyId: Long, send: Boolean = true, entryId: Long? = null): PluginSyncReport {
		val down = download(companyId)
		if (!send)
			return down
		return lock.withLock { upload(companyId, entryId, down) }
	}

	private suspend fun upload(companyId: Long, only: Long?, start: PluginSyncReport): PluginSyncReport {
		val company = c.masterData.company(companyId) ?: error("company $companyId")
		val plugin = company.connectorId?.let { app.plugins.plugin(it) } ?: error("no plugin for ${company.name}")
		val spec = plugin.export ?: return start
		val s = c.settings.current()
		val rounding = { rule: String, min: Long ->
			val r = when (rule) { "travel" -> s.rounding.travel; "pause" -> s.rounding.pause; else -> s.rounding.work }
			r.apply(Duration.ofMinutes(min)).toMinutes()
		}
		val base = app.plugins.env(company, plugin).withHooks(rounding = rounding)
		val client = app.plugins.client(company, plugin, base)
		PluginSession.prepare(plugin, client, base)
		val items = dao.items(companyId, plugin.id)
		val policy = DayAttributionPolicy(LocalTime.of(s.carryOverMin / 60, s.carryOverMin % 60))
		var report = start
		/* records of entries deleted in the app: deleted on the remote side too */
		for (d in dao.deleted(companyId, plugin.id)) {
			val remote = JsonObject(mapOf("id" to JsonPrimitive(d.remoteId)))
			try {
				spec.delete?.let { del -> client.call(del.method, del.path, base.with { r -> if (r.root == "remote") PluginEnv.dig(remote, r.path) else null }) }
			} catch (x: PluginHttpException) {
				/* already gone there: nothing left to delete */
				if (x.status != 404)
					throw x
			}
			dao.forgetDeleted(d.pluginId, d.remoteId)
			report = report.copy(deleted = report.deleted + 1)
		}
		for (e in dao.entriesToSend(companyId, plugin.id).filter { only == null || it.id == only }) {
			val zone = runCatching { ZoneId.of(e.zoneId) }.getOrDefault(ZoneId.systemDefault())
			val todo = e.todoId?.let { dao.todo(it) }?.takeIf { it.companyId == companyId && it.externalKey != null }
			val clientRow = e.clientId?.let { dao.client(it) }
			val title = clientRow?.name ?: company.name
			val lines = dao.materialsOf(e.id)
			/* entry fields: the entry's own, else those its to-do pre-filled */
			val fields = HashMap<String, JsonElement>()
			obj(todo?.pluginFields)?.let { fields.putAll(it) }
			obj(e.pluginFields)?.let { fields.putAll(it) }
			val entryJson = JsonObject(
				buildMap {
					put("id", JsonPrimitive(e.id))
					e.uid?.let { put("uid", JsonPrimitive(it)) }
					put("activity", JsonPrimitive(e.activity.orEmpty()))
					put("notes", JsonPrimitive(e.notes.orEmpty()))
					put("locality", JsonPrimitive(e.locality.orEmpty()))
					put("company", JsonObject(mapOf("id" to JsonPrimitive(company.id), "name" to JsonPrimitive(company.name))))
					clientRow?.let { put("client", JsonObject(mapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name), "code" to (it.externalCode?.let(::JsonPrimitive) ?: JsonNull)))) }
				},
			)
			/* @todo.fields: what the import kept of the remote record (e.g. Antares' customer premises) */
			val todoJson: JsonElement = todo?.let {
				JsonObject(
					mapOf(
						"id" to JsonPrimitive(it.id), "externalKey" to JsonPrimitive(it.externalKey!!), "title" to JsonPrimitive(it.title),
						"refs" to JsonPrimitive(it.refs), "fields" to (obj(it.pluginFields) ?: JsonObject(emptyMap())),
					),
				)
			} ?: JsonNull
			var blockJson: JsonElement = JsonNull
			var remoteJson: JsonElement = JsonNull
			lateinit var eenv: PluginEnv
			fun selected(listName: String): JsonElement {
				val f = plugin.entryFields.firstOrNull { it.list == listName } ?: return JsonNull
				val id = fields[f.key]?.let(::text) ?: return JsonNull
				val parent = f.parent?.let { text(it.eval(null, eenv)) }.orEmpty()
				return items.firstOrNull { it.list == listName && it.id == id && (f.parent == null || it.parentId == parent) }?.let { itemJson(it) } ?: JsonNull
			}
			/* dates in the entry's own time zone, as recorded */
			eenv = base.withHooks(materials = { mode -> materials(lines, mode) }, zone = zone).with { r ->
				when (r.root) {
					"entry" -> PluginEnv.dig(entryJson, r.path)
					"todo" -> if (r.path.isEmpty()) todoJson else PluginEnv.dig(todoJson, r.path)
					"field" -> r.path.firstOrNull()?.let { fields[it] }?.let { PluginEnv.dig(it, r.path.drop(1)) } ?: JsonNull
					"list" -> if (r.path.getOrNull(1) == "selected") PluginEnv.dig(selected(r.path[0]), r.path.drop(2)) else JsonNull
					"block" -> PluginEnv.dig(blockJson, r.path)
					"remote" -> PluginEnv.dig(remoteJson, r.path)
					else -> null
				}
			}
			/* defaults of the fields not set, then the required ones */
			for (f in plugin.entryFields)
				if (f.key !in fields)
					f.default?.eval(null, eenv)?.takeIf { it !is JsonNull }?.let { fields[f.key] = it }
			val missing = plugin.entryFields.firstOrNull { f ->
				truthy(f.required.eval(null, eenv)) && fields[f.key].let { it == null || it is JsonNull || text(it).isNullOrEmpty() }
			}
			if (missing != null) {
				val label = missing.label[java.util.Locale.getDefault().language] ?: missing.label["en"] ?: missing.key
				report = report.copy(skipped = report.skipped + (title to label))
				continue
			}
			val segs = dao.segmentsOf(e.id).filter { it.endUtcMs != null }.map {
				ExportSegment(it.kind, Instant.ofEpochMilli(it.startUtcMs), Instant.ofEpochMilli(it.endUtcMs!!), it.kmDeclared ?: it.kmEstimated)
			}
			val split = spec.group.splitPauseMin?.let { text(it.eval(null, eenv))?.toLongOrNull() }
			val blocks = PluginExport.blocks(spec.group, segs, zone, s.rounding, policy, split)
			val bodies = blocks.map { b ->
				blockJson = b.asJson()
				val body = if (b.kind == SegmentKind.TRAVEL && spec.travelBody != null) spec.travelBody!! else spec.body
				b to PluginExport.body(body, eenv)
			}
			val sent = dao.sent(e.id, plugin.id).map { SentRecord(it.blockKey, it.remoteId, it.hash) }
			for (a in PluginExport.plan(bodies, sent)) {
				when (a) {
					is ExportAction.Create -> {
						blockJson = a.block.asJson()
						val reply = client.call(spec.create.method, spec.create.path, eenv, a.body)
						val id = spec.create.id?.eval(reply, eenv)?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() ?: it }?.let(::text)
							?: throw PluginDataException("no id in the reply to ${spec.create.method}")
						dao.recordSent(RemoteRecordEntity(e.id, plugin.id, a.block.key, id, a.hash, System.currentTimeMillis()))
						report = report.copy(sent = report.sent + 1)
					}
					is ExportAction.Update -> {
						val u = spec.update
						blockJson = a.block.asJson()
						remoteJson = JsonObject(mapOf("id" to JsonPrimitive(a.sent.remoteId)))
						if (u == null) {
							/* no update in the descriptor: a new record replaces the old one */
							spec.delete?.let { d -> client.call(d.method, d.path, eenv) }
							val reply = client.call(spec.create.method, spec.create.path, eenv, a.body)
							val id = spec.create.id?.eval(reply, eenv)?.let(::text) ?: throw PluginDataException("no id in the reply")
							dao.recordSent(RemoteRecordEntity(e.id, plugin.id, a.block.key, id, a.hash, System.currentTimeMillis()))
						} else {
							if (u.fetchFirst) {
								val current = client.call("GET", u.path, eenv) as? JsonObject ?: JsonObject(emptyMap())
								remoteJson = JsonObject(current + ("id" to JsonPrimitive(a.sent.remoteId)))
							}
							val body = JsonObject(a.body + PluginExport.body(u.add, eenv))
							client.call(u.method, u.path, eenv, body)
							dao.recordSent(RemoteRecordEntity(e.id, plugin.id, a.block.key, a.sent.remoteId, a.hash, System.currentTimeMillis()))
						}
						report = report.copy(updated = report.updated + 1)
					}
					is ExportAction.Delete -> {
						remoteJson = JsonObject(mapOf("id" to JsonPrimitive(a.sent.remoteId)))
						spec.delete?.let { d -> client.call(d.method, d.path, eenv) }
						dao.forgetSent(e.id, plugin.id, a.sent.key)
						report = report.copy(deleted = report.deleted + 1)
					}
				}
			}
		}
		/* to-dos marked done in the app: closed on the remote side once, after their last records went */
		spec.todoDone?.let { done ->
			for (t in dao.doneImportedTodos(companyId)) {
				val fields = obj(t.pluginFields) ?: JsonObject(emptyMap())
				if (fields[CLOSED] == JsonPrimitive(true))
					continue
				val todoJson = JsonObject(
					mapOf(
						"id" to JsonPrimitive(t.id), "externalKey" to JsonPrimitive(t.externalKey!!), "title" to JsonPrimitive(t.title),
						"refs" to JsonPrimitive(t.refs), "fields" to fields,
						"activities" to JsonPrimitive(dao.activitiesOfTodo(t.id).joinToString("\n\n")),
					),
				)
				val env = base.with { r -> if (r.root == "todo") (if (r.path.isEmpty()) todoJson else PluginEnv.dig(todoJson, r.path)) else null }
				client.call(done.method, done.path, env, PluginExport.body(done.body, env))
				dao.updateTodo(t.copy(pluginFields = JsonObject(fields + (CLOSED to JsonPrimitive(true))).toString()))
			}
		}
		return report
	}

	private fun obj(json: String?): JsonObject? = json?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

	/*
	 * A to-do's plugin fields after an import: the remote's, keeping the mark
	 * that it is closed on the remote side (set here when the remote says so,
	 * or after export.todoDone), so a done to-do is closed there only once.
	 */
	private fun importedFields(remote: Map<String, JsonElement>, old: String?, remoteDone: Boolean): String? {
		val closed = remoteDone || obj(old)?.get(CLOSED) == JsonPrimitive(true)
		val fields = remote + if (closed) mapOf(CLOSED to JsonPrimitive(true)) else emptyMap()
		return fields.takeIf { it.isNotEmpty() }?.let { JsonObject(it).toString() } ?: old
	}

	private fun itemJson(i: RemoteItemEntity): JsonObject = RemoteItem(
		i.list, i.id, i.parentId, i.name, i.clientCode, i.kind, i.active, i.order, obj(i.extra) ?: JsonObject(emptyMap()),
	).asJson()

	/* an entry's materials: lines, or one text line each ("2 m Cable") */
	private fun materials(lines: List<EntryMaterialEntity>, mode: String): JsonElement = when (mode) {
		"lines" -> JsonArray(lines.map { l ->
			JsonObject(buildMap {
				put("description", JsonPrimitive(l.description))
				l.quantity?.let { put("quantity", JsonPrimitive(it)) }
				l.unit?.let { put("unit", JsonPrimitive(it)) }
				l.unitCostCents?.let { put("unitCost", JsonPrimitive(it / 100.0)) }
			})
		})
		else -> JsonPrimitive(lines.joinToString("\n") { l ->
			listOfNotNull(l.quantity?.let { q -> if (q % 1.0 == 0.0) q.toLong().toString() else q.toString() }, l.unit, l.description).joinToString(" ")
		})
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
		/* in a to-do's plugin fields: closed on the remote side (export.todoDone) */
		const val CLOSED = "_closed"
	}
}
