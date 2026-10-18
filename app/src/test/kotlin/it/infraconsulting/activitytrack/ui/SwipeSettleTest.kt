/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui

import it.infraconsulting.activitytrack.ui.common.Settle
import it.infraconsulting.activitytrack.ui.common.settle
import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeSettleTest {
	/* a 1000 px card, 200 px of buttons toward the end, 100 px toward the start */
	private fun at(offset: Float, velocity: Float = 0f, full: Boolean = true) = settle(offset, velocity, 1000f, 200f, 100f, full)

	@Test
	fun shortDragsCloseOrOpen() {
		assertEquals(Settle.Closed, at(90f))
		assertEquals(Settle.Open(towardEnd = true), at(110f))
		assertEquals(Settle.Closed, at(-40f))
		assertEquals(Settle.Open(towardEnd = false), at(-60f))
	}

	@Test
	fun flingOpensItsSide() {
		assertEquals(Settle.Open(towardEnd = true), at(30f, velocity = 2000f))
		/* a fling back toward the middle closes */
		assertEquals(Settle.Closed, at(30f, velocity = -2000f))
	}

	@Test
	fun longSwipeRunsTheFirstActionWhenEnabled() {
		assertEquals(Settle.Run(towardEnd = true), at(700f))
		assertEquals(Settle.Run(towardEnd = false), at(-650f))
		assertEquals(Settle.Open(towardEnd = true), at(700f, full = false))
	}

	@Test
	fun aSideWithoutButtonsNeverOpens() {
		assertEquals(Settle.Closed, settle(500f, 3000f, 1000f, 0f, 100f, true))
	}
}
