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

	@Test
	fun kmUsesRoadDistanceWhenKnown() {
		val fixes = listOf(fix(0, 0.0), fix(5, 1000.0), fix(10, 2000.0))
		assertEquals(2.5, KmEstimate.km(fixes, 1.25), 1e-6)
		assertEquals(1.4 + 1.25, KmEstimate.km(fixes, 1.25, listOf(1400.0, null)), 1e-6)
	}

	@Test
	fun jitterLegsCountZero() {
		val fixes = listOf(fix(0, 0.0), fix(5, 15.0, 20.0), fix(10, 0.0, 20.0), fix(15, 1000.0))
		assertEquals(1.25, KmEstimate.km(fixes, 1.25), 1e-6)
	}
}
