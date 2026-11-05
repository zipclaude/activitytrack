/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.geo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PmtilesHeaderTest {
	private fun header(
		type: Int = 1, comp: Int = 2, minZ: Int = 0, maxZ: Int = 10, dataOffset: Long = 200, dataLength: Long = 1000,
		box: List<Double> = listOf(9.83, 45.17, 12.41, 46.97), magic: String = "PMTiles", version: Int = 3,
	): ByteArray {
		val b = ByteArray(127)
		magic.toByteArray(Charsets.ISO_8859_1).copyInto(b)
		b[7] = version.toByte()
		val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
		buf.putLong(56, dataOffset)
		buf.putLong(64, dataLength)
		b[98] = comp.toByte()
		b[99] = type.toByte()
		b[100] = minZ.toByte()
		b[101] = maxZ.toByte()
		box.forEachIndexed { i, v -> buf.putInt(102 + 4 * i, Math.round(v * 1e7).toInt()) }
		return b
	}

	@Test
	fun readsTheFieldsOfAHeader() {
		val h = PmtilesHeader.parse(header())!!
		assertEquals(10, h.maxZoom)
		assertEquals(0, h.minZoom)
		assertEquals(9.83, h.minLon, 1e-6)
		assertEquals(46.97, h.maxLat, 1e-6)
		assertEquals(1000, h.dataLength)
		assertNull(h.problem(fileSize = 1200))
	}

	@Test
	fun whatIsNotAPmtilesV3ArchiveIsNotAHeader() {
		assertNull(PmtilesHeader.parse(ByteArray(10)))
		assertNull(PmtilesHeader.parse(header(magic = "NotTiles")))
		assertNull(PmtilesHeader.parse(header(version = 2)))
		assertNull(PmtilesHeader.parse(ByteArray(127)))
	}

	@Test
	fun rasterAndUnknownCompressionsAreRefused() {
		assertEquals("not a vector tile archive", PmtilesHeader.parse(header(type = 2))!!.problem(1200))
		assertEquals("unsupported tile compression", PmtilesHeader.parse(header(comp = 4))!!.problem(1200))
		assertNull(PmtilesHeader.parse(header(comp = 1))!!.problem(1200))
	}

	@Test
	fun badBoundsAndZoomsAreRefused() {
		assertEquals("bad zoom range", PmtilesHeader.parse(header(minZ = 5, maxZ = 3))!!.problem(1200))
		assertEquals("bad bounds", PmtilesHeader.parse(header(box = listOf(12.0, 45.0, 11.0, 46.0)))!!.problem(1200))
		assertEquals("bad bounds", PmtilesHeader.parse(header(box = listOf(0.0, 0.0, 0.0, 0.0)))!!.problem(1200))
	}

	@Test
	fun aTruncatedFileIsRefused() {
		assertEquals("the file is truncated", PmtilesHeader.parse(header())!!.problem(fileSize = 900))
		assertEquals("the file is truncated", PmtilesHeader.parse(header(dataOffset = 10))!!.problem(fileSize = 5000))
	}
}
