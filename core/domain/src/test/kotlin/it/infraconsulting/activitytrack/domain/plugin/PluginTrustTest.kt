/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginTrustTest {
	private val antares = File("../../plugins/antares.json").readText()

	@Test
	fun endpointsOfTheAntaresPlugin() {
		val e = PluginTrust.endpoints(antares)
		assertTrue("http.baseUrl: \"@option.apiUrl\"" in e, e.toString())
		assertTrue("auth.issuer: \"@option.identityUrl\"" in e, e.toString())
		assertTrue("auth.type: \"oauth2Pkce\"" in e, e.toString())
		/* the options' default addresses count too */
		assertTrue(e.any { it.startsWith("url: https://") }, e.toString())
	}

	@Test
	fun absoluteAddressesAnywhereCount() {
		val d = """{ "http": { "baseUrl": "https://a.example/api", "headers": { "X": "1" } }, "auth": { "type": "oauth2Pkce", "tokenUrl": "https://id.example/token" },
			"export": { "create": { "method": "POST", "path": "https://elsewhere.example/x" } } }"""
		assertEquals(
			setOf("http.baseUrl: \"https://a.example/api\"", "auth.type: \"oauth2Pkce\"", "auth.tokenUrl: \"https://id.example/token\"", "url: https://elsewhere.example/x"),
			PluginTrust.endpoints(d),
		)
		assertEquals(emptySet(), PluginTrust.endpoints("not json"))
	}

	@Test
	fun decisions() {
		val e = setOf("http.baseUrl: \"https://a\"")
		val moved = setOf("http.baseUrl: \"https://b\"")
		/* new: asked with every address */
		val ask = assertIs<PluginTrust.Decision.Ask>(PluginTrust.decide("f1", e, null, null, false))
		assertTrue(ask.isNew)
		assertEquals(e.toList(), ask.added)
		/* signed by the pinned signer: used */
		assertEquals(PluginTrust.Decision.Use(false), PluginTrust.decide("f1", e, null, null, true))
		val c = PluginTrust.Confirmed("f1", e)
		assertEquals(PluginTrust.Decision.Use(false), PluginTrust.decide("f1", e, c, null, false))
		/* another file, same addresses: used, the record updated */
		assertEquals(PluginTrust.Decision.Use(true), PluginTrust.decide("f2", e, c, null, false))
		/* addresses changed: asked with the difference */
		assertEquals(PluginTrust.Decision.Ask(false, moved.toList(), e.toList()), PluginTrust.decide("f3", moved, c, null, false))
		/* refused: not asked again until the file changes */
		assertEquals(PluginTrust.Decision.Refused, PluginTrust.decide("f3", moved, c, "f3", false))
		assertIs<PluginTrust.Decision.Ask>(PluginTrust.decide("f4", moved, c, "f3", false))
		assertEquals(PluginTrust.Decision.Refused, PluginTrust.decide("f1", e, null, "f1", false))
	}

	@Test
	fun storedForm() {
		val c = PluginTrust.Confirmed("ab", setOf("url: https://x", "auth.type: \"none\""))
		assertEquals(c, PluginTrust.decode(PluginTrust.encode(c)))
		assertEquals(null, PluginTrust.decode("garbage"))
		assertEquals(null, PluginTrust.decode(null))
	}
}
