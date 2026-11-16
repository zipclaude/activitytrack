/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.domain.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/*
 * The Android string resources are complete and aligned in every supported language (issue #196):
 * the default values/strings.xml is English, values-<language>/strings.xml translates it. The
 * languages are the ones of res/xml/locales_config.xml (the same four as the library and the
 * desktop, command line and text interfaces: it, en, de, es). A missing, stale (no longer in the
 * default), unused or untranslated string, a plural without its required quantities, or a changed
 * placeholder fails the build; lint's MissingTranslation only runs with the release build.
 */
class StringResourcesTest {
	private val res = File("../../app/src/main/res")

	/* the default language is English; the others, in the order of the app's language list */
	private val languages = listOf("it", "de", "es")

	private class Plural(val items: Map<String, String>)

	private class Resources(val strings: Map<String, String>, val plurals: Map<String, Plural>, val arrays: Map<String, List<String>>, val untranslatable: Set<String>) {
		val keys: Set<String> get() = strings.keys + plurals.keys + arrays.keys
	}

	private fun load(dir: String): Resources {
		val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml")).documentElement
		val strings = LinkedHashMap<String, String>()
		val plurals = LinkedHashMap<String, Plural>()
		val arrays = LinkedHashMap<String, List<String>>()
		val untranslatable = HashSet<String>()
		val seen = HashSet<String>()
		val nodes = root.childNodes
		for (i in 0 until nodes.length) {
			val e = nodes.item(i) as? Element ?: continue
			val name = e.getAttribute("name")
			assertTrue(seen.add(name), "$dir: $name is defined twice")
			if (e.getAttribute("translatable") == "false")
				untranslatable += name
			when (e.tagName) {
				"string" -> strings[name] = e.textContent
				"plurals" -> {
					val items = e.getElementsByTagName("item")
					plurals[name] = Plural((0 until items.length).associate { (items.item(it) as Element).getAttribute("quantity") to items.item(it).textContent })
				}
				"string-array" -> {
					val items = e.getElementsByTagName("item")
					arrays[name] = (0 until items.length).map { items.item(it).textContent }
				}
				else -> error("$dir: unexpected <${e.tagName}> $name")
			}
		}
		return Resources(strings, plurals, arrays, untranslatable)
	}

	private val base = load("values")
	private val translated get() = base.keys - base.untranslatable

	private fun placeholders(s: String) = Regex("%(?:\\d+\\$)?[sdfx]").findAll(s).map { it.value.replace(Regex("^%(\\d+\\$)?"), "%") }.sorted().toList()

	@Test
	fun theLanguagesAreTheDeclaredOnes() {
		val folders = res.list().orEmpty().filter { it.startsWith("values-") && File(res, "$it/strings.xml").exists() }.map { it.removePrefix("values-") }.sorted()
		assertEquals(languages.sorted(), folders)
		val config = Regex("android:name=\"([^\"]+)\"").findAll(File(res, "xml/locales_config.xml").readText()).map { it.groupValues[1] }.toList()
		assertEquals((languages + "en").sorted(), config.sorted(), "res/xml/locales_config.xml")
	}

	/* the app's languages are the policy's (ui.language) and the settings' */
	@Test
	fun theLanguagesAreThoseOfThePolicyAndTheSettings() {
		assertEquals((languages + "en").sorted(), it.infraconsulting.activitytrack.domain.settings.UiLanguage.SUPPORTED.sorted())
		assertEquals((languages + "en").sorted(), it.infraconsulting.activitytrack.domain.policy.PolicyParser.UI_LANGUAGES.sorted())
	}

	@Test
	fun noStringIsMissingOrStale() {
		for (l in languages) {
			val t = load("values-$l")
			assertEquals(emptyList(), (translated - t.keys).sorted(), "values-$l: missing")
			assertEquals(emptyList(), (t.keys - base.keys).sorted(), "values-$l: not in the default (stale)")
			assertEquals(emptyList(), (t.keys.intersect(base.untranslatable)).sorted(), "values-$l: translates a translatable=false string")
			assertEquals(emptyList(), t.untranslatable.toList(), "values-$l: translatable=false belongs to the default only")
		}
	}

	@Test
	fun noStringIsBlank() {
		for (dir in listOf("values") + languages.map { "values-$it" }) {
			val r = load(dir)
			assertEquals(emptyList(), r.strings.filterValues { it.isBlank() }.keys.toList(), "$dir: empty strings")
			assertEquals(emptyList(), r.plurals.filterValues { p -> p.items.values.any { it.isBlank() } }.keys.toList(), "$dir: empty plurals")
		}
	}

	/* every plural has "one" and "other" in every language (English, Italian, German and Spanish all distinguish them); "many" is allowed where CLDR has it */
	@Test
	fun pluralsAreComplete() {
		for (dir in listOf("values") + languages.map { "values-$it" }) {
			val r = load(dir)
			for ((name, p) in r.plurals) {
				assertTrue(p.items.keys.containsAll(listOf("one", "other")), "$dir: plural $name needs one and other, has ${p.items.keys}")
				assertTrue(p.items.keys.all { it in setOf("zero", "one", "two", "few", "many", "other") }, "$dir: plural $name")
			}
		}
	}

	@Test
	fun placeholdersAreKept() {
		for (l in languages) {
			val t = load("values-$l")
			for ((k, v) in base.strings)
				t.strings[k]?.let { assertEquals(placeholders(v), placeholders(it), "values-$l: $k") }
			for ((k, p) in base.plurals)
				for ((q, v) in t.plurals[k]?.items.orEmpty())
					assertEquals(placeholders(p.items.getValue("other")), placeholders(v), "values-$l: plural $k/$q")
			for ((k, a) in base.arrays)
				t.arrays[k]?.let { assertEquals(a.size, it.size, "values-$l: array $k") }
		}
	}

	/* a translation equal to the English text is a copy left untranslated, unless it is a name, a unit or a word the language shares */
	@Test
	fun noTextIsLeftInEnglish() {
		for (l in languages) {
			val t = load("values-$l")
			val same = base.strings.filter { (k, v) -> t.strings[k] == v }.keys.sorted()
			assertEquals(sameAsEnglish.getValue(l).sorted(), same, "values-$l: identical to the English text (copy it to the list only if it is right in that language)")
		}
	}

	/*
	 * The files where a use of a string counts: the sources and resources of the app, not the tests (this
	 * file names the strings in its lists below, and a string only a test names is unused) nor the strings.
	 */
	private fun sources(): List<File> = File("../..").walkTopDown()
		/* no hidden folder either: in CI the Android SDK and the Gradle home are inside the project (.android-sdk, .gradle-home) */
		.onEnter { it.name == ".." || (!it.name.startsWith(".") && it.name !in setOf("build", "node_modules", "test", "androidTest")) }
		.filter { it.isFile && it.extension in setOf("kt", "xml", "kts") && !(it.name == "strings.xml" && it.parentFile.name.startsWith("values")) }
		.toList()

	/*
	 * A use is a reference to the resource: R.string.x, R.plurals.x, R.array.x in the code, @string/x (@plurals/x,
	 * @array/x) in the manifest and the other resources. The bare name does not count: "tags" or "priority" are
	 * words of the code too. Nothing looks a string up by name (no getIdentifier).
	 */
	private fun unused(keys: Collection<String>, text: String): List<String> {
		val used = Regex("(?:\\bR\\.(?:string|plurals|array)\\.|@(?:string|plurals|array)/)(\\w+)").findAll(text).map { it.groupValues[1] }.toSet()
		return keys.filter { it !in used }.sorted()
	}

	/* a string nothing refers to is a leftover of a removed screen: remove it from every language */
	@Test
	fun everyStringIsUsed() {
		assertEquals(emptyList(), unused(base.keys, sources().joinToString("\n") { it.readText() }))
	}

	/* the check itself: no test file is scanned (the lists of this one would make their strings used), the app's code is */
	@Test
	fun theUnusedCheckDoesNotReadTheTests() {
		val files = sources()
		assertEquals(emptyList(), files.filter { it.name == "StringResourcesTest.kt" || "/src/test/" in it.invariantSeparatorsPath })
		assertTrue(files.any { it.name == "HomeList.kt" } && files.any { it.name == "AndroidManifest.xml" })
		assertEquals(emptyList(), files.filter { f -> f.invariantSeparatorsPath.split("/").any { it.startsWith(".") && it != ".." } })
		assertEquals(listOf("no_such_string"), unused(listOf("nav_ask", "no_such_string"), "stringResource(R.string.nav_ask, x)"))
		/* a word of the code is not a use, nor is a longer name that starts with it */
		assertEquals(listOf("tags"), unused(listOf("tags", "tags_free"), "val tags = c.tags; Text(stringResource(R.string.tags_free))"))
		assertEquals(emptyList(), unused(listOf("app_name", "export_done", "kinds"), "android:label=\"@string/app_name\" R.plurals.export_done R.array.kinds"))
		assertTrue(sources().none { "getIdentifier" in it.readText() }, "a string looked up by name: count it as a use")
	}

	/* shared by the language and the English: names, units, and words that are the same */
	private val sameAsEnglish = mapOf(
		"it" to listOf(
			"set_endpoint", "cmd_stop", "action_no", "att_file", "field_km", "ref_ticket", "ref_url", "ref_uuid", "nav_home", "nav_agenda",
			"nav_report", "action_ok", "home_filter_tag", "color_lime", "color_magenta", "order_alpha", "todo_window_24h", "short_file",
			"backup_section", "contact_email", "ch_policy", "trust_policy_name", "sync_server", "sync_rec_other", "tag_edit", "sample_min",
			"sample_sec", "offline_map_item", "sync_app_version", "sync_server_version", "home_sec_incomplete",
		),
		"de" to listOf(
			"field_name", "field_radius", "field_text", "round_minimum", "cmd_pause", "field_km", "ref_ticket", "ref_url", "ref_uuid",
			"nav_agenda", "export_section", "import_section", "action_ok", "home_filter_tag", "display_normal", "color_orange", "color_magenta",
			"color_indigo", "tags", "order_alpha", "material_line", "short_material", "sync_server", "sync_org", "md_tags", "tag_edit",
			"sample_sec", "offline_map_item", "sync_app_version", "sync_server_version",
		),
		"es" to listOf(
			"md_roles", "set_endpoint", "action_no", "notif_summary_text", "segment_manual", "field_km", "ref_ticket", "ref_uuid",
			"day_reported", "nav_agenda", "display_normal", "color_magenta", "order_alpha", "order_manual", "todo_window_24h", "material_line",
			"short_material", "central_suffix", "sample_min", "sample_sec", "offline_map_item", "sync_app_version",
		),
	)
}
