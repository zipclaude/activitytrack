/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import it.infraconsulting.activitytrack.domain.settings.AppSettings

/*
 * A policy (issue #48): a file the company or the user drops in the data
 * folder to switch features off, rename or hide fields and fix settings,
 * without separate builds. Everything absent keeps the app's default, so
 * the empty policy [NONE] is the app as shipped.
 */
data class Policy(
	val id: String = "",
	val version: String = "",
	val features: Map<Feature, Boolean> = emptyMap(),
	val fields: Map<Field, FieldPolicy> = emptyMap(),
	val settings: Map<String, SettingPolicy> = emptyMap(),
	val behaviours: Behaviours = Behaviours(),
	val integrations: Map<String, IntegrationPolicy> = emptyMap(),
	val entrySource: EntrySource = EntrySource.FREE,
	val masterData: MasterDataSource = MasterDataSource.USER,
	val exportFormats: Set<String>? = null,		/* null = all */
	val ui: UiPolicy = UiPolicy(),
) {
	fun enabled(f: Feature): Boolean = features[f] ?: true

	fun field(f: Field): FieldPolicy = fields[f] ?: FieldPolicy()

	/* At import: every value of the policy written into the settings, fixed or not. */
	fun applyTo(s: AppSettings): AppSettings = write(s) { true }

	/* At every read: the fixed values only, so the user's own choices stay. */
	fun enforce(s: AppSettings): AppSettings = write(s) { it.fixed }

	private fun write(s: AppSettings, which: (SettingPolicy) -> Boolean): AppSettings = settings.entries.fold(s) { acc, (key, p) ->
		val spec = SettingsCatalog.byKey[key]
		if (spec == null || p.value == null || !which(p)) acc else spec.apply(acc, p.value)
	}

	companion object {
		val NONE = Policy()
	}
}

/*
 * One field: [enabled] = editable, [visible] = shown, [required] = must be
 * filled; [label] per language ("it", "en", ...), colours "#RRGGBB" of the
 * importance border while invalid / valid. Null = the app's default.
 */
data class FieldPolicy(
	val enabled: Boolean = true,
	val visible: Boolean = true,
	val required: Boolean? = null,
	val label: Map<String, String> = emptyMap(),
	val invalidColor: Long? = null,
	val validColor: Long? = null,
) {
	/*
	 * The label in [language]; else the policy's English one; else null
	 * (the app's own text). Owner, issue #48 answer 6.
	 */
	fun labelFor(language: String): String? = label[language] ?: label["en"]
}

/*
 * A setting set by the policy, [value] null = the user's current one.
 * [locked] greys it with a padlock, [hidden] removes it from the screen
 * (owner, issue #48 answer 3); either way the user cannot change it.
 * Neither = only the starting value, the user may change it.
 */
data class SettingPolicy(
	val value: Any? = null,
	val locked: Boolean = false,
	val hidden: Boolean = false,
) {
	val fixed get() = locked || hidden
}

/* Choices of the app that others may make differently. Defaults = the app as shipped. */
data class Behaviours(
	val askTravelDone: Boolean = true,		/* "is the travel completed?" at a new start */
	val closeCompleteOnStop: Boolean = true,	/* Stop closes an entry that needs nothing more */
	val minDurationSec: Int = 60,			/* shorter activities and travels are an error */
	val exportMaxDays: Int = 366,
)

/* An integration: [plugin] is the descriptor in plugins/ (part B of issue #48). */
data class IntegrationPolicy(
	val enabled: Boolean = true,
	val plugin: String,
)

data class UiPolicy(
	val language: String? = null,			/* forced UI language, e.g. "it" */
)
