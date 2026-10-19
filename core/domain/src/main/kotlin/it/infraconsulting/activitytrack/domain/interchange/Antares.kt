/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/*
 * Antares (Saidea) management system, through its Public API v3 (owner,
 * 2026-10-19). Settings per company: API base URL, identity provider and
 * the OpenID client id. The app is a public OAuth client (RFC 8252): login
 * with Authorization Code + PKCE in the browser, no client secret; the
 * refresh token is the only secret and lives in the SecretStore.
 */
object AntaresConnector : Connector {
	override val id = "antares"
	override val name = "Antares (Saidea)"
	override val capabilities = setOf(Capability.REMOTE_PULL, Capability.REMOTE_PUSH)
	override val settingFields = listOf(
		SettingField(API_URL, "API URL"),
		SettingField(IDENTITY_URL, "Identity provider"),
		SettingField(CLIENT_ID, "Client ID"),
		SettingField(PAUSE_SPLIT_MIN, "Pause splitting the activity (min)"),
	)

	const val API_URL = "api_url"
	const val IDENTITY_URL = "identity_url"
	const val CLIENT_ID = "client_id"
	/* a pause longer than this splits the activity (owner, 2026-10-19; default 60) */
	const val PAUSE_SPLIT_MIN = "pause_split_min"
	/*
	 * Issue #34 (owner, 2026-10-19), chosen in the company screen:
	 * - CLOSE_SCOPE: which closed entries offer the send, "tickets" (started
	 *   from an Antares ticket, default) or "all";
	 * - CLOSE_MODE: "ask" (default), "always" (send without asking) or "off";
	 * - CLOSE_SYNC: "full" Sync (default) or only the closed "entry", both
	 *   with the download of the updates; queued until the network is back;
	 * - FETCH_MIN: background download every 15 min to once a day (default 1 h).
	 */
	const val CLOSE_SCOPE = "close_scope"
	const val CLOSE_MODE = "close_mode"
	const val CLOSE_SYNC = "close_sync"
	const val FETCH_MIN = "fetch_min"
	const val SCOPE_TICKETS = "tickets"
	const val SCOPE_ALL = "all"
	const val MODE_ASK = "ask"
	const val MODE_ALWAYS = "always"
	const val MODE_OFF = "off"
	const val SYNC_FULL = "full"
	const val SYNC_ENTRY = "entry"
	/* WorkManager runs periodic work at most every 15 minutes */
	val FETCH_CHOICES = listOf(15, 30, 60, 120, 240, 480, 720, 1440)
	const val FETCH_DEFAULT = 60

	fun fetchMinutes(raw: String?): Int = raw?.toIntOrNull()?.coerceIn(FETCH_CHOICES.first(), FETCH_CHOICES.last()) ?: FETCH_DEFAULT

	/* Whether closing an entry offers the send: off, or not from a ticket while only tickets count. */
	fun offersSendOnClose(mode: String?, scope: String?, fromTicket: Boolean): Boolean =
		(mode ?: MODE_ASK) != MODE_OFF && (fromTicket || scope == SCOPE_ALL)

	/* filled by the app after login (/me/claims) */
	const val PERSON_ID = "person_id"
	const val USERNAME = "username"
	/* secret */
	const val REFRESH_TOKEN = "refresh_token"

	/* the test installation (owner: demo.goantares.uno is the test environment) */
	const val DEMO_API = "https://demo.goantares.uno/public/api"
	const val DEMO_IDENTITY = "https://identitydemo.goantares.uno"
	const val SCOPES = "openid profile public-api offline_access"
}

/* PKCE (RFC 7636): a random verifier and its S256 challenge. */
object Pkce {
	private val random = SecureRandom()
	private val b64 = Base64.getUrlEncoder().withoutPadding()

	/* 32 random bytes -> 43 base64url characters, within the 43..128 allowed */
	fun verifier(): String = ByteArray(32).also(random::nextBytes).let(b64::encodeToString)

	fun challenge(verifier: String): String =
		b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

	/* opaque value checked when the browser comes back */
	fun state(): String = ByteArray(16).also(random::nextBytes).let(b64::encodeToString)
}

/* The authorization request the browser opens (OpenID Connect, code flow with PKCE). */
fun authorizeUrl(authorizationEndpoint: String, clientId: String, redirectUri: String, state: String, challenge: String, scopes: String): String {
	fun e(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
	return authorizationEndpoint + "?client_id=" + e(clientId) + "&response_type=code" +
		"&redirect_uri=" + e(redirectUri) + "&scope=" + e(scopes) + "&state=" + e(state) +
		"&code_challenge=" + e(challenge) + "&code_challenge_method=S256"
}
