/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.domain.start

/* A client as offered by the start flow; [lastUsedMs] is the newest entry on it, if any. */
data class ClientChoice(
	val id: Long,
	val companyId: Long?,
	val name: String,
	val archived: Boolean,
	val lastUsedMs: Long?,
)

/*
 * Client picker order (DESIGN §5): only the clients of [companyId] when a
 * company is selected (all companies otherwise), archived clients hidden
 * unless [keep] (the current choice); most recently used first, never-used
 * clients after them by name. [byLastUse] false keeps the order of
 * [clients] (M16: the list order chosen in Master data).
 */
object ClientPicker {
	fun order(clients: List<ClientChoice>, companyId: Long?, keep: Long? = null, byLastUse: Boolean = true): List<ClientChoice> {
		val shown = clients
			.filter { companyId == null || it.companyId == companyId }
			.filter { !it.archived || it.id == keep }
		return if (!byLastUse) shown else shown.sortedWith(
			compareByDescending<ClientChoice> { it.lastUsedMs ?: Long.MIN_VALUE }
				.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
		)
	}

	/*
	 * The client once [companyId] is chosen with [current] in the client
	 * field: a client of another company goes; an empty field takes the
	 * company's client when it has exactly one not archived (issue #182);
	 * otherwise it stays empty. No company (all of them) fills nothing.
	 */
	fun afterCompany(companyId: Long?, current: Long?, clients: List<ClientChoice>): Long? {
		val kept = current?.takeIf { id -> clients.any { it.id == id && (companyId == null || it.companyId == companyId) } }
		if (kept != null || companyId == null)
			return kept
		return clients.filter { it.companyId == companyId && !it.archived }.singleOrNull()?.id
	}
}
