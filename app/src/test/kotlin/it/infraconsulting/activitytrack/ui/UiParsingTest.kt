/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui

import it.infraconsulting.activitytrack.ui.common.formatMin
import it.infraconsulting.activitytrack.ui.common.parseDecimal
import it.infraconsulting.activitytrack.ui.common.parseHhMm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiParsingTest {
	@Test
	fun hhMm() {
		assertEquals(510, parseHhMm("08:30"))
		assertEquals(510, parseHhMm("8.30"))
		assertEquals(480, parseHhMm(" 8 "))
		assertEquals(0, parseHhMm("0:00"))
		assertEquals(1440, parseHhMm("24:00"))
		assertNull(parseHhMm("24:01"))
		assertNull(parseHhMm("12:60"))
		assertNull(parseHhMm("12:30:00"))
		assertNull(parseHhMm(""))
		assertNull(parseHhMm("ab"))
		assertEquals("06:00", formatMin(360))
		assertEquals("24:00", formatMin(1440))
	}

	@Test
	fun decimal() {
		assertEquals(45.4642, parseDecimal("45,4642"))
		assertEquals(-9.19, parseDecimal(" -9.19 "))
		assertNull(parseDecimal("4,5,6"))
	}

	@Test
	fun backStackKeysRoundTrip() {
		val all = listOf(
			Screen.Home, Screen.Start, Screen.Settings, Screen.MasterData, Screen.Companies, Screen.Company(7),
			Screen.Clients, Screen.Client(42), Screen.Roles, Screen.Presets, Screen.Places, Screen.Schedule,
		)
		all.forEach { assertEquals(it, Screen.parse(it.key())) }
		assertNull(Screen.parse("company/x"))
		assertNull(Screen.parse("unknown"))
	}
}
