/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

/*
 * The language of the interface (owner decision, issue #196): the policy's ui.language if valid,
 * else the user's choice if valid, else the system's language; a system language the app does not
 * have gives English (the default resources, values/).
 */
object UiLanguage {
	val SUPPORTED = listOf("it", "en", "de", "es")

	/* the language tag to force, or null to follow the system; anything not supported is ignored */
	fun effective(policy: String?, user: String): String? =
		policy?.takeIf { it in SUPPORTED } ?: user.takeIf { it in SUPPORTED }

	/* the language the app shows: [effective], else the first of the system's [preferred] languages it has, else English */
	fun shown(policy: String?, user: String, preferred: List<String>): String =
		effective(policy, user) ?: preferred.map { it.substringBefore('-').substringBefore('_').lowercase() }.firstOrNull { it in SUPPORTED } ?: "en"
}
