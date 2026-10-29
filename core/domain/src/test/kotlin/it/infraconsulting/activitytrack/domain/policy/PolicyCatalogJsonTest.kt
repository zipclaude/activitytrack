/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * docs/policy-catalog.json is what ActivityTrack Web's editor reads (issue
 * #117): it must be the catalog of this code. After changing a key run
 * `UPDATE_POLICY_CATALOG=1 ./gradlew :core:domain:test` and commit the file.
 */
class PolicyCatalogJsonTest {
	private val file = File("../../docs/policy-catalog.json")
	private val rendered = PolicyCatalogJson.render(File("../../docs/POLICY.md").readText())

	@Test
	fun committedCatalogIsCurrent() {
		if (System.getenv("UPDATE_POLICY_CATALOG") != null) file.writeText(rendered)
		assertEquals(rendered, file.readText(), "docs/policy-catalog.json is stale: UPDATE_POLICY_CATALOG=1 ./gradlew :core:domain:test")
	}

	@Test
	fun everyItemHasItsDescription() {
		for (part in listOf("\"key\": \"editableTimes\"", "\"key\": \"km\"", "\"key\": \"calls\"", "\"key\": \"minDurationSec\""))
			assertTrue(part in rendered, part)
		assertTrue("\"doc\": \"\"" !in rendered, "a key without a row in POLICY.md")
	}
}
