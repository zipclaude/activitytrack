/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

/*
 * Order of the master-data lists (backlog 1, owner 2026-10-18): per list
 * alphabetical, by last use or manual (rows dragged in Master data). The
 * order holds in the drop-downs too.
 */
enum class ListOrder { ALPHABETICAL, LAST_USE, MANUAL }

enum class OrderedList { COMPANIES, CLIENTS, ROLES, PRESETS }

/* Defaults keep the orders the app had: clients by last use, the rest by name. */
data class ListOrders(
	val companies: ListOrder = ListOrder.ALPHABETICAL,
	val clients: ListOrder = ListOrder.LAST_USE,
	val roles: ListOrder = ListOrder.ALPHABETICAL,
	val presets: ListOrder = ListOrder.ALPHABETICAL,
) {
	fun of(list: OrderedList): ListOrder = when (list) {
		OrderedList.COMPANIES -> companies
		OrderedList.CLIENTS -> clients
		OrderedList.ROLES -> roles
		OrderedList.PRESETS -> presets
	}

	fun with(list: OrderedList, order: ListOrder): ListOrders = when (list) {
		OrderedList.COMPANIES -> copy(companies = order)
		OrderedList.CLIENTS -> copy(clients = order)
		OrderedList.ROLES -> copy(roles = order)
		OrderedList.PRESETS -> copy(presets = order)
	}
}

object Ordering {
	/*
	 * Archived items always last. ALPHABETICAL: by name, ignoring case.
	 * LAST_USE: most recent first, never-used ones after them by name.
	 * MANUAL: by [position]; items never placed (null: added or imported
	 * since the last drag) after them, oldest first ([id]).
	 */
	fun <T> sort(
		items: List<T>,
		order: ListOrder,
		name: (T) -> String,
		archived: (T) -> Boolean = { false },
		lastUse: (T) -> Long? = { null },
		position: (T) -> Int? = { null },
		id: (T) -> Long = { 0 },
	): List<T> {
		val byName = compareBy(String.CASE_INSENSITIVE_ORDER, name)
		val within: Comparator<T> = when (order) {
			ListOrder.ALPHABETICAL -> byName
			ListOrder.LAST_USE -> compareByDescending<T> { lastUse(it) ?: Long.MIN_VALUE }.then(byName)
			ListOrder.MANUAL -> compareBy<T> { position(it) == null }
				.thenBy { position(it) ?: 0 }
				.thenBy(id)
		}
		return items.sortedWith(compareBy<T> { archived(it) }.then(within))
	}

	/* Moves the item at [from] to [to], shifting the ones between (drag and drop). */
	fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
		if (from == to || from !in items.indices || to !in items.indices)
			return items
		return items.toMutableList().apply { add(to, removeAt(from)) }
	}
}
