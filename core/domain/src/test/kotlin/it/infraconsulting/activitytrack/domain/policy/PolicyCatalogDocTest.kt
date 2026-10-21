/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/*
 * docs/POLICY.md is the reference of every key a policy may use: a key of
 * the catalogue missing there fails the build, and the sample policy must
 * be valid.
 */
class PolicyCatalogDocTest {
	private val doc = File("../../docs/POLICY.md").readText()

	@Test
	fun everyKeyIsDocumented() {
		val keys = Feature.entries.map { it.key } + Field.entries.map { it.key } + SettingsCatalog.all.map { it.key } +
			listOf("askTravelDone", "closeCompleteOnStop", "minDurationSec", "exportMaxDays")
		assertEquals(emptyList(), keys.filter { "`$it`" !in doc })
	}

	@Test
	fun sampleIsValid() {
		val r = PolicyParser.parse(File("../../docs/samples/policy-example.json").readText())
		assertIs<PolicyResult.Valid>(r, (r as? PolicyResult.Invalid)?.errors?.joinToString("\n").orEmpty())
	}
}
