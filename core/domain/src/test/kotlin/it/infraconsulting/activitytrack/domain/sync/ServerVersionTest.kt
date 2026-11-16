/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.domain.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/* Issue #194: the server's version and schemas as the head answer gives them. */
class ServerVersionTest {
	private fun head(s: String) = Json.parseToJsonElement(s).jsonObject

	@Test
	fun readFromTheHeadAnswer() {
		val v = ServerVersion.of(head("""{"rev": 12, "server": "1.0.0-beta-1", "schema": 3, "minSchema": 3, "mapsRev": ""}"""))
		assertEquals(ServerVersion("1.0.0-beta-1", 3, 3), v)
		val schema = { n: Int -> "protocollo $n" }
		assertEquals("1.0.0-beta-1 · protocollo 3", v!!.text(schema, "minimo"))
		assertEquals("1.1.0 · protocollo 4 (minimo 3)", ServerVersion("1.1.0", 4, 3).text(schema, "minimo"))
		assertEquals("protocollo 3", ServerVersion(schema = 3).text(schema, "minimo"))
		/* an older server says nothing of it */
		assertNull(ServerVersion.of(head("""{"rev": 12}""")))
		/* wrong types are ignored, not taken */
		assertEquals(ServerVersion(server = "1.0.0"), ServerVersion.of(head("""{"server": "1.0.0", "schema": "3"}""")))
	}

	@Test
	fun keptAsText() {
		val v = ServerVersion("1.0.0", 3, 2)
		assertEquals(v, ServerVersion.decode(v.encode()))
		assertNull(ServerVersion.decode(null))
		assertNull(ServerVersion.decode("not json"))
	}
}
