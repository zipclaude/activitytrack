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

	@Test
	fun companyCalendarReplacesTheGenericOne() {
		val s = AppSettings(calendarId = 1, companyCalendars = mapOf(7L to 2L))
		assertEquals(2L, s.calendarFor(7))
		assertEquals(1L, s.calendarFor(8))
		assertEquals(1L, s.calendarFor(null))
	}

	@Test
	fun homeTabAndWindowsValidated() {
		/* the 3-day planned window (issue #59) is accepted, on the Home and the To-dos screen */
		assertEquals(72, AppSettings(homeTodoWindowHours = 72).validated().homeTodoWindowHours)
		assertEquals("CLOSED", AppSettings(homeTab = "CLOSED").validated().homeTab)
		assertEquals("PLANNED", AppSettings(homeTab = "BOH").validated().homeTab)
		/* "Chiuse": today (0) or the last 24 h / 7 d / 30 d; anything else falls back to today */
		assertEquals(168, AppSettings(homeClosedWindow = 168).validated().homeClosedWindow)
		assertEquals(0, AppSettings(homeClosedWindow = 72).validated().homeClosedWindow)
	}

	@Test
	fun widgetTodoWindowValidated() {
		/* the planned-to-dos widget (issue #62): 24 h / 3 d / 7 d / 30 d, default 24 h */
		assertEquals(72, AppSettings(widgetTodoWindowHours = 72).validated().widgetTodoWindowHours)
		assertEquals(24, AppSettings(widgetTodoWindowHours = 48).validated().widgetTodoWindowHours)
	}
}
