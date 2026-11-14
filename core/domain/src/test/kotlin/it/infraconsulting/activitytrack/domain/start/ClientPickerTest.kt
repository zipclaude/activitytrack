/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.start

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientPickerTest {
	private val clients = listOf(
		ClientChoice(1, companyId = 10, name = "bravo", archived = false, lastUsedMs = null),
		ClientChoice(2, companyId = 10, name = "Alpha", archived = false, lastUsedMs = null),
		ClientChoice(3, companyId = 10, name = "Zulu", archived = false, lastUsedMs = 500),
		ClientChoice(4, companyId = 20, name = "Other", archived = false, lastUsedMs = 900),
		ClientChoice(5, companyId = 10, name = "Old", archived = true, lastUsedMs = 1_000),
	)

	@Test
	fun companyFilterRecentFirstThenName() {
		assertEquals(listOf(3L, 2L, 1L), ClientPicker.order(clients, 10).map { it.id })
	}

	@Test
	fun allCompanies() {
		assertEquals(listOf(4L, 3L, 2L, 1L), ClientPicker.order(clients, null).map { it.id })
	}

	@Test
	fun inputOrderKept() {
		assertEquals(listOf(1L, 2L, 3L), ClientPicker.order(clients, 10, byLastUse = false).map { it.id })
	}

	@Test
	fun archivedOnlyWhenKept() {
		assertEquals(listOf(5L, 3L, 2L, 1L), ClientPicker.order(clients, 10, keep = 5).map { it.id })
	}

	@Test
	fun aCompanyWithOneClientFillsAnEmptyField() {
		/* issue #182: company 20 has one client, 10 several, 30 only an archived one */
		val all = clients + ClientChoice(6, companyId = 30, name = "Gone", archived = true, lastUsedMs = null)
		assertEquals(4L, ClientPicker.afterCompany(20, null, all))
		assertEquals(null, ClientPicker.afterCompany(10, null, all))
		assertEquals(null, ClientPicker.afterCompany(30, null, all))
		/* a client of the company stays, one of another company goes (and the field is then empty: filled) */
		assertEquals(2L, ClientPicker.afterCompany(10, 2, all))
		assertEquals(4L, ClientPicker.afterCompany(20, 2, all))
		assertEquals(null, ClientPicker.afterCompany(10, 4, all))
		/* all companies: nothing filled, the client kept */
		assertEquals(null, ClientPicker.afterCompany(null, null, all))
		assertEquals(3L, ClientPicker.afterCompany(null, 3, all))
		/* an archived client of the company already chosen stays */
		assertEquals(5L, ClientPicker.afterCompany(10, 5, all))
	}
}
