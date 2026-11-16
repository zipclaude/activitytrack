/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/* The place of a to-do (issue #192): the dialog sends it, other callers leave it alone. */
class TodoLocalityTest {
	@Test
	fun nullKeepsBlankClearsTextIsTrimmed() {
		assertEquals("Sede Milano", todoLocality("Sede Milano", null))
		assertNull(todoLocality("Sede Milano", "  "))
		assertEquals("Casa", todoLocality(null, " Casa "))
		assertNull(todoLocality(null, null))
	}
}
