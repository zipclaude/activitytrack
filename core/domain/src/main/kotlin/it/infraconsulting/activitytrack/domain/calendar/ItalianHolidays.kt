/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.calendar

import java.time.LocalDate
import java.time.Month

/*
 * Italian national public holidays (L. 260/1949 and later amendments).
 * 4 October (St Francis) is a holiday again from 2026 (L. 151/2025).
 * Local patron saints are not included: they belong to the manual days off.
 */
object ItalianHolidays {
	fun isHoliday(date: LocalDate): Boolean {
		val md = date.month to date.dayOfMonth
		return md in FIXED ||
			(md == (Month.OCTOBER to 4) && date.year >= 2026) ||
			date == easter(date.year).plusDays(1)
	}

	/* Gregorian Easter Sunday (anonymous Gregorian algorithm, Meeus/Jones/Butcher). */
	fun easter(year: Int): LocalDate {
		val a = year % 19
		val b = year / 100
		val c = year % 100
		val d = b / 4
		val e = b % 4
		val f = (b + 8) / 25
		val g = (b - f + 1) / 3
		val h = (19 * a + b - d - g + 15) % 30
		val i = c / 4
		val k = c % 4
		val l = (32 + 2 * e + 2 * i - h - k) % 7
		val m = (a + 11 * h + 22 * l) / 451
		val month = (h + l - 7 * m + 114) / 31
		val day = (h + l - 7 * m + 114) % 31 + 1
		return LocalDate.of(year, month, day)
	}

	private val FIXED = setOf(
		Month.JANUARY to 1,	/* Capodanno */
		Month.JANUARY to 6,	/* Epifania */
		Month.APRIL to 25,	/* Liberazione */
		Month.MAY to 1,		/* Festa del lavoro */
		Month.JUNE to 2,	/* Festa della Repubblica */
		Month.AUGUST to 15,	/* Ferragosto */
		Month.NOVEMBER to 1,	/* Ognissanti */
		Month.DECEMBER to 8,	/* Immacolata */
		Month.DECEMBER to 25,	/* Natale */
		Month.DECEMBER to 26,	/* Santo Stefano */
	)
}
