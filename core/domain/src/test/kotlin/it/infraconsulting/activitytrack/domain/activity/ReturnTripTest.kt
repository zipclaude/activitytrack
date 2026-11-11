/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind.PAUSE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.TRAVEL
import it.infraconsulting.activitytrack.domain.model.SegmentKind.WORK
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReturnTripTest {
	private val home = GeoPoint(45.0, 9.0)
	private val client = GeoPoint(45.5, 9.5)
	private fun t(hhmm: String) = Instant.parse("2026-11-11T$hhmm:00Z")
	private fun seg(kind: SegmentKind, from: String, to: String?, km: Double? = null, first: GeoPoint? = null, last: GeoPoint? = null) =
		TripSegment(kind, t(from), to?.let(::t), kmEstimated = km, kmDeclared = km?.plus(1), firstSample = first, lastSample = last)

	private val outbound = seg(TRAVEL, "07:10", "07:55", 42.0, home, client)
	private val day = listOf(outbound, seg(WORK, "07:55", "10:00"), seg(PAUSE, "10:00", "10:30"), seg(WORK, "10:30", null))

	@Test
	fun appliesAfterTravelThenWorkWithoutTravelBack() {
		assertTrue(ReturnTrip.applies(day))
		assertTrue(ReturnTrip.applies(day.reversed()))
		/* already travelling back */
		assertFalse(ReturnTrip.applies(day + seg(TRAVEL, "12:00", null)))
		/* work only, travel only, travel after work only */
		assertFalse(ReturnTrip.applies(listOf(seg(WORK, "08:00", null))))
		assertFalse(ReturnTrip.applies(listOf(seg(TRAVEL, "08:00", null))))
		assertFalse(ReturnTrip.applies(listOf(seg(WORK, "08:00", "09:00"), seg(TRAVEL, "09:00", null))))
		assertFalse(ReturnTrip.applies(emptyList<TripSegment<GeoPoint>>()))
	}

	@Test
	fun thirdChoiceOnlyWithEditableTimes() {
		assertEquals(ReturnChoice.entries.toList(), ReturnTrip.choices(day, editableTimes = true))
		assertEquals(listOf(ReturnChoice.TRAVEL_BACK, ReturnChoice.STOP), ReturnTrip.choices(day, editableTimes = false))
		assertEquals(emptyList(), ReturnTrip.choices(listOf(seg(WORK, "08:00", null)), editableTimes = true))
	}

	@Test
	fun outboundIsTheLastTravelBeforeTheFirstWorkAfterIt() {
		val a = seg(TRAVEL, "06:00", "06:30")
		val b = seg(TRAVEL, "06:40", "07:00")
		val w = seg(WORK, "07:00", "08:00")
		val c = seg(TRAVEL, "08:00", "08:20")
		val w2 = seg(WORK, "08:20", null)
		assertEquals(b, ReturnTrip.outbound(listOf(w2, c, w, b, a)))
		assertEquals(null, ReturnTrip.outbound(listOf(w, c)))
	}

	@Test
	fun returnSegmentMirrorsTheOutbound() {
		val at = t("16:02")
		val r = ReturnTrip.returnSegment(day, at)!!
		assertEquals(at, r.start)
		assertEquals(t("16:47"), r.end)
		assertEquals(42.0, r.kmEstimated)
		assertEquals(43.0, r.kmDeclared)
		assertEquals(listOf(ReturnSample(at, client), ReturnSample(t("16:47"), home)), r.samples)
	}

	@Test
	fun noSamplesWithoutOutboundSamples() {
		val r = ReturnTrip.returnSegment(listOf(seg(TRAVEL, "07:00", "07:30"), seg(WORK, "07:30", null)), t("12:00"))!!
		assertEquals(emptyList(), r.samples)
		assertEquals(null, r.kmEstimated)
	}

	@Test
	fun stopWithReturnClosesAndAdds() {
		val state = ActivityState(ActivityKind.ACTIVITY, ActivityStatus.RUNNING, WORK)
		val now = t("16:00")
		val (stop, ret) = ReturnTrip.stopWithReturn(state, day, t("16:15"), now)
		assertEquals(ActivityTransition(ActivityState(ActivityKind.ACTIVITY, ActivityStatus.CLOSED, closedAt = now), closeAt = t("16:15")), stop)
		assertEquals(t("17:00"), ret.end)
		assertFailsWith<IllegalArgumentException> { ReturnTrip.stopWithReturn(state, listOf(seg(WORK, "08:00", null)), now, now) }
	}

	@Test
	fun travelBackSwitchesOrResumes() {
		val at = t("16:00")
		assertEquals(OpenSegment(TRAVEL, at), ReturnTrip.travelBack(ActivityState(ActivityKind.ACTIVITY, ActivityStatus.RUNNING, PAUSE), at, at).open)
		assertEquals(at, ReturnTrip.travelBack(ActivityState(ActivityKind.ACTIVITY, ActivityStatus.RUNNING, WORK), at, at).closeAt)
		val resumed = ReturnTrip.travelBack(ActivityState(ActivityKind.ACTIVITY, ActivityStatus.RUNNING, null), at, at)
		assertEquals(null, resumed.closeAt)
		assertEquals(OpenSegment(TRAVEL, at), resumed.open)
	}
}
