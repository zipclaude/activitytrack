/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.domain.settings

import it.infraconsulting.activitytrack.domain.policy.Feature
import it.infraconsulting.activitytrack.domain.policy.PolicyParser
import it.infraconsulting.activitytrack.domain.policy.PolicyResult
import it.infraconsulting.activitytrack.domain.policy.UiPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* The interface language (issue #196): policy, else the user's choice, else the system's, else English. */
class UiLanguageTest {
	private val none = UiPolicy()

	@Test
	fun policyWinsOverTheUserWhichWinsOverTheSystem() {
		assertEquals("de", UiLanguage.effective(UiPolicy("de"), "es"))
		assertEquals("es", UiLanguage.effective(none, "es"))
		assertNull(UiLanguage.effective(none, ""))
		assertNull(UiLanguage.forced(none))
	}

	/* a policy with a wrong language forces English: over the user's choice and over the system language */
	@Test
	fun aWrongPolicyLanguageForcesEnglish() {
		val wrong = UiPolicy(languageInvalid = true)
		assertEquals("en", UiLanguage.forced(wrong))
		assertEquals("en", UiLanguage.effective(wrong, "es"))
		assertEquals("en", UiLanguage.effective(wrong, ""))
		assertEquals("en", UiLanguage.shown(wrong, "de", listOf("it")))
	}

	/* first start with the setting: the system's per-app language becomes the user's choice, unless it is the policy's doing */
	@Test
	fun aPolicyLanguageIsNotAdoptedAsTheUsersChoice() {
		assertEquals("de", UiLanguage.adopted("", "de", none))
		assertEquals("", UiLanguage.adopted("", "", none))
		assertEquals("", UiLanguage.adopted("", "fr", none))
		/* the per-app language was written by the policy (valid or wrong value) */
		assertEquals("", UiLanguage.adopted("", "de", UiPolicy("de")))
		assertEquals("", UiLanguage.adopted("", "en", UiPolicy(languageInvalid = true)))
		/* a choice already stored stays, whatever the system and the policy say */
		assertEquals("es", UiLanguage.adopted("es", "de", none))
		assertEquals("es", UiLanguage.adopted("es", "de", UiPolicy("de")))
	}

	/* a later start: a per-app language changed in the system settings while the app was not running is the choice */
	@Test
	fun aSystemChoiceMadeWhileTheAppWasNotRunningIsKept() {
		assertEquals("en", UiLanguage.atStart("it", "en", none))
		assertEquals("en", UiLanguage.atStart("", "en", none))
		/* put back to the system's language */
		assertEquals("", UiLanguage.atStart("de", "", none))
		assertEquals("de", UiLanguage.atStart("de", "de", none))
		/* a language the app does not have is not a choice */
		assertEquals("de", UiLanguage.atStart("de", "fr", none))
		/* under a policy the per-app language is the policy's: the user's stored choice stays for when it goes */
		assertEquals("it", UiLanguage.atStart("it", "de", UiPolicy("de")))
		assertEquals("it", UiLanguage.atStart("it", "en", UiPolicy(languageInvalid = true)))
		assertEquals("", UiLanguage.atStart("", "de", UiPolicy("de")))
	}

	@Test
	fun anUnsupportedUserValueIsIgnored() {
		assertNull(UiLanguage.effective(none, "xx"))
		assertEquals("", AppSettings(language = "fr").validated().language)
		assertEquals("de", AppSettings(language = "de").validated().language)
	}

	@Test
	fun anUnsupportedSystemLanguageGivesEnglish() {
		assertEquals("en", UiLanguage.shown(none, "", listOf("fr-FR", "ja")))
		assertEquals("en", UiLanguage.shown(none, "", emptyList()))
		/* the first of the system's languages the app has */
		assertEquals("it", UiLanguage.shown(none, "", listOf("fr-FR", "it_IT", "de")))
		assertEquals("de", UiLanguage.shown(none, "", listOf("DE-at")))
		assertEquals("es", UiLanguage.shown(none, "es", listOf("fr")))
		assertEquals("it", UiLanguage.shown(UiPolicy("it"), "es", listOf("fr")))
	}

	@Test
	fun theSupportedLanguagesAreThoseOfThePolicy() {
		assertEquals(PolicyParser.UI_LANGUAGES, UiLanguage.SUPPORTED.toSet())
	}

	/* the policy stays valid with a wrong ui.language: only the language is affected */
	@Test
	fun aWrongLanguageDoesNotInvalidateThePolicy() {
		fun ui(lang: String) = assertIs<PolicyResult.Valid>(
			PolicyParser.parse("""{ "format": "activitytrack-policy", "schema": 1, "id": "acme", "features": { "planning": false }, "ui": { "language": $lang } }"""),
			lang,
		).policy.also { assertEquals("acme", it.id); assertFalse(it.enabled(Feature.PLANNING)) }.ui
		assertEquals(UiPolicy("de"), ui("\"de\""))
		for (wrong in listOf("\"fr\"", "\"\"", "\"DE\"", "5", "true", "[\"it\"]", "{}")) {
			val u = ui(wrong)
			assertNull(u.language, wrong)
			assertTrue(u.languageInvalid, wrong)
		}
		/* absent or null: no language forced */
		assertFalse(assertIs<PolicyResult.Valid>(PolicyParser.parse("""{ "format": "activitytrack-policy", "schema": 1, "ui": {} }""")).policy.ui.languageInvalid)
		assertEquals(none, assertIs<PolicyResult.Valid>(PolicyParser.parse("""{ "format": "activitytrack-policy", "schema": 1 }""")).policy.ui)
		assertEquals(none, ui("null"))
	}

	/* the rest of ui stays checked: an unknown key or a ui that is not an object are still errors */
	@Test
	fun theRestOfUiIsStillChecked() {
		assertIs<PolicyResult.Invalid>(PolicyParser.parse("""{ "format": "activitytrack-policy", "schema": 1, "ui": { "lang": "it" } }"""))
		assertIs<PolicyResult.Invalid>(PolicyParser.parse("""{ "format": "activitytrack-policy", "schema": 1, "ui": "it" }"""))
	}
}
