/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import it.infraconsulting.activitytrack.connect.SecretStore
import it.infraconsulting.activitytrack.domain.plugin.Auth
import it.infraconsulting.activitytrack.domain.plugin.Authorizer
import it.infraconsulting.activitytrack.domain.plugin.HttpCall
import it.infraconsulting.activitytrack.domain.plugin.Pkce
import it.infraconsulting.activitytrack.domain.plugin.PluginEnv
import it.infraconsulting.activitytrack.domain.plugin.eval
import it.infraconsulting.activitytrack.domain.plugin.text
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class OAuthException(message: String) : IOException(message)

/*
 * OAuth 2 / OpenID Connect login of a plugin (auth "oauth2Pkce") as a
 * public native client (RFC 8252), for one company: Authorization Code +
 * PKCE in the browser, back on "<applicationId>:/oauth2redirect"
 * (OAuthRedirectActivity). The refresh token is kept encrypted and
 * replaced at every refresh; the access token only in memory until a
 * minute before it expires. From PR #29's AntaresAuth, made generic.
 */
class OAuthPkce(private val context: Context, private val secrets: SecretStore) {
	private val pending = context.getSharedPreferences("oauth_pending", Context.MODE_PRIVATE)
	private val tokens = ConcurrentHashMap<String, Pair<String, Long>>()
	/*
	 * One refresh at a time: with rotation (one-time refresh tokens) a second
	 * concurrent refresh would spend the same token and force a new login.
	 */
	private val refreshing = Mutex()

	val redirectUri: String get() = context.packageName + ":/oauth2redirect"

	/* the stored settings of one login */
	data class Client(val companyId: Long, val pluginId: String, val issuer: String?, val authorizeUrl: String?, val tokenUrl: String?, val clientId: String, val scopes: String) {
		val key get() = "$companyId/$pluginId"

		companion object {
			fun of(companyId: Long, env: PluginEnv): Client {
				val a = env.plugin.auth as? Auth.OAuth2Pkce ?: throw OAuthException("${env.plugin.id} does not use OAuth")
				fun v(e: it.infraconsulting.activitytrack.domain.plugin.Expr?) = e?.let { text(it.eval(null, env)) }?.trim()?.trimEnd('/')?.ifEmpty { null }
				return Client(companyId, env.plugin.id, v(a.issuer), v(a.authorizeUrl), v(a.tokenUrl), v(a.clientId) ?: throw OAuthException("client id missing"), a.scopes)
			}
		}
	}

	private data class Endpoints(val authorize: String, val token: String)

	private suspend fun endpoints(c: Client): Endpoints {
		if (c.authorizeUrl != null && c.tokenUrl != null)
			return Endpoints(c.authorizeUrl, c.tokenUrl)
		val issuer = c.issuer ?: throw OAuthException("no identity provider")
		val o = JSONObject(get("$issuer/.well-known/openid-configuration"))
		return Endpoints(o.getString("authorization_endpoint"), o.getString("token_endpoint"))
	}

	/* Opens the login page; the verifier and state wait for the redirect. */
	suspend fun startLogin(c: Client) {
		val e = endpoints(c)
		val verifier = Pkce.verifier()
		val state = Pkce.state()
		pending.edit {
			putString("$state/client", JSONObject()
				.put("company", c.companyId).put("plugin", c.pluginId).put("issuer", c.issuer).put("authorize", c.authorizeUrl)
				.put("token", c.tokenUrl).put("clientId", c.clientId).put("scopes", c.scopes).toString())
			putString("$state/verifier", verifier)
		}
		val url = Pkce.authorizeUrl(e.authorize, c.clientId, redirectUri, state, Pkce.challenge(verifier), c.scopes)
		context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	}

	/* The browser came back with ?code=…&state=…: the code becomes tokens. Returns the login's client. */
	suspend fun finishLogin(redirect: Uri): Client {
		redirect.getQueryParameter("error")?.let { throw OAuthException(redirect.getQueryParameter("error_description") ?: it) }
		val state = redirect.getQueryParameter("state") ?: throw OAuthException("no state")
		val code = redirect.getQueryParameter("code") ?: throw OAuthException("no code")
		val stored = pending.getString("$state/client", null) ?: throw OAuthException("unknown login")
		val verifier = pending.getString("$state/verifier", null) ?: throw OAuthException("unknown login")
		pending.edit { remove("$state/client"); remove("$state/verifier") }
		val o = JSONObject(stored)
		fun s(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
		val c = Client(o.getLong("company"), o.getString("plugin"), s("issuer"), s("authorize"), s("token"), o.getString("clientId"), o.optString("scopes"))
		store(c, tokenRequest(endpoints(c).token, mapOf(
			"grant_type" to "authorization_code", "client_id" to c.clientId, "code" to code,
			"redirect_uri" to redirectUri, "code_verifier" to verifier,
		)))
		return c
	}

	fun loggedIn(companyId: Long, pluginId: String): Boolean = secrets.has(companyId, refreshField(pluginId))

	fun logout(companyId: Long, pluginId: String) {
		tokens.remove("$companyId/$pluginId")
		secrets.put(companyId, refreshField(pluginId), null)
	}

	/* A valid access token, refreshed a minute before it expires. */
	suspend fun accessToken(c: Client): String {
		tokens[c.key]?.let { (t, until) -> if (System.currentTimeMillis() < until) return t }
		return refreshing.withLock {
			/* another caller may have refreshed it while this one waited */
			tokens[c.key]?.let { (t, until) -> if (System.currentTimeMillis() < until) return@withLock t }
			val refresh = secrets.get(c.companyId, refreshField(c.pluginId)) ?: throw OAuthException("not logged in")
			store(c, tokenRequest(endpoints(c).token, mapOf(
				"grant_type" to "refresh_token", "client_id" to c.clientId, "refresh_token" to refresh,
			)))
		}
	}

	/* The Authorizer of a plugin's client: bearer token, dropped on a 401 to refresh once. */
	fun authorizer(c: Client): Authorizer = object : Authorizer {
		override suspend fun authorize(call: HttpCall) = call.copy(headers = call.headers + ("Authorization" to "Bearer ${accessToken(c)}"))

		override suspend fun refused(): Boolean {
			tokens.remove(c.key)
			return loggedIn(c.companyId, c.pluginId)
		}
	}

	private fun store(c: Client, o: JSONObject): String {
		val access = o.getString("access_token")
		val life = o.optLong("expires_in", 300)
		tokens[c.key] = access to System.currentTimeMillis() + (life - 60).coerceAtLeast(30) * 1000
		/* the provider may rotate it: the old one stops working */
		o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { secrets.put(c.companyId, refreshField(c.pluginId), it) }
		return access
	}

	private suspend fun tokenRequest(endpoint: String, form: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
		val body = form.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
		val c = URL(endpoint).openConnection() as HttpURLConnection
		try {
			c.requestMethod = "POST"
			c.doOutput = true
			c.connectTimeout = TIMEOUT
			c.readTimeout = TIMEOUT
			c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
			c.setRequestProperty("Accept", "application/json")
			c.outputStream.use { it.write(body.toByteArray()) }
			val code = c.responseCode
			val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
			if (code >= 400) {
				/* invalid_grant: the refresh token is gone, a new login is needed */
				val err = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
				throw OAuthException("token $code ${err.ifEmpty { text.take(200) }}")
			}
			JSONObject(text)
		} finally {
			c.disconnect()
		}
	}

	private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
		val c = URL(url).openConnection() as HttpURLConnection
		try {
			c.connectTimeout = TIMEOUT
			c.readTimeout = TIMEOUT
			if (c.responseCode >= 400)
				throw OAuthException("${c.responseCode} $url")
			c.inputStream.bufferedReader().use { it.readText() }
		} finally {
			c.disconnect()
		}
	}

	companion object {
		private const val TIMEOUT = 20_000

		/* secret field of a plugin's refresh token in the SecretStore */
		fun refreshField(pluginId: String) = "plugin.$pluginId.refresh_token"
	}
}
