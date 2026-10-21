/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/* PKCE (RFC 7636): a random verifier and its S256 challenge (from PR #29, now for every OAuth plugin). */
object Pkce {
	private val random = SecureRandom()
	private val b64 = Base64.getUrlEncoder().withoutPadding()

	/* 32 random bytes -> 43 base64url characters, within the 43..128 allowed */
	fun verifier(): String = ByteArray(32).also(random::nextBytes).let(b64::encodeToString)

	fun challenge(verifier: String): String =
		b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

	/* opaque value checked when the browser comes back */
	fun state(): String = ByteArray(16).also(random::nextBytes).let(b64::encodeToString)

	/* The authorization request the browser opens (OpenID Connect, code flow with PKCE). */
	fun authorizeUrl(authorizationEndpoint: String, clientId: String, redirectUri: String, state: String, challenge: String, scopes: String): String {
		fun e(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
		return authorizationEndpoint + (if ('?' in authorizationEndpoint) "&" else "?") + "client_id=" + e(clientId) + "&response_type=code" +
			"&redirect_uri=" + e(redirectUri) + "&scope=" + e(scopes) + "&state=" + e(state) +
			"&code_challenge=" + e(challenge) + "&code_challenge_method=S256"
	}
}
