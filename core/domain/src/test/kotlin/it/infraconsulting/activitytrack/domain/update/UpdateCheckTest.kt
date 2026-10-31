/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckTest {
	private fun release(tag: String, pre: Boolean = false, draft: Boolean = false) =
		"""{"tag_name": "$tag", "prerelease": $pre, "draft": $draft, "html_url": "https://github.com/zipclaude/activitytrack/releases/tag/$tag"}"""

	private val list = "[" + listOf(
		release("v1.2.0-beta.1", pre = true), release("v1.1.0"), release("v1.3.0", draft = true), release("v1.0.1"), release("v0.9.2"),
	).joinToString() + "]"

	@Test
	fun versionOrder() {
		val ordered = listOf("0.9.2", "1.0.0-beta.2", "1.0.0-beta.10", "1.0.0", "1.0.1", "1.1", "1.10.0")
		assertEquals(ordered, ordered.reversed().sortedWith { a, b -> Versions.compare(a, b) })
		assertEquals(0, Versions.compare("1.1", "1.1.0"))
		assertEquals(0, Versions.compare("v1.1.0", "1.1.0"))
	}

	@Test
	fun stableOnlyUnlessBetas() {
		assertEquals("1.1.0", UpdateCheck.newer(list, "1.0.0", betas = false)?.version)
		val beta = UpdateCheck.newer(list, "1.0.0", betas = true)
		assertEquals("1.2.0-beta.1", beta?.version)
		assertEquals(true, beta?.beta)
		/* a draft never counts; nothing newer than the current one */
		assertNull(UpdateCheck.newer(list, "1.2.0", betas = true))
		/* a beta user on 1.2.0-beta.1 sees 1.2.0 when it comes */
		assertEquals("1.2.0", UpdateCheck.newer("[" + release("v1.2.0") + "]", "1.2.0-beta.1", betas = true)?.version)
		assertNull(UpdateCheck.newer("not json", "1.0.0", betas = false))
	}
}
