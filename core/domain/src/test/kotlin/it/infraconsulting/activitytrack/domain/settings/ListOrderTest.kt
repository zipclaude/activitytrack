/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ListOrderTest {
	private data class Item(val id: Long, val name: String, val archived: Boolean = false, val last: Long? = null, val pos: Int? = null)

	private val items = listOf(
		Item(1, "bravo", last = 100, pos = 2),
		Item(2, "Alpha", pos = 1),
		Item(3, "charlie", last = 300),
		Item(4, "delta", archived = true, last = 900, pos = 0),
		Item(5, "Echo", last = 200),
	)

	private fun sorted(order: ListOrder) =
		Ordering.sort(items, order, { it.name }, { it.archived }, { it.last }, { it.pos }, { it.id }).map { it.id }

	@Test
	fun alphabeticalIgnoresCaseArchivedLast() = assertEquals(listOf(2L, 1L, 3L, 5L, 4L), sorted(ListOrder.ALPHABETICAL))

	@Test
	fun lastUseThenNeverUsedByName() = assertEquals(listOf(3L, 5L, 1L, 2L, 4L), sorted(ListOrder.LAST_USE))

	@Test
	fun manualThenUnplacedOldestFirst() = assertEquals(listOf(2L, 1L, 3L, 5L, 4L), sorted(ListOrder.MANUAL))

	@Test
	fun move() {
		assertEquals(listOf("b", "c", "a"), Ordering.move(listOf("a", "b", "c"), 0, 2))
		assertEquals(listOf("c", "a", "b"), Ordering.move(listOf("a", "b", "c"), 2, 0))
		assertEquals(listOf("a", "b"), Ordering.move(listOf("a", "b"), 0, 5))
	}

	@Test
	fun perListAccess() {
		val o = ListOrders().with(OrderedList.ROLES, ListOrder.MANUAL)
		assertEquals(ListOrder.MANUAL, o.of(OrderedList.ROLES))
		assertEquals(ListOrder.LAST_USE, o.of(OrderedList.CLIENTS))
	}
}
