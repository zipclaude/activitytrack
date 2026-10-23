/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* An item of a remote list (docs/PLUGINS.md §6); [parentId] "" at the top level. */
data class RemoteItem(
	val list: String,
	val id: String,
	val parentId: String,
	val name: String,
	val clientCode: String? = null,
	val kind: String? = null,
	val active: Boolean = true,
	val order: Int? = null,
	val extra: JsonObject = JsonObject(emptyMap()),
) {
	/* the item as @parent / @list.x.selected sees it */
	fun asJson(): JsonObject = JsonObject(
		buildMap {
			put("id", JsonPrimitive(id))
			put("name", JsonPrimitive(name))
			put("parentId", JsonPrimitive(parentId))
			clientCode?.let { put("clientCode", JsonPrimitive(it)) }
			kind?.let { put("kind", JsonPrimitive(it)) }
			put("active", JsonPrimitive(active))
			order?.let { put("order", JsonPrimitive(it)) }
			put("extra", extra)
		},
	)
}

/*
 * A record to import: [key] its external code, [values] the target's fields
 * (title, clientCode, done…), [fields] the entry fields it pre-fills,
 * [skipIfNew] true when the descriptor says not to create it (a closed ticket).
 */
data class ImportedRecord(
	val key: String,
	val values: Map<String, JsonElement>,
	val fields: Map<String, JsonElement> = emptyMap(),
	val skipIfNew: Boolean = false,
) {
	fun text(k: String): String? = values[k]?.let(::text)?.trim()?.ifEmpty { null }

	fun bool(k: String): Boolean? = values[k]?.takeIf { it !is JsonNull }?.let(::truthy)
}

/*
 * Import side of the engine (issue #48 B3): remote lists, parents before
 * children, and the records of clients, contacts and to-dos as the
 * descriptor maps them. Pure reading: the app stores the results.
 */
object PluginImport {
	/* Every list; a child list is read for each active item of its parent. */
	suspend fun lists(plugin: Plugin, client: PluginClient, env: PluginEnv): List<RemoteItem> {
		val out = ArrayList<RemoteItem>()
		for (name in order(plugin.lists)) {
			val list = plugin.lists.getValue(name)
			val parents: List<RemoteItem?> = if (list.parent == null) listOf(null) else out.filter { it.list == list.parent && it.active }
			for (parent in parents) {
				val penv = if (parent == null) env else env.with { r -> if (r.root == "parent") PluginEnv.dig(parent.asJson(), r.path) else null }
				for (source in list.sources) {
					if (source.whenExpr != null && !truthy(source.whenExpr.eval(null, penv)))
						continue
					for (row in client.list(source.request, penv))
						item(name, parent?.id.orEmpty(), source.map, row, penv)?.let { out += it }
				}
			}
		}
		return out
	}

	/* The records of [target]; rows without a key are skipped. */
	suspend fun records(plugin: Plugin, target: ImportTarget, client: PluginClient, env: PluginEnv): List<ImportedRecord> {
		val spec = plugin.imports[target] ?: return emptyList()
		return client.list(spec.request, env).mapNotNull { row ->
			val key = text(spec.key.eval(row, env).first())?.trim()?.ifEmpty { null } ?: return@mapNotNull null
			ImportedRecord(
				key,
				spec.map.mapValues { (_, x) -> x.eval(row, env).first() },
				spec.fields.mapValues { (_, x) -> x.eval(row, env).first() },
				spec.skipNew?.let { truthy(it.eval(row, env)) } ?: false,
			)
		}
	}

	private fun item(list: String, parentId: String, map: Map<String, Expr>, row: JsonElement, env: PluginEnv): RemoteItem? {
		fun v(k: String) = map[k]?.eval(row, env)?.first()
		val id = v("id")?.let(::text)?.ifEmpty { null } ?: return null
		val extra = map.filterKeys { it.startsWith("extra.") }.map { (k, x) -> k.removePrefix("extra.") to x.eval(row, env).first() }.toMap()
		return RemoteItem(
			list, id, parentId,
			name = v("name")?.let(::text) ?: id,
			clientCode = v("clientCode")?.let(::text)?.ifEmpty { null },
			kind = v("kind")?.let(::text)?.ifEmpty { null },
			active = v("active")?.takeIf { it !is JsonNull }?.let(::truthy) ?: true,
			order = v("order")?.let(::text)?.toDoubleOrNull()?.toInt(),
			extra = JsonObject(extra),
		)
	}

	/* parents before children (the parser refused cycles) */
	private fun order(lists: Map<String, RemoteList>): List<String> {
		val done = LinkedHashSet<String>()
		fun visit(n: String) {
			if (n in done)
				return
			lists[n]?.parent?.let(::visit)
			done += n
		}
		lists.keys.forEach(::visit)
		return done.toList()
	}

	/* a filter selector gives a list: its first value */
	private fun JsonElement.first(): JsonElement = (this as? kotlinx.serialization.json.JsonArray)?.firstOrNull() ?: this
}
