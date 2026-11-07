/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.update

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UpdateCheckTest {
	private fun package_(component: String, url: String) = UpdateManifest.Package(component, url, "ab".repeat(32), 10)

	private fun entry(version: String, channel: String = "prod", url: String = "https://example.org/a.apk", notes: String = "https://example.org/n") =
		UpdateManifest.Entry(version, channel, "2026-11-05", notes, listOf(package_("desktop-deb", "https://example.org/d.deb"), package_("android", url)))

	private fun manifest(vararg e: UpdateManifest.Entry, issued: String = "2026-11-07T10:00:00Z") =
		UpdateManifest.Verified(Instant.parse(issued), e.toList(), "fp")

	private val m = manifest(entry("0.9.9"), entry("1.0.0-beta.1", "beta"), entry("0.9.8"), entry("1.1.0", "dev"))

	@Test
	fun versionOrder() {
		val ordered = listOf("0.9.2", "1.0.0-beta.2", "1.0.0-beta.10", "1.0.0", "1.0.1", "1.1", "1.10.0")
		assertEquals(ordered, ordered.reversed().sortedWith { a, b -> Versions.compare(a, b) })
		assertEquals(0, Versions.compare("1.1", "1.1.0"))
		assertEquals(0, Versions.compare("v1.1.0", "1.1.0"))
	}

	@Test
	fun onlyTheFollowedChannels() {
		assertEquals("0.9.9", UpdateCheck.newer(m, "0.9.8", setOf("prod"), null, false)?.version)
		val beta = UpdateCheck.newer(m, "0.9.9", setOf("prod", "beta"), null, false)
		assertEquals("1.0.0-beta.1", beta?.version)
		assertEquals(true, beta?.beta)
		assertEquals("1.1.0", UpdateCheck.newer(m, "0.9.9", setOf("prod", "dev"), null, false)?.version)
		assertNull(UpdateCheck.newer(m, "0.9.9", setOf("prod"), null, false))
		assertNull(UpdateCheck.newer(m, "1.1.0", setOf("prod", "beta", "dev"), null, false))
		/* a beta user on 1.0.0-beta.1 sees 1.0.0 when it comes */
		assertEquals("1.0.0", UpdateCheck.newer(manifest(entry("1.0.0")), "1.0.0-beta.1", setOf("prod", "beta"), null, false)?.version)
	}

	@Test
	fun channelsAlwaysHaveProd() {
		assertEquals(setOf("prod"), UpdateCheck.channelsOf(""))
		assertEquals(setOf("beta", "prod"), UpdateCheck.channelsOf("beta"))
		assertEquals(setOf("prod", "dev", "test"), UpdateCheck.channelsOf(" Dev, test ,nonsense"))
	}

	@Test
	fun replayAndDowngradeAreRefused() {
		assertEquals("0.9.9", UpdateCheck.newer(m, "0.9.0", setOf("prod"), Instant.parse("2026-11-07T10:00:00Z"), false)?.version)
		assertFailsWith<UpdateManifest.Invalid> { UpdateCheck.newer(m, "0.9.0", setOf("prod"), Instant.parse("2026-11-08T00:00:00Z"), false) }
		/* a manifest listing only older versions offers nothing */
		assertNull(UpdateCheck.newer(manifest(entry("0.9.7")), "0.9.8", setOf("prod"), null, false))
	}

	@Test
	fun addressesAndNotes() {
		val relative = manifest(entry("2.0.0", url = "/api/v1/update/files/" + "ab".repeat(32), notes = "javascript:x"))
		/* a server path only from the server's own manifest; a notes page that is not https is the releases page */
		assertNull(UpdateCheck.newer(relative, "1.0.0", setOf("prod"), null, relative = false))
		val r = UpdateCheck.newer(relative, "1.0.0", setOf("prod"), null, relative = true)!!
		assertEquals("/api/v1/update/files/" + "ab".repeat(32), r.packageUrl)
		assertEquals(UpdateCheck.RELEASES_PAGE, r.url)
		assertEquals("https://example.org/n", UpdateCheck.newer(m, "0.9.8", setOf("prod"), null, false)?.url)
	}

	@Test
	fun whichRunsCheck() {
		/* "Check now": always (a store's app too), unless the build cannot check (debug) */
		assertEquals(true, UpdateCheck.runs(manual = true, updateCheck = false, applies = false, canCheck = true))
		assertEquals(false, UpdateCheck.runs(manual = true, updateCheck = true, applies = true, canCheck = false))
		/* the daily run: only switched on and not a store's app, whatever the job's tags (an older install has none) */
		assertEquals(true, UpdateCheck.runs(manual = false, updateCheck = true, applies = true, canCheck = true))
		assertEquals(false, UpdateCheck.runs(manual = false, updateCheck = true, applies = false, canCheck = true))
		assertEquals(false, UpdateCheck.runs(manual = false, updateCheck = false, applies = true, canCheck = true))
	}

	@Test
	fun noPackageForThisAppNoOffer() {
		val other = UpdateManifest.Entry("3.0.0", "prod", "", "", listOf(package_("desktop-deb", "https://example.org/d.deb")))
		assertNull(UpdateCheck.newer(manifest(other), "1.0.0", setOf("prod"), null, false))
	}
}
