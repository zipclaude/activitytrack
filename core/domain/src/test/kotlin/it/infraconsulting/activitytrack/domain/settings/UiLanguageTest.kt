/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

import it.infraconsulting.activitytrack.domain.policy.PolicyParser
import it.infraconsulting.activitytrack.domain.policy.PolicyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/* The interface language (issue #196): policy, else the user's choice, else the system's, else English. */
class UiLanguageTest {
	@Test
	fun policyWinsOverTheUserWhichWinsOverTheSystem() {
		assertEquals("de", UiLanguage.effective("de", "es"))
		assertEquals("es", UiLanguage.effective(null, "es"))
		assertNull(UiLanguage.effective(null, ""))
	}

	@Test
	fun anInvalidValueIsIgnored() {
		assertEquals("es", UiLanguage.effective("fr", "es"))
		assertNull(UiLanguage.effective("fr", "xx"))
		assertEquals("", AppSettings(language = "fr").validated().language)
		assertEquals("de", AppSettings(language = "de").validated().language)
	}

	@Test
	fun anUnsupportedSystemLanguageGivesEnglish() {
		assertEquals("en", UiLanguage.shown(null, "", listOf("fr-FR", "ja")))
		assertEquals("en", UiLanguage.shown(null, "", emptyList()))
		/* the first of the system's languages the app has */
		assertEquals("it", UiLanguage.shown(null, "", listOf("fr-FR", "it_IT", "de")))
		assertEquals("de", UiLanguage.shown(null, "", listOf("DE-at")))
		assertEquals("es", UiLanguage.shown(null, "es", listOf("fr")))
		assertEquals("it", UiLanguage.shown("it", "es", listOf("fr")))
	}

	@Test
	fun theSupportedLanguagesAreThoseOfThePolicy() {
		assertEquals(PolicyParser.UI_LANGUAGES, UiLanguage.SUPPORTED.toSet())
	}

	@Test
	fun aPolicyWithAnInvalidLanguageIsRefused() {
		fun parse(lang: String) = PolicyParser.parse("""{ "format": "activitytrack-policy", "schema": 1, "ui": { "language": $lang } }""")
		assertIs<PolicyResult.Invalid>(parse("\"fr\""))
		assertIs<PolicyResult.Invalid>(parse("5"))
		assertEquals("de", assertIs<PolicyResult.Valid>(parse("\"de\"")).policy.ui.language)
	}
}
