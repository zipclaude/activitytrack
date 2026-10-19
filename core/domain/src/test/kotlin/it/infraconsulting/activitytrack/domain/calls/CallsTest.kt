/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.calls

import it.infraconsulting.activitytrack.domain.time.atMinute
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallsTest {
	private val rome = ZoneId.of("Europe/Rome")
	private val day = LocalDate.of(2026, 10, 20)
	private fun at(h: Int, m: Int, s: Long = 0) = day.atMinute(h * 60 + m, rome).plusSeconds(s)
	private val on = CallSettings(incoming = true, outgoing = true)

	@Test
	fun numbers() {
		assertEquals("+393331234567", PhoneNumbers.normalize("+39 333 123-45 67"))
		assertEquals("+393331234567", PhoneNumbers.normalize("0039 333 1234567"))
		assertTrue(PhoneNumbers.same("+39 333 1234567", "333 123 4567"))
		assertTrue(PhoneNumbers.same("0461 123456", "+39 0461123456"))
		assertEquals(false, PhoneNumbers.same("12345", "112345"))
	}

	@Test
	fun rounding() {
		assertEquals(at(9, 10), CallRules.floor5(at(9, 13, 40), rome))
		assertEquals(at(9, 15), CallRules.ceil5(at(9, 13, 40), rome))
		assertEquals(at(9, 15), CallRules.ceil5(at(9, 15), rome))
	}

	@Test
	fun sameClientIgnoredOtherClientPausedAndProposed() {
		val running = listOf(RunningEntry(7, clientId = 1, working = true))
		assertEquals(CallOutcome(), CallRules.decide(true, at(10, 0), at(10, 5), 1, running, on, true, rome))
		val other = CallRules.decide(true, at(10, 0, 20), at(10, 6, 10), 2, running, on, true, rome)!!
		assertEquals(7L, other.pauseEntryId)
		assertEquals(at(10, 0) to at(10, 10), other.proposedStart to other.proposedEnd)
		assertTrue(other.propose)
	}

	@Test
	fun shortCallNoPauseOutsideHoursOrDisabledNothing() {
		val running = listOf(RunningEntry(7, clientId = 1, working = true))
		assertNull(CallRules.decide(true, at(10, 0), at(10, 0, 40), 2, running, on, true, rome)!!.pauseEntryId)
		assertNull(CallRules.decide(true, at(20, 0), at(20, 5), 2, running, on, false, rome))
		assertNull(CallRules.decide(false, at(10, 0), at(10, 5), 2, running, on.copy(outgoing = false), true, rome))
		assertEquals(false, CallRules.decide(true, at(10, 0), at(10, 5), 2, emptyList(), on.copy(proposeEntry = false), true, rome)!!.propose)
	}
}
