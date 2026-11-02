/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class PkceTest {
	@Test
	fun pendingLoginsExpireAfterFifteenMinutes() {
		val now = 10_000_000_000L
		val stored = mapOf(
			"fresh/client" to "{}", "fresh/verifier" to "v", "fresh/at" to now - 14 * 60_000L,
			"old/client" to "{}", "old/verifier" to "v", "old/at" to now - 16 * 60_000L,
			/* stored before the time was kept */
			"legacy/client" to "{}", "legacy/verifier" to "v",
			/* a clock set back: not kept forever */
			"future/client" to "{}", "future/at" to now + 60 * 60_000L,
		)
		assertEquals(setOf("old", "legacy", "future"), Pkce.expiredStates(stored, now))
		assertEquals(emptySet(), Pkce.expiredStates(emptyMap<String, Any>(), now))
	}
}
