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
	)

	const val API_URL = "api_url"
	const val IDENTITY_URL = "identity_url"
	const val CLIENT_ID = "client_id"
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
