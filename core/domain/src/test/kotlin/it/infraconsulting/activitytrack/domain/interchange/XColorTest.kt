/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XColorTest {
	@Test
	fun roundTrip() {
		assertEquals("#3D7BEA", XColor.format(0x3D7BEA))
		assertEquals(0x3D7BEA, XColor.parse("#3D7BEA"))
		assertEquals(0x3D7BEA, XColor.parse(" 3d7bea "))
		/* an alpha byte is dropped */
		assertEquals("#3D7BEA", XColor.format(0xFF3D7BEA.toInt()))
	}

	@Test
	fun invalidIsNoColour() {
		assertNull(XColor.parse(null))
		assertNull(XColor.parse("#3D7"))
		assertNull(XColor.parse("blue"))
		assertNull(XColor.format(null))
	}
}
