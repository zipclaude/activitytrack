/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/*
 * The entry fields of a plugin (docs/PLUGINS.md §8) for one activity: the
 * values they take and how their expressions evaluate. The entry form, the
 * send of the records and the activity's displayed state (complete or not,
 * ACTIVITY-MODEL.md 2.4) all read them from here, so they agree.
 *
 * [own]: the activity's stored plugin fields; [items]: the plugin's lists
 * of the company, for @list.<name>.selected; [base]: the company's env
 * (options, identity); [roots]: the other roots the expressions may read
 * (@entry, @activity, and those of a phase, @block, @remote), winning over
 * @field and @list. A field without a stored value takes its default.
 */
class EntryFieldScope(
	val plugin: Plugin,
	own: Map<String, JsonElement>,
	private val items: List<RemoteItem>,
	base: PluginEnv,
	roots: (Expr.Ref) -> JsonElement? = { null },
) {
	/* the stored values, then the defaults of the fields not set */
	val fields: Map<String, JsonElement> get() = values

	private val values = HashMap(own)

	val env: PluginEnv = base.with { r ->
		roots(r) ?: when (r.root) {
			"field" -> r.path.firstOrNull()?.let { values[it] }?.let { PluginEnv.dig(it, r.path.drop(1)) } ?: JsonNull
			"list" -> if (r.path.getOrNull(1) == "selected") PluginEnv.dig(selected(r.path[0]), r.path.drop(2)) else JsonNull
			else -> null
		}
	}

	init {
		for (f in plugin.entryFields)
			if (f.key !in values)
				f.default?.eval(null, env)?.takeIf { it !is JsonNull }?.let { values[f.key] = it }
	}

	/* the id of the parent list item a child list hangs on ("" for none, as the items store it) */
	fun parentOf(f: EntryField): String? = f.parent?.let { text(it.eval(null, env)) }

	/* the item chosen in [list], as @list.<list>.selected */
	fun selected(list: String): JsonElement {
		val f = plugin.entryFields.firstOrNull { it.list == list } ?: return JsonNull
		val id = values[f.key]?.let(::text) ?: return JsonNull
		val parent = parentOf(f).orEmpty()
		return items.firstOrNull { it.list == list && it.id == id && (f.parent == null || it.parentId == parent) }?.asJson() ?: JsonNull
	}

	fun required(f: EntryField): Boolean = truthy(f.required.eval(null, env))

	fun hidden(f: EntryField): Boolean = truthy(f.hidden.eval(null, env))

	/*
	 * The first field that is required and empty, or null: the activity
	 * cannot be complete nor sent without it. A hidden field counts as any
	 * other (the send has always checked it): a descriptor that hides a
	 * required field has to make the two exclusive, as the bundled ones do.
	 */
	fun missingRequired(): EntryField? = plugin.entryFields.firstOrNull { required(it) && isEmpty(values[it.key]) }

	companion object {
		/* no value, null, or blank text */
		fun isEmpty(v: JsonElement?): Boolean = v == null || v is JsonNull || text(v).isNullOrBlank()
	}
}
