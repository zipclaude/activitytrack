/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.plugin

import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.RemoteItemEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.plugin.EntryField
import it.infraconsulting.activitytrack.domain.plugin.EntryFieldScope
import it.infraconsulting.activitytrack.domain.plugin.Expr
import it.infraconsulting.activitytrack.domain.plugin.Plugin
import it.infraconsulting.activitytrack.domain.plugin.PluginEnv
import it.infraconsulting.activitytrack.domain.plugin.RemoteItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The roots @entry and @activity of the entry fields' expressions, built
 * the same way for the entry form, the send (PluginSync) and the check of
 * the required fields, so that they evaluate alike.
 */
internal object PluginRoots {
	fun obj(json: String?): JsonObject? = json?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

	fun entryJson(e: TimeEntryEntity, company: CompanyEntity, client: ClientEntity?): JsonObject = JsonObject(
		buildMap {
			put("id", JsonPrimitive(e.id))
			e.uid?.let { put("uid", JsonPrimitive(it)) }
			put("activity", JsonPrimitive(e.activity.orEmpty()))
			put("notes", JsonPrimitive(e.notes.orEmpty()))
			put("locality", JsonPrimitive(e.locality.orEmpty()))
			put("company", JsonObject(mapOf("id" to JsonPrimitive(company.id), "name" to JsonPrimitive(company.name))))
			client?.let { put("client", JsonObject(mapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name), "code" to (it.externalCode?.let(::JsonPrimitive) ?: JsonNull)))) }
		},
	)

	/* @activity: id, external key, title (the description), references, the plugin fields, [activities] what was done */
	suspend fun activityJson(c: AppContainer, e: TimeEntryEntity, activities: String?): JsonObject = JsonObject(
		buildMap {
			put("id", JsonPrimitive(e.id))
			put("externalKey", JsonPrimitive(e.externalKey.orEmpty()))
			put("title", JsonPrimitive(e.activity.orEmpty()))
			put("refs", JsonPrimitive(c.entries.externalRefValues(e.id).joinToString("\n")))
			put("fields", obj(e.pluginFields) ?: JsonObject(emptyMap()))
			activities?.let { put("activities", JsonPrimitive(it)) }
		},
	)

	/*
	 * @activity of [e]: the planned fields of the same record when the plugin
	 * imported it (activity model 1.0), else of the imported activity a UUID
	 * reference points at (an entry of a to-do before 1.0); null otherwise.
	 */
	suspend fun activityOf(c: AppContainer, e: TimeEntryEntity, companyId: Long): JsonElement =
		(e.takeIf { it.externalKey != null } ?: c.pluginData.linkedActivity(e.id, companyId))?.let { activityJson(c, it, null) } ?: JsonNull

	fun item(i: RemoteItemEntity): RemoteItem =
		RemoteItem(i.list, i.id, i.parentId, i.name, i.clientCode, i.kind, i.active, i.order, obj(i.extra) ?: JsonObject(emptyMap()))

	/* the roots of the entry fields outside a phase: @entry and @activity */
	fun roots(entry: JsonElement, activity: JsonElement): (Expr.Ref) -> JsonElement? = { r ->
		when (r.root) {
			"entry" -> PluginEnv.dig(entry, r.path)
			"activity" -> if (r.path.isEmpty()) activity else PluginEnv.dig(activity, r.path)
			else -> null
		}
	}
}

/*
 * Whether a closed activity misses a field its company's plugin requires
 * (ACTIVITY-MODEL.md 2.4, issue #178): the displayed state, the Home's
 * incomplete section and the question after closing (PluginWatcher) read
 * it from here, the send (PluginSync) checks the same EntryFieldScope.
 */
class RequiredFields(private val app: ActivityTrackApp) {
	private val c get() = app.container

	/* a check that follows the descriptors and the companies: one per change, for a whole list */
	val checks: Flow<Check> = combine(app.plugins.plugins, app.container.masterData.companies) { p, co -> Check(p, co) }

	/* a check of the descriptors and companies of now, for one activity or a few */
	suspend fun current(): Check = Check(app.plugins.plugins.value, c.masterData.companies.first())

	/*
	 * The descriptors and the companies read once; a company's env and lists
	 * loaded the first time one of its activities is checked. Not thread-safe:
	 * one coroutine at a time.
	 */
	inner class Check internal constructor(private val plugins: Map<String, Plugin>, private val companies: List<CompanyEntity>) {
		private val withFields: Map<Long, Pair<CompanyEntity, Plugin>> = companies.mapNotNull { co ->
			co.connectorId?.let(plugins::get)?.takeIf { it.entryFields.isNotEmpty() }?.let { co.id to (co to it) }
		}.toMap()
		private val envs = HashMap<Long, PluginEnv>()
		private val items = HashMap<Long, List<RemoteItem>>()
		private val clients = HashMap<Long, ClientEntity?>()

		/* the same descriptors and companies, nothing loaded yet: one per list, so a list sees the plugin lists of now */
		fun fresh(): Check = Check(plugins, companies)

		/* the company of [e] has a plugin with entry fields */
		fun applies(e: TimeEntryEntity): Boolean = e.companyId?.let { it in withFields } == true

		/* the entry fields of [e], or null when its company has no plugin with entry fields */
		suspend fun scope(e: TimeEntryEntity): EntryFieldScope? {
			val (co, plugin) = e.companyId?.let { withFields[it] } ?: return null
			val env = envs.getOrPut(co.id) { app.plugins.env(co, plugin) }
			val lists = items.getOrPut(co.id) { c.pluginData.items(co.id, plugin.id).map(PluginRoots::item) }
			val client = e.clientId?.let { id -> clients.getOrPut(id) { c.masterData.client(id) } }
			val roots = PluginRoots.roots(PluginRoots.entryJson(e, co, client), PluginRoots.activityOf(c, e, co.id))
			return EntryFieldScope(plugin, PluginRoots.obj(e.pluginFields).orEmpty(), lists, env, roots)
		}

		suspend fun missingField(e: TimeEntryEntity): EntryField? = scope(e)?.missingRequired()

		/*
		 * The value of ActivitySnapshot.missingRequired: only a closed activity
		 * with a client can be complete, so the others are not looked into.
		 */
		suspend fun missing(e: TimeEntryEntity): Boolean =
			e.kind == ActivityKind.ACTIVITY && e.status == ActivityStatus.CLOSED && e.clientId != null && applies(e) && missingField(e) != null
	}
}
