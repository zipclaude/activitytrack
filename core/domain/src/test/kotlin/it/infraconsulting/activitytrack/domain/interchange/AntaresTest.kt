/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AntaresTest {
	@Test
	fun pkceRfc7636Vector() {
		/* RFC 7636, appendix B */
		assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
		val v = Pkce.verifier()
		assertEquals(43, v.length)
		assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
	}

	@Test
	fun authorizeUrlEncodesEverything() {
		val url = authorizeUrl(
			"https://idp/connect/authorize", "app", "it.infraconsulting.activitytrack:/oauth2redirect", "s1", "c1", AntaresConnector.SCOPES,
		)
		assertEquals(
			"https://idp/connect/authorize?client_id=app&response_type=code" +
				"&redirect_uri=it.infraconsulting.activitytrack%3A%2Foauth2redirect" +
				"&scope=openid%20profile%20public-api%20offline_access&state=s1&code_challenge=c1&code_challenge_method=S256",
			url,
		)
	}

	@Test
	fun closeAndFetchOptions() {
		assertEquals(60, AntaresConnector.fetchMinutes(null))
		assertEquals(60, AntaresConnector.fetchMinutes("x"))
		assertEquals(15, AntaresConnector.fetchMinutes("5"))
		assertEquals(1440, AntaresConnector.fetchMinutes("9999"))
		assertEquals(true, AntaresConnector.offersSendOnClose(null, null, fromTicket = true))
		assertEquals(false, AntaresConnector.offersSendOnClose(null, null, fromTicket = false))
		assertEquals(true, AntaresConnector.offersSendOnClose("always", "all", fromTicket = false))
		assertEquals(false, AntaresConnector.offersSendOnClose("off", "all", fromTicket = true))
	}
}
