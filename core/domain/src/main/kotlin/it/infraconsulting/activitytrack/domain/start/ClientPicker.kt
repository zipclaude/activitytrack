/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.start

/* A client as offered by the start flow; [lastUsedMs] is the newest entry on it, if any. */
data class ClientChoice(
	val id: Long,
	val companyId: Long,
	val name: String,
	val archived: Boolean,
	val lastUsedMs: Long?,
)

/*
 * Client picker order (DESIGN §5): only the clients of [companyId] when a
 * company is selected (all companies otherwise), archived clients hidden
 * unless [keep] (the current choice); most recently used first, never-used
 * clients after them by name.
 */
object ClientPicker {
	fun order(clients: List<ClientChoice>, companyId: Long?, keep: Long? = null): List<ClientChoice> =
		clients
			.filter { companyId == null || it.companyId == companyId }
			.filter { !it.archived || it.id == keep }
			.sortedWith(
				compareByDescending<ClientChoice> { it.lastUsedMs ?: Long.MIN_VALUE }
					.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
			)
}
