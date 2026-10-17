/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.start

import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class ArrivalAdviceTest {
	private val client = GeoPoint(45.40, 12.30)
	private val office = GeoPoint(45.45, 12.35)
	private val home = GeoPoint(45.50, 12.40)
	private val sites = listOf(
		KnownSite.Client(companyId = 1, clientId = 10, point = client, radiusM = 100),
		KnownSite.Company(companyId = 1, point = office, radiusM = 100),
		KnownSite.Home(point = home, radiusM = 100),
	)

	@Test
	fun clientSiteAssignsClientWhenFreeOrSame() {
		assertEquals(Arrival.AtClient(1, 10), ArrivalAdvice.propose(client, sites, entryClientId = null))
		assertEquals(Arrival.AtClient(1, 10), ArrivalAdvice.propose(client, sites, entryClientId = 10))
	}

	@Test
	fun otherClientSiteKeepsTheEntryClient() {
		assertEquals(Arrival.Work, ArrivalAdvice.propose(client, sites, entryClientId = 11))
	}

	@Test
	fun companyHomeAndUnknown() {
		assertEquals(Arrival.Work, ArrivalAdvice.propose(office, sites, null))
		assertEquals(Arrival.Home, ArrivalAdvice.propose(home, sites, 10))
		assertEquals(Arrival.Unknown, ArrivalAdvice.propose(GeoPoint(44.0, 11.0), sites, null))
	}
}
