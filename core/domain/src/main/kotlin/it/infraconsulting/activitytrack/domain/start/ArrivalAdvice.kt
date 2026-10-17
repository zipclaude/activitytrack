/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.start

import it.infraconsulting.activitytrack.domain.geo.GeoPoint

/* What the "Arrived?" prompt proposes for a travelling entry (SPEC §5). */
sealed interface Arrival {
	/* client site: that client (when the entry has none or the same one), switch to WORK */
	data class AtClient(val companyId: Long, val clientId: Long) : Arrival

	/* company site, or a client site of another client: switch to WORK, entry unchanged */
	data object Work : Arrival

	/* home: close the entry (return travel) */
	data object Home : Arrival

	/* nowhere known: ask, WORK or keep travelling */
	data object Unknown : Arrival
}

/*
 * Proposal at the stop position, from the nearest site containing it.
 * A client site of a client other than the entry's does not replace the
 * entry's client: the user decides in the entry screen.
 */
object ArrivalAdvice {
	fun propose(position: GeoPoint, sites: List<KnownSite>, entryClientId: Long?): Arrival =
		when (val s = nearestContaining(position, sites)) {
			is KnownSite.Client ->
				if (entryClientId == null || entryClientId == s.clientId)
					Arrival.AtClient(s.companyId, s.clientId)
				else
					Arrival.Work
			is KnownSite.Company -> Arrival.Work
			is KnownSite.Home -> Arrival.Home
			null -> Arrival.Unknown
		}
}
