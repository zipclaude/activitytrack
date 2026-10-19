/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.antares

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import it.infraconsulting.activitytrack.connect.SecretStore
import it.infraconsulting.activitytrack.domain.interchange.AntaresConnector
import it.infraconsulting.activitytrack.domain.interchange.Pkce
import it.infraconsulting.activitytrack.domain.interchange.authorizeUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/* Plain connector settings of a company (company.connector_config). */
data class AntaresConfig(val apiUrl: String, val identityUrl: String, val clientId: String) {
	companion object {
		fun of(json: String?): AntaresConfig {
			val o = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
			return AntaresConfig(
				o.optString(AntaresConnector.API_URL).ifBlank { AntaresConnector.DEMO_API }.trimEnd('/'),
				o.optString(AntaresConnector.IDENTITY_URL).ifBlank { AntaresConnector.DEMO_IDENTITY }.trimEnd('/'),
				o.optString(AntaresConnector.CLIENT_ID).trim(),
			)
		}
	}
}

class AntaresAuthException(message: String) : IOException(message)

/*
 * OpenID Connect login for Antares as a public native client (RFC 8252):
 * Authorization Code + PKCE in the browser, the redirect comes back to
 * OAuthRedirectActivity on "<applicationId>:/oauth2redirect". The refresh
 * token is kept encrypted (SecretStore) and replaced at every refresh, the
 * access token only in memory until shortly before it expires.
 */
class AntaresAuth(private val context: Context, private val secrets: SecretStore) {
	private val pending = context.getSharedPreferences("oauth_pending", Context.MODE_PRIVATE)
	private val tokens = HashMap<Long, Pair<String, Long>>()

	val redirectUri: String get() = context.packageName + ":/oauth2redirect"

	private data class Endpoints(val authorize: String, val token: String)

	private suspend fun discover(identityUrl: String): Endpoints = withContext(Dispatchers.IO) {
		val o = JSONObject(http("$identityUrl/.well-known/openid-configuration"))
		Endpoints(o.getString("authorization_endpoint"), o.getString("token_endpoint"))
	}

	/* Opens the login page; the verifier and state wait for the redirect. */
	suspend fun startLogin(companyId: Long, config: AntaresConfig) {
		if (config.clientId.isEmpty())
			throw AntaresAuthException("client id missing")
		val e = discover(config.identityUrl)
		val verifier = Pkce.verifier()
		val state = Pkce.state()
		pending.edit {
			putLong("$state/company", companyId)
			putString("$state/verifier", verifier)
		}
		val url = authorizeUrl(e.authorize, config.clientId, redirectUri, state, Pkce.challenge(verifier), AntaresConnector.SCOPES)
		context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	}

	/* The browser came back with ?code=…&state=…: the code becomes tokens. Returns the company. */
	suspend fun finishLogin(redirect: Uri, configOf: suspend (Long) -> AntaresConfig): Long {
		redirect.getQueryParameter("error")?.let { throw AntaresAuthException(redirect.getQueryParameter("error_description") ?: it) }
		val state = redirect.getQueryParameter("state") ?: throw AntaresAuthException("no state")
		val code = redirect.getQueryParameter("code") ?: throw AntaresAuthException("no code")
		val companyId = pending.getLong("$state/company", -1).takeIf { it >= 0 } ?: throw AntaresAuthException("unknown login")
		val verifier = pending.getString("$state/verifier", null) ?: throw AntaresAuthException("unknown login")
		pending.edit { remove("$state/company"); remove("$state/verifier") }
		val config = configOf(companyId)
		val e = discover(config.identityUrl)
		store(companyId, tokenRequest(e.token, mapOf(
			"grant_type" to "authorization_code", "client_id" to config.clientId, "code" to code,
			"redirect_uri" to redirectUri, "code_verifier" to verifier,
		)))
		return companyId
	}

	fun loggedIn(companyId: Long): Boolean = secrets.has(companyId, AntaresConnector.REFRESH_TOKEN)

	fun logout(companyId: Long) {
		tokens.remove(companyId)
		secrets.put(companyId, AntaresConnector.REFRESH_TOKEN, null)
	}

	/* A valid access token, refreshed a minute before it expires. */
	suspend fun accessToken(companyId: Long, config: AntaresConfig): String {
		tokens[companyId]?.let { (t, until) -> if (System.currentTimeMillis() < until) return t }
		val refresh = secrets.get(companyId, AntaresConnector.REFRESH_TOKEN) ?: throw AntaresAuthException("not logged in")
		val e = discover(config.identityUrl)
		return store(companyId, tokenRequest(e.token, mapOf(
			"grant_type" to "refresh_token", "client_id" to config.clientId, "refresh_token" to refresh,
		)))
	}

	private fun store(companyId: Long, o: JSONObject): String {
		val access = o.getString("access_token")
		val life = o.optLong("expires_in", 300)
		tokens[companyId] = access to System.currentTimeMillis() + (life - 60).coerceAtLeast(30) * 1000
		/* the provider may rotate it: the old one stops working */
		o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { secrets.put(companyId, AntaresConnector.REFRESH_TOKEN, it) }
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
				throw AntaresAuthException("token $code ${err.ifEmpty { text.take(200) }}")
			}
			JSONObject(text)
		} finally {
			c.disconnect()
		}
	}

	private fun http(url: String): String {
		val c = URL(url).openConnection() as HttpURLConnection
		try {
			c.connectTimeout = TIMEOUT
			c.readTimeout = TIMEOUT
			if (c.responseCode >= 400)
				throw AntaresAuthException("${c.responseCode} $url")
			return c.inputStream.bufferedReader().use { it.readText() }
		} finally {
			c.disconnect()
		}
	}

	private companion object {
		const val TIMEOUT = 20_000
	}
}
