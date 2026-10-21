/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import kotlinx.serialization.json.JsonArray

/*
 * The first step of every sync: who the user is on the remote side
 * (identity.values) and the small lookup lists, into [env].
 */
object PluginSession {
	suspend fun prepare(plugin: Plugin, client: PluginClient, env: PluginEnv) {
		plugin.identity?.let { id ->
			val reply = client.call(id.request, env)
			for ((k, x) in id.values) {
				val v = x.eval(reply, env)
				/* a filter selector gives a list: its first value */
				env.identity[k] = (v as? JsonArray)?.firstOrNull() ?: v
			}
		}
		for ((name, l) in plugin.lookups) {
			val rows = if (l.request.paged) JsonArray(client.list(l.request, env)) else client.call(l.request, env)
			env.lookups[name] = l.select.eval(rows, env)
		}
	}
}
