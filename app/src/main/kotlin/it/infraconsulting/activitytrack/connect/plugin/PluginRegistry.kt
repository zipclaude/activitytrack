/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.plugin

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import it.infraconsulting.activitytrack.domain.plugin.Auth
import it.infraconsulting.activitytrack.domain.plugin.Plugin
import it.infraconsulting.activitytrack.domain.plugin.PluginClient
import it.infraconsulting.activitytrack.domain.plugin.PluginEnv
import it.infraconsulting.activitytrack.domain.plugin.PluginParser
import it.infraconsulting.activitytrack.domain.plugin.PluginResult
import it.infraconsulting.activitytrack.domain.plugin.StaticAuthorizer
import it.infraconsulting.activitytrack.domain.policy.PolicyError
import it.infraconsulting.activitytrack.domain.policy.PolicyGate
import it.infraconsulting.activitytrack.domain.policy.PolicySignature
import it.infraconsulting.activitytrack.policy.PolicyLoader
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The integration plugins of the data folder (issue #48 B2): the .json files in plugins/,
 * read with the policy check (start, foreground). A policy listing
 * integrations allows only those, enabled; with a signed policy in force
 * every plugin must carry <file>.sig by the same CN (owner, answer 2).
 * A refused file is notified once, until it changes.
 */
class PluginRegistry(private val app: ActivityTrackApp) {
	private val _plugins = MutableStateFlow<Map<String, Plugin>>(emptyMap())
	val plugins: StateFlow<Map<String, Plugin>> = _plugins.asStateFlow()

	/* files refused, by fingerprint: notified once */
	private val seen = app.getSharedPreferences("plugins_refused", Context.MODE_PRIVATE)

	fun plugin(id: String): Plugin? = _plugins.value[id]

	suspend fun reload() {
		val c = app.container
		val tree = c.settings.current().rootUri?.toUri()
		if (tree == null || !c.userDataDir.isAccessible(tree)) {
			_plugins.value = emptyMap()
			return
		}
		val policy = c.policies.current.value
		val allowed = policy.integrations.takeIf { it.isNotEmpty() }?.filterValues { it.enabled }?.values?.map { it.plugin }?.toSet()
		val pinned = c.policies.gateState().pinnedCn
		val files = c.userDataDir.list(tree, UserDataDir.Sub.PLUGINS).associateBy { it.name }
		val out = LinkedHashMap<String, Plugin>()
		for ((name, file) in files.toSortedMap()) {
			if (!name.endsWith(".json") || (allowed != null && name !in allowed))
				continue
			val bytes = read(file.uri) ?: continue
			val sig = files["$name.sig"]?.let { read(it.uri) }
			val fingerprint = PolicyGate.fingerprint(bytes, sig)
			val errors = check(name, bytes, sig, pinned)
			if (errors.isNotEmpty()) {
				if (seen.getString(name, null) != fingerprint) {
					seen.edit { putString(name, fingerprint) }
					PolicyLoader.notify(app, app.getString(R.string.plugin_refused_title, name), errors.take(8).joinToString("\n"), name.hashCode())
				}
				Log.w(TAG, "$name refused: ${errors.joinToString("; ")}")
				continue
			}
			seen.edit { remove(name) }
			val p = (PluginParser.parse(bytes.toString(Charsets.UTF_8), name.removeSuffix(".json")) as PluginResult.Valid).plugin
			out[p.id] = p
		}
		_plugins.value = out
		Log.i(TAG, "plugins: ${out.keys.joinToString().ifEmpty { "none" }}")
	}

	private fun check(name: String, bytes: ByteArray, sig: ByteArray?, pinned: String?): List<PolicyError> {
		if (bytes.size > MAX_BYTES)
			return listOf(PolicyError("", null, app.getString(R.string.policy_too_large)))
		if (pinned != null) {
			if (sig == null)
				return listOf(PolicyError("", null, "$name must be signed by \"$pinned\", like the policy in force ($name.sig)"))
			val cn = try {
				PolicySignature.verify(bytes, sig, PolicyLoader.trustAnchors())
			} catch (e: PolicySignature.Invalid) {
				return listOf(PolicyError("", null, e.message.orEmpty()))
			}
			if (cn != pinned)
				return listOf(PolicyError("", null, "signed by \"$cn\", but must be signed by \"$pinned\""))
		}
		return when (val r = PluginParser.parse(bytes.toString(Charsets.UTF_8), name.removeSuffix(".json"))) {
			is PluginResult.Invalid -> r.errors
			is PluginResult.Valid -> emptyList()
		}
	}

	private suspend fun read(uri: android.net.Uri): ByteArray? = runCatching {
		withContext(Dispatchers.IO) { app.container.userDataDir.openInput(uri).use { it.readNBytes(MAX_BYTES + 1) } }
	}.getOrNull()

	/*
	 * The expressions' values for [company]: its options (company
	 * connector_config JSON), its secrets (keystore), the phone's zone.
	 */
	fun env(company: CompanyEntity, plugin: Plugin): PluginEnv {
		val values = runCatching { Json.parseToJsonElement(company.connectorConfig ?: "{}") as? JsonObject }.getOrNull()
			?.let { (it["options"] as? JsonObject) ?: it } ?: emptyMap<String, JsonElement>()
		return PluginEnv(plugin, values, { key -> app.secrets.get(company.id, secretField(plugin.id, key)) }, ZoneId.systemDefault())
	}

	/* The HTTP client of [plugin] for [company], with its credentials. */
	fun client(company: CompanyEntity, plugin: Plugin, env: PluginEnv = env(company, plugin)): PluginClient {
		val auth = when (plugin.auth) {
			is Auth.OAuth2Pkce -> app.oauth.authorizer(OAuthPkce.Client.of(company.id, env))
			else -> StaticAuthorizer(plugin.auth, env)
		}
		return PluginClient(plugin, HttpTransport(plugin.http.timeoutSec, app.container.userAgent), auth, { delay(it) })
	}

	companion object {
		private const val TAG = "PluginRegistry"
		private const val MAX_BYTES = 1 shl 20

		/* a secret option of a plugin in the SecretStore */
		fun secretField(pluginId: String, key: String) = "plugin.$pluginId.$key"
	}
}
