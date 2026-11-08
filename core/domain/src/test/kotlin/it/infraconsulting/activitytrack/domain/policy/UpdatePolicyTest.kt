/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import it.infraconsulting.activitytrack.domain.settings.AppSettings
import it.infraconsulting.activitytrack.domain.update.UpdateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/* The update settings a policy may fix (issue #1): where the manifest comes from, and the channels. */
class UpdatePolicyTest {
	private fun apply(settings: String): AppSettings {
		val r = PolicyParser.parse("""{"format": "activitytrack-policy", "schema": 1, "settings": {$settings}}""")
		return (r as PolicyResult.Valid).policy.applyTo(AppSettings())
	}

	private fun invalid(settings: String) =
		assertIs<PolicyResult.Invalid>(PolicyParser.parse("""{"format": "activitytrack-policy", "schema": 1, "settings": {$settings}}"""))

	@Test
	fun sourceUrlAndChannels() {
		val s = apply(""""updateSource": {"value": "custom", "locked": true}, "updateUrl": "https://updates.example.com/m.json", "updateChannels": "test, prod" """)
		assertEquals(UpdateSource.CUSTOM, s.updateSource)
		assertEquals("https://updates.example.com/m.json", s.updateUrl)
		assertEquals(setOf("test", "prod"), s.updateChannels)
		assertEquals(UpdateSource.UPSTREAM, AppSettings().updateSource)
		assertEquals(setOf("prod"), AppSettings().updateChannels)
	}

	@Test
	fun theOldSwitchOfTheBetasIsTheBetaChannel() {
		assertEquals(setOf("prod", "beta"), apply(""""updateBetas": true""").updateChannels)
		assertEquals(setOf("prod"), apply(""""updateBetas": false""").updateChannels)
	}

	@Test
	fun badValuesAreRefused() {
		invalid(""""updateSource": "ftp"""")
		invalid(""""updateUrl": "http://updates.example.com/m.json"""")
		invalid(""""updateChannels": "prod,nightly"""")
	}
}
