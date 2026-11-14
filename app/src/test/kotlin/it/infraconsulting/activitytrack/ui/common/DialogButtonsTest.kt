/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class DialogButtonsTest {
	@Test
	fun rowsKeepTheLastPairTogether() {
		assertEquals(emptyList(), dialogButtonRows(emptyList<String>()))
		assertEquals(listOf(listOf("Annulla")), dialogButtonRows(listOf("Annulla")))
		assertEquals(listOf(listOf("Annulla", "Salva")), dialogButtonRows(listOf("Annulla", "Salva")))
		/* Elimina alone above Annulla · Salva (issue #186) */
		assertEquals(listOf(listOf("Elimina"), listOf("Annulla", "Salva")), dialogButtonRows(listOf("Elimina", "Annulla", "Salva")))
		assertEquals(listOf(listOf("Elimina", "Apri"), listOf("Annulla", "Salva")), dialogButtonRows(listOf("Elimina", "Apri", "Annulla", "Salva")))
		/* one per row (the stop choices), three in a row (the time grid) */
		assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), dialogButtonRows(listOf("a", "b", "c"), perRow = 1))
		assertEquals(listOf(listOf("a", "b", "c")), dialogButtonRows(listOf("a", "b", "c"), perRow = 3))
	}
}
