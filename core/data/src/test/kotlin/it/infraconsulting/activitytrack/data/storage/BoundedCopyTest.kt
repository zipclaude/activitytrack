/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.storage

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class BoundedCopyTest {
	@Test
	fun copiesUpToTheLimit() {
		val data = ByteArray(200_000) { it.toByte() }
		val out = ByteArrayOutputStream()
		assertEquals(200_000L, BoundedCopy.copy(data.inputStream(), out, 200_000))
		assertEquals(data.toList(), out.toByteArray().toList())
		assertEquals(0L, BoundedCopy.copy(ByteArray(0).inputStream(), ByteArrayOutputStream(), 0))
	}

	@Test
	fun stopsPastTheLimitWithoutReadingEverything() {
		var read = 0L
		/* an endless stream: the copy must stop on its own */
		val endless = object : InputStream() {
			override fun read(): Int { read++; return 0 }
			override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
		}
		val out = ByteArrayOutputStream()
		assertEquals(-1L, BoundedCopy.copy(endless, out, 1_000_000))
		assert(out.size() <= 1_000_000)
		assert(read < 2_000_000)
		assertEquals(-1L, BoundedCopy.copy(ByteArray(11).inputStream(), ByteArrayOutputStream(), 10))
	}
}
