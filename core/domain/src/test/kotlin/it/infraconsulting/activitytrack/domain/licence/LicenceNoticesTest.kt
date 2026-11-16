/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.domain.licence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * The package carries the notices its components ask for (issue #198): the application's own
 * licence, the list of the components of other authors and their licence texts, shown by the
 * screen "About and licences". A new library must be named in the list before it ships.
 */
class LicenceNoticesTest {
	private val root = File("../..")
	private val assets = File(root, "app/src/main/assets")
	private val notice = File(assets, "licenses/THIRD-PARTY.txt").readText()

	/* the libraries of the build that never reach the package: tests and code generation */
	private val notShipped = setOf("junit", "org.xerial", "org.json", "androidx.test", "androidx.test.ext")

	/* what the notice must name for the libraries of a publisher */
	private val named = listOf("androidx." to "AndroidX", "org.jetbrains." to "Kotlin", "com.google.zxing" to "ZXing", "org.maplibre." to "MapLibre")

	@Test
	fun theLicenceInThePackageIsTheRepositorys() {
		assertEquals(File(root, "LICENSE").readText(), File(assets, "licenses/GPL-3.0.txt").readText())
	}

	@Test
	fun everyLibraryOfTheCatalogIsInTheNotice() {
		val groups = Regex("group = \"([^\"]+)\"").findAll(File(root, "gradle/libs.versions.toml").readText()).map { it.groupValues[1] }.toSet() - notShipped
		assertTrue(groups.size > 10, "gradle/libs.versions.toml: libraries not found")
		for (g in groups.sorted()) {
			val name = named.firstOrNull { g.startsWith(it.first) }?.second
			assertTrue(name != null, "$g: a new publisher; add its notice to assets/licenses/THIRD-PARTY.txt and its name to this test")
			assertTrue(name in notice, "$g: \"$name\" is not in assets/licenses/THIRD-PARTY.txt")
		}
	}

	@Test
	fun theScreenShowsTextsThatAreInThePackage() {
		val screen = File(root, "app/src/main/kotlin/it/infraconsulting/activitytrack/ui/settings/AboutScreen.kt").readText()
		val paths = Regex("to \"([^\"]+\\.(?:txt|md))\"").findAll(screen).map { it.groupValues[1] }.toList()
		assertTrue(paths.size >= 5, "AboutScreen.kt: the list of the texts was not found")
		for (p in paths)
			assertTrue(File(assets, p).length() > 500, "$p is not in the assets")
		/* every text of the folder is shown, and the notice names the files it points to */
		assertEquals(File(assets, "licenses").list().orEmpty().sorted(), paths.filter { it.startsWith("licenses/") }.map { it.substringAfter('/') }.sorted())
		for (f in listOf("Apache-2.0.txt", "MapLibre-Native-Android.md", "assets/fonts/OFL.txt"))
			assertTrue(f in notice, "THIRD-PARTY.txt does not point to $f")
	}

	/* an icon under another licence says where it comes from; the others are the application's */
	@Test
	fun everyIconSaysItsLicenceAndItsOrigin() {
		val icons = File(root, "app/src/main/res/drawable").listFiles().orEmpty().filter { it.extension == "xml" }
		assertTrue(icons.size > 40)
		for (f in icons) {
			val head = f.readLines().take(5).joinToString("\n")
			when (Regex("SPDX-License-Identifier: (\\S+)").find(head)?.groupValues?.get(1)) {
				"Apache-2.0" -> assertTrue("Material Icons" in head, "${f.name}: an Apache-2.0 icon names Material Icons")
				"GPL-3.0-or-later" -> assertTrue("SPDX-FileCopyrightText" in head && "Material" !in head, "${f.name}: an own icon has the copyright line")
				else -> error("${f.name}: no SPDX-License-Identifier in its first lines")
			}
		}
	}
}
