/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.tag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagsTest {
	@Test
	fun normalize() {
		assertEquals("urgente", Tags.normalize("  #Urgente "))
		assertEquals("lavoro-remoto", Tags.normalize("lavoro   remoto"))
		assertNull(Tags.normalize(" # "))
		assertEquals("remoto", Tags.normalize("\\#remoto%"))
		assertEquals("però_sì", Tags.normalize("però_sì!"))
		assertNull(Tags.normalize("?!"))
		assertEquals(Tags.MAX, Tags.normalize("x".repeat(50))!!.length)
	}

	@Test
	fun parseAndShow() {
		assertEquals(listOf("urgente", "remoto"), Tags.parse("#urgente, remoto  #URGENTE"))
		assertEquals("#urgente #remoto", Tags.show(listOf("urgente", "remoto")))
	}
}
