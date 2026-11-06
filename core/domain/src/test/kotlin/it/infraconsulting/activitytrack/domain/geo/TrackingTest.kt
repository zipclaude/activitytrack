/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackingTest {
	private val base = GeoPoint(45.4, 12.3)
	private val min = 60_000L

	/* [north] metres north of base; 1° latitude = π R / 180 on the IUGG mean sphere */
	private fun fix(minute: Long, north: Double, acc: Double = 10.0) =
		Fix(minute * min, GeoPoint(base.lat + north / 111_195.08, base.lon), acc)

	@Test
	fun noDepartureWhileNearAnchor() {
		val fixes = listOf(fix(0, 0.0), fix(5, 40.0), fix(10, 80.0), fix(15, 20.0))
		assertNull(Movement.departure(fixes, 100.0))
	}

	@Test
	fun singleAwayFixIsIgnored() {
		val fixes = listOf(fix(0, 0.0), fix(5, 300.0), fix(10, 10.0), fix(15, 300.0))
		assertNull(Movement.departure(fixes, 100.0))
	}

	@Test
	fun departureAtFirstOfTwoAwayFixes() {
		val fixes = listOf(fix(0, 0.0), fix(5, 30.0), fix(10, 250.0), fix(15, 900.0))
		assertEquals(10 * min, Movement.departure(fixes, 100.0))
	}

	@Test
	fun accuracyIsSubtracted() {
		/* 150 m away but ±60 m: 90 m is not beyond 100 m */
		val fixes = listOf(fix(0, 0.0), fix(5, 150.0, 60.0), fix(10, 150.0, 60.0))
		assertNull(Movement.departure(fixes, 100.0))
	}

	@Test
	fun anchorSkipsInaccurateFixes() {
		/* the first fix (±500 m) is not the anchor; from the second one, 200 m is no move */
		val fixes = listOf(fix(0, 1000.0, 500.0), fix(5, 0.0), fix(10, 30.0), fix(15, 60.0))
		assertNull(Movement.departure(fixes, 100.0))
	}

	@Test
	fun noArrivalWhileMoving() {
		val fixes = (0L..6L).map { fix(it * 5, it * 2000.0) }
		assertNull(Stationarity.arrival(fixes, 100.0, 15 * min))
	}

	@Test
	fun arrivalAtFirstFixOfTheStop() {
		val moving = listOf(fix(0, 0.0), fix(5, 3000.0), fix(10, 6000.0))
		val stop = listOf(fix(15, 9000.0), fix(20, 9030.0), fix(25, 8980.0), fix(30, 9010.0))
		assertEquals(15 * min, Stationarity.arrival(moving + stop, 100.0, 15 * min))
		/* 10 minutes of stop are not enough */
		assertNull(Stationarity.arrival(moving + stop.take(3), 100.0, 15 * min))
	}

	@Test
	fun inaccurateFixDoesNotBreakTheStop() {
		val fixes = listOf(fix(0, 0.0), fix(5, 5000.0), fix(10, 5020.0), fix(15, 7000.0, 900.0), fix(20, 5010.0))
		assertEquals(5 * min, Stationarity.arrival(fixes, 100.0, 15 * min))
	}

	/* [sec] seconds after the start, [north] metres north of base */
	private fun fixAt(sec: Long, north: Double, acc: Double = 10.0) =
		Fix(sec * 1000, GeoPoint(base.lat + north / 111_195.08, base.lon), acc)

	@Test
	fun kmUsesRoadDistanceWhenKnown() {
		/* 5 minutes apart: far apart in time, so the road distance or the factor */
		val fixes = listOf(fix(0, 0.0), fix(5, 1000.0), fix(10, 2000.0))
		assertEquals(2.5, KmEstimate.km(fixes, 1.25), 1e-6)
		assertEquals(1.4 + 1.25, KmEstimate.km(fixes, 1.25, mapOf(0 to 1400.0)), 1e-6)
	}

	@Test
	fun jitterLegsCountZero() {
		val fixes = listOf(fix(0, 0.0), fix(5, 15.0, 20.0), fix(10, 0.0, 20.0), fix(15, 1000.0))
		assertEquals(1.25, KmEstimate.km(fixes, 1.25), 1e-6)
	}

	@Test
	fun closeFixesAreJoinedDirectly() {
		/* every 30 s: the straight line, whatever the factor and the router say */
		val fixes = (0..10).map { fixAt(it * 30L, it * 500.0) }
		assertEquals(5.0, KmEstimate.km(fixes, 1.25), 1e-6)
		assertEquals(5.0, KmEstimate.km(fixes, 2.0, (0..9).associateWith { 4000.0 }), 1e-6)
		assertEquals(emptyList(), KmEstimate.roadRuns(fixes))
		/* exactly 2 minutes still counts as close */
		assertEquals(1.0, KmEstimate.km(listOf(fixAt(0, 0.0), fixAt(120, 1000.0)), 1.25), 1e-6)
		assertEquals(1.25, KmEstimate.km(listOf(fixAt(0, 0.0), fixAt(121, 1000.0)), 1.25), 1e-6)
	}

	@Test
	fun roadRunsGroupTheFarApartLegs() {
		/* legs 0-1 close, 1-2 and 2-3 far, 3-4 close, 4-5 far, 5-6 jitter although far */
		val fixes = listOf(
			fixAt(0, 0.0), fixAt(30, 300.0), fixAt(330, 5000.0), fixAt(630, 9000.0), fixAt(660, 9300.0),
			fixAt(960, 12000.0), fixAt(1260, 12005.0, 20.0),
		)
		assertEquals(listOf(1..2, 4..4), KmEstimate.roadRuns(fixes))
	}

	@Test
	fun roadDistanceThatIsNotARoadFallsBackToTheFactor() {
		/* the router snapped to the other carriageway: 8 km for a 1 km leg */
		val fixes = listOf(fix(0, 0.0), fix(5, 1000.0))
		assertEquals(1.25, KmEstimate.km(fixes, 1.25, mapOf(0 to 8000.0)), 1e-6)
		assertEquals(2.9, KmEstimate.km(fixes, 1.25, mapOf(0 to 2900.0)), 1e-6)
	}

	@Test
	fun impossibleLegCountsZero() {
		/* 20 km in 10 s */
		val fixes = listOf(fixAt(0, 0.0), fixAt(10, 20_000.0))
		assertEquals(0.0, KmEstimate.km(fixes, 1.25), 1e-9)
		assertEquals(emptyList(), KmEstimate.roadRuns(fixes))
	}

	@Test
	fun lonelyFixIsDropped() {
		/* 30 m/s along the road, one fix 40 km off the road in between */
		val fixes = (0..6).map { fixAt(it * 30L, it * 900.0) }.toMutableList()
		fixes[3] = Fix(fixes[3].atMs, GeoPoint(base.lat, base.lon + 0.5), 10.0)
		val clean = KmEstimate.plausible(fixes)
		assertEquals(listOf(0, 1, 2, 4, 5, 6).map { fixes[it] }, clean)
		assertEquals(5.4, KmEstimate.km(clean, 1.25), 1e-6)
		/* the same for a stray first or last fix, which has a single neighbour */
		val road = (0..6).map { fixAt(it * 30L, it * 900.0) }
		val first = road.toMutableList().also { it[0] = Fix(it[0].atMs, GeoPoint(base.lat, base.lon + 0.5), 10.0) }
		assertEquals(first.drop(1), KmEstimate.plausible(first))
		val last = road.toMutableList().also { it[6] = Fix(it[6].atMs, GeoPoint(base.lat, base.lon + 0.5), 10.0) }
		assertEquals(last.dropLast(1), KmEstimate.plausible(last))
	}

	@Test
	fun fastButPossibleTripKeepsAllFixes() {
		/* 200 km/h on a motorway */
		val fixes = (0..10).map { fixAt(it * 20L, it * 1111.0) }
		assertEquals(fixes, KmEstimate.plausible(fixes))
		assertEquals(emptyList(), KmEstimate.plausible(emptyList()))
		assertEquals(fixes.take(1), KmEstimate.plausible(fixes.take(1)))
	}

	@Test
	fun denseTripHasItsOwnLength() {
		/* the case that gave 857 km: 700 fixes every 13 s along 227 km; no router involved */
		val fixes = (0..699).map { fixAt(it * 13L, it * 325.0) }
		assertEquals(227.2, KmEstimate.km(KmEstimate.plausible(fixes), 1.25), 0.1)
	}
}
