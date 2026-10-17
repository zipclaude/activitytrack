/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import it.infraconsulting.activitytrack.domain.rounding.RoundingMode
import it.infraconsulting.activitytrack.domain.rounding.RoundingRule
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
	@Test
	fun defaultsAreValid() {
		assertEquals(AppSettings(), AppSettings().validated())
	}

	@Test
	fun remindersNeverBelowTenMinutes() {
		val s = AppSettings(confirmIntervalMin = 1, pauseIntervalMin = 0).validated()
		assertEquals(10, s.confirmIntervalMin)
		assertEquals(10, s.pauseIntervalMin)
	}

	@Test
	fun unsupportedQuantumFallsBackToFifteen() {
		val r = ReportRounding.DEFAULT.copy(work = RoundingRule(RoundingMode.CEIL_TOLERANT, Duration.ofMinutes(7)))
		val s = AppSettings(rounding = r).validated()
		assertEquals(Duration.ofMinutes(15), s.rounding.work.quantum)
		assertEquals(ReportRounding.DEFAULT.pause, s.rounding.pause)
	}

	@Test
	fun endpointsTrimmed() {
		val s = AppSettings(osrm = OnlineService(false, " https://osrm.example.org/ ")).validated()
		assertEquals(OnlineService(false, "https://osrm.example.org"), s.osrm)
	}

	@Test
	fun kmFactorNaN() {
		assertEquals(1.25, AppSettings(kmFactor = Double.NaN).validated().kmFactor)
	}

	@Test
	fun dictationLanguageFallsBackToItalian() {
		assertEquals("en-US", AppSettings(dictationLanguage = "en-US").validated().dictationLanguage)
		assertEquals("it-IT", AppSettings(dictationLanguage = "en-IT").validated().dictationLanguage)
	}
}
