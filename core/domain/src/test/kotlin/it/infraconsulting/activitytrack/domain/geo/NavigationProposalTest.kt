/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.domain.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/* Issue #193: when to offer the navigator, and the intents' URIs. */
class NavigationProposalTest {
	/* Padova and Milano: about 210 km apart; Mestre about 30 km from Padova, Vigonza about 10 */
	private val padova = GeoPoint(45.4064, 11.8768)
	private val milano = NavTarget("Sede Milano", GeoPoint(45.4642, 9.1900))
	private val mestre = NavTarget("Casa", GeoPoint(45.4903, 12.2425))
	private val vigonza = NavTarget("Magazzino", GeoPoint(45.4446, 11.9817))

	@Test
	fun aNamedSiteFarAwayIsOffered() {
		assertEquals(milano, NavigationProposal.target(" sede milano ", listOf(listOf(milano)), padova))
		/* home, 30 km */
		assertEquals(mestre, NavigationProposal.target("Casa", listOf(emptyList(), emptyList(), listOf(mestre)), padova))
	}

	@Test
	fun theClientsSiteWinsOverTheCompanysOfTheSameName() {
		val clientOne = milano.copy(point = GeoPoint(45.0703, 7.6869))
		assertEquals(clientOne, NavigationProposal.target("Sede Milano", listOf(listOf(clientOne), listOf(milano)), padova))
	}

	@Test
	fun nothingWhenNearUnknownOrWithoutPosition() {
		/* about 10 km: within the threshold */
		assertNull(NavigationProposal.target("Magazzino", listOf(listOf(vigonza)), padova))
		assertNull(NavigationProposal.target("Altrove", listOf(listOf(milano)), padova))
		assertNull(NavigationProposal.target("  ", listOf(listOf(milano)), padova))
		assertNull(NavigationProposal.target(null, listOf(listOf(milano)), padova))
		assertNull(NavigationProposal.target("Sede Milano", listOf(listOf(milano)), null))
	}

	@Test
	fun theThresholdIsTwentyKilometres() {
		val p = GeoPoint(45.0, 9.0)
		/* north by 18 and 22 km (about 111.2 km a degree of latitude) */
		val near = NavTarget("X", GeoPoint(45.0 + 18.0 / 111.2, 9.0))
		val far = NavTarget("X", GeoPoint(45.0 + 22.0 / 111.2, 9.0))
		assertNull(NavigationProposal.target("X", listOf(listOf(near)), p))
		assertEquals(far, NavigationProposal.target("X", listOf(listOf(far)), p))
	}

	@Test
	fun urisUseADotAndEncodeTheLabel() {
		assertEquals("google.navigation:q=45.464200,9.190000", NavigationProposal.navigationUri(milano))
		assertEquals("geo:45.464200,9.190000?q=45.464200,9.190000(Sede%20Milano)", NavigationProposal.geoUri(milano))
	}
}
