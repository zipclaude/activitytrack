/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

import it.infraconsulting.activitytrack.domain.policy.UiPolicy

/*
 * The language of the interface (owner decision, issue #196): the policy's ui.language if valid,
 * English if the policy has one that is not supported (the rest of the policy holds), else the
 * user's choice if valid, else the system's language; a system language the app does not have
 * gives English (the default resources, values/).
 */
object UiLanguage {
	val SUPPORTED = listOf("it", "en", "de", "es")

	/* what the policy forces: its language, English for a wrong one, null when it has none */
	fun forced(ui: UiPolicy): String? = if (ui.languageInvalid) "en" else ui.language?.takeIf { it in SUPPORTED }

	/*
	 * The user's choice to store at the first start with this setting: the [stored] one if any, else the
	 * [system]'s per-app language. Not while a policy forces a language: the per-app language is then the
	 * policy's, not a choice of the user, and would stay after the policy goes.
	 */
	fun adopted(stored: String, system: String, ui: UiPolicy): String = when {
		stored in SUPPORTED -> stored
		forced(ui) != null -> ""
		else -> system.takeIf { it in SUPPORTED }.orEmpty()
	}

	/* the language tag to apply, or null to follow the system; the user's choice counts when it is supported */
	fun effective(ui: UiPolicy, user: String): String? = forced(ui) ?: user.takeIf { it in SUPPORTED }

	/* the language the app shows: [effective], else the first of the system's [preferred] languages it has, else English */
	fun shown(ui: UiPolicy, user: String, preferred: List<String>): String =
		effective(ui, user) ?: preferred.map { it.substringBefore('-').substringBefore('_').lowercase() }.firstOrNull { it in SUPPORTED } ?: "en"
}
