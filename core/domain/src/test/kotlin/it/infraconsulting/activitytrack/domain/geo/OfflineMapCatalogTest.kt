/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfflineMapCatalogTest {
	private val sha = "a".repeat(64)

	private fun map(id: String = "trento", file: String = "trento.pmtiles", bytes: String = "2600000", sha: String = this.sha, bbox: String = "[9.8, 45.1, 12.4, 47.0]", zoom: String = "10") =
		"""{"id":"$id","name":"Trento","file":"$file","bytes":$bytes,"sha256":"$sha","bbox":$bbox,"maxZoom":$zoom,"updated":"2026-11-04"}"""

	private fun doc(vararg maps: String, schema: Int = 1) = """{"schema":$schema,"maps":[${maps.joinToString(",")}]}"""

	@Test
	fun parsesAGoodEntry() {
		val e = OfflineMapCatalog.parse(doc(map())).single()
		assertEquals("trento", e.id)
		assertEquals(2_600_000L, e.bytes)
		assertEquals(10, e.maxZoom)
		assertEquals(9.8, e.minLon)
		assertEquals(47.0, e.maxLat)
	}

	@Test
	fun aFileNameCanOnlyBeAPlainName() {
		for (bad in listOf("../x.pmtiles", "a/b.pmtiles", "x.pmtiles/../y", "/etc/passwd.pmtiles", "x.txt", ".hidden.pmtiles", "x.pmtiles?a=b"))
			assertEquals(emptyList(), OfflineMapCatalog.parse(doc(map(file = bad))), bad)
	}

	@Test
	fun badEntriesAreLeftOutTheGoodOnesStay() {
		val maps = listOf(
			map(id = "Bad Id"),
			map(sha = "xyz"),
			map(bytes = "0"),
			map(bytes = "${OfflineMapCatalog.MAX_BYTES + 1}"),
			map(bbox = "[9, 45, 12]"),
			map(bbox = "[12, 45, 9, 47]"),
			map(bbox = "[9, 95, 12, 97]"),
			map(zoom = "30"),
			map(id = "ok"),
		)
		assertEquals(listOf("ok"), OfflineMapCatalog.parse(doc(*maps.toTypedArray())).map { it.id })
	}

	@Test
	fun aBadDocumentIsRefused() {
		assertFailsWith<OfflineMapCatalog.Invalid> { OfflineMapCatalog.parse("nope") }
		assertFailsWith<OfflineMapCatalog.Invalid> { OfflineMapCatalog.parse("[]") }
		assertFailsWith<OfflineMapCatalog.Invalid> { OfflineMapCatalog.parse(doc(map(), schema = 2)) }
		assertFailsWith<OfflineMapCatalog.Invalid> { OfflineMapCatalog.parse("""{"schema":1}""") }
	}

	@Test
	fun anEmptyUpdatedDateIsNone() {
		assertEquals(null, OfflineMapCatalog.parse(doc(map().replace("\"2026-11-04\"", "\"\""))).single().updated)
	}

	@Test
	fun shaIsLowerCased() {
		assertEquals("a".repeat(64), OfflineMapCatalog.parse(doc(map(sha = "A".repeat(64)))).single().sha256)
	}
}
