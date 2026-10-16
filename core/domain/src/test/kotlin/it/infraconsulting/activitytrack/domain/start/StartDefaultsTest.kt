/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.start

import it.infraconsulting.activitytrack.domain.geo.Geo
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.LocationKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartDefaultsTest {
	/* 0.001 deg of latitude ~ 111.195 m */
	private val base = GeoPoint(45.0, 9.0)
	private fun north(m: Double) = GeoPoint(base.lat + m / 111_195.0, base.lon)

	private val companySite = KnownSite.Company(companyId = 1, point = base, radiusM = 100)
	private val clientSite = KnownSite.Client(companyId = 2, clientId = 20, point = north(150.0), radiusM = 100)
	private val home = KnownSite.Home(point = north(5_000.0), radiusM = 100)
	private val sites = listOf(companySite, clientSite, home)

	@Test
	fun haversine() {
		val d = Geo.distanceM(GeoPoint(45.0, 9.0), GeoPoint(45.001, 9.0))
		assertTrue(abs(d - 111.195) < 0.01, "got $d")
		assertEquals(0.0, Geo.distanceM(base, base))
	}

	@Test
	fun companySiteSetsCompanyOnly() {
		val p = StartDefaults.propose(north(20.0), sites, previousCompanyToday = 9)
		assertEquals(StartProposal(1, null, LocationKind.COMPANY, ProposalSource.COMPANY_SITE), p)
	}

	@Test
	fun clientSiteSetsCompanyAndClient() {
		val p = StartDefaults.propose(north(140.0), sites, previousCompanyToday = 1)
		assertEquals(StartProposal(2, 20, LocationKind.CLIENT, ProposalSource.CLIENT_SITE), p)
	}

	@Test
	fun overlappingSitesNearestWins() {
		/* at 80 m: inside both (company 80 m, client 70 m) */
		val p = StartDefaults.propose(north(80.0), sites, previousCompanyToday = null)
		assertEquals(ProposalSource.CLIENT_SITE, p.source)
	}

	@Test
	fun homeUsesPreviousCompany() {
		val p = StartDefaults.propose(north(5_010.0), sites, previousCompanyToday = 7)
		assertEquals(StartProposal(7, null, LocationKind.HOME, ProposalSource.PREVIOUS_ENTRY), p)
	}

	@Test
	fun unknownPositionWithoutPrevious() {
		val p = StartDefaults.propose(null, sites, previousCompanyToday = null)
		assertEquals(StartProposal(null, null, LocationKind.OTHER, ProposalSource.NONE), p)
	}

	@Test
	fun outsideEverySiteUsesPrevious() {
		val p = StartDefaults.propose(north(1_000.0), sites, previousCompanyToday = 3)
		assertEquals(StartProposal(3, null, LocationKind.OTHER, ProposalSource.PREVIOUS_ENTRY), p)
	}
}
