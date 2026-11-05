/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.geo

import it.infraconsulting.activitytrack.domain.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class SamplingPlanTest {
	@Test
	fun defaultsAreFiveMinutesWorkingAndOneMinuteTravelling() {
		assertEquals(SamplingPlan(300_000, false), SamplingPlan.of(AppSettings(), travelling = false))
		assertEquals(SamplingPlan(60_000, true), SamplingPlan.of(AppSettings(), travelling = true))
	}

	@Test
	fun theChosenIntervalsApply() {
		val s = AppSettings(workSampleMin = 20, travelSampleSec = 15).validated()
		assertEquals(1_200_000L, SamplingPlan.of(s, false).intervalMs)
		assertEquals(15_000L, SamplingPlan.of(s, true).intervalMs)
	}
}
