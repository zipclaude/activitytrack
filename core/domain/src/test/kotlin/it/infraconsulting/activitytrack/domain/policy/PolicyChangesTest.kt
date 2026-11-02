/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyChangesTest {
	private val head = "\"format\": \"activitytrack-policy\", \"schema\": 1"

	@Test
	fun keysOfSectionsAndTopLevelValues() {
		val old = "{ $head, \"id\": \"acme\", \"features\": { \"gps\": false, \"todos\": true }, \"settings\": { \"language\": { \"value\": \"it\" } } }"
		val new = "{ $head, \"id\": \"acme\", \"version\": \"2\", \"features\": { \"gps\": true, \"todos\": true }, \"integrations\": { \"antares\": { \"plugin\": \"antares.json\" } } }"
		assertEquals(
			listOf(
				PolicyChanges.Change("features.gps", "false", "true"),
				PolicyChanges.Change("integrations.antares", null, "{\"plugin\":\"antares.json\"}"),
				PolicyChanges.Change("settings.language", "{\"value\":\"it\"}", null),
				PolicyChanges.Change("version", null, "\"2\""),
			),
			PolicyChanges.of(old, new),
		)
	}

	@Test
	fun firstPolicyListsEverythingAndLongValuesAreCut() {
		val long = "x".repeat(200)
		val c = PolicyChanges.of(null, "{ $head, \"id\": \"$long\" }").single()
		assertEquals("id", c.path)
		assertTrue(c.new!!.length == 80 && c.new.endsWith("…"))
		assertEquals(emptyList(), PolicyChanges.of("{ $head }", "{ $head }"))
		/* an unreadable policy in force counts as none */
		assertEquals(1, PolicyChanges.of("not json", "{ $head, \"id\": \"a\" }").size)
	}
}
