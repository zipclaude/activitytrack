/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.start

import it.infraconsulting.activitytrack.domain.geo.Geo
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.LocationKind

/* A known location: company site, client site, or home. */
sealed interface KnownSite {
	val point: GeoPoint
	val radiusM: Int

	data class Company(
		val companyId: Long,
		override val point: GeoPoint,
		override val radiusM: Int,
	) : KnownSite

	data class Client(
		val companyId: Long,
		val clientId: Long,
		override val point: GeoPoint,
		override val radiusM: Int,
	) : KnownSite

	data class Home(
		override val point: GeoPoint,
		override val radiusM: Int,
	) : KnownSite
}

enum class ProposalSource {
	CLIENT_SITE,		/* position within a client site: company + client */
	COMPANY_SITE,		/* position within a company site: company, pick among its clients */
	PREVIOUS_ENTRY,		/* company copied from the last entry of today */
	NONE,
}

data class StartProposal(
	val companyId: Long?,
	val clientId: Long?,
	val locationKind: LocationKind,
	val source: ProposalSource,
)

/*
 * Pre-fill rules for a new entry, in priority order:
 * 1. the nearest site containing the position (distance <= radius):
 *    client site -> company and client; company site -> company only;
 * 2. otherwise the company of the most recent entry started today;
 * 3. otherwise nothing.
 * Location kind follows the nearest containing site or home; OTHER when none
 * matches or the position is unknown. Home never sets a company.
 */
object StartDefaults {
	fun propose(
		position: GeoPoint?,
		sites: List<KnownSite>,
		previousCompanyToday: Long?,
	): StartProposal {
		val match = position?.let { p ->
			sites
				.map { it to Geo.distanceM(p, it.point) }
				.filter { (s, d) -> d <= s.radiusM }
				.minByOrNull { (_, d) -> d }
				?.first
		}

		return when (match) {
			is KnownSite.Client ->
				StartProposal(match.companyId, match.clientId, LocationKind.CLIENT, ProposalSource.CLIENT_SITE)
			is KnownSite.Company ->
				StartProposal(match.companyId, null, LocationKind.COMPANY, ProposalSource.COMPANY_SITE)
			is KnownSite.Home -> fromPrevious(previousCompanyToday, LocationKind.HOME)
			null -> fromPrevious(previousCompanyToday, LocationKind.OTHER)
		}
	}

	private fun fromPrevious(companyId: Long?, kind: LocationKind) =
		if (companyId != null)
			StartProposal(companyId, null, kind, ProposalSource.PREVIOUS_ENTRY)
		else
			StartProposal(null, null, kind, ProposalSource.NONE)
}
