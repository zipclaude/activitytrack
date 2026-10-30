/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PairingTest {
	/* as the server's web.views.pair_link writes it (urlencode) */
	private val link = "activitytrack://pair?v=1&u=https%3A%2F%2Fat.example.com%2F&c=zm1IQApO6pUnx_UcBtwnE2Y2EtQ"
	private val pin = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="

	@Test
	fun readsTheServerAndTheCode() {
		val p = PairLink.parse(link)
		assertEquals("https://at.example.com", p.server)
		assertEquals("zm1IQApO6pUnx_UcBtwnE2Y2EtQ", p.code)
		assertNull(p.pin)
		assertEquals(pin, PairLink.parse("$link&f=" + java.net.URLEncoder.encode(pin, Charsets.UTF_8)).pin)
	}

	@Test
	fun refusesWhatIsNotAPairingLink() {
		fun error(text: String, http: Boolean = false) = assertFailsWith<PairException> { PairLink.parse(text, http) }.error
		assertEquals(PairError.NOT_A_LINK, error("https://at.example.com"))
		assertEquals(PairError.NOT_A_LINK, error("activitytrack://pair?v=1&u=https%3A%2F%2Fa.b"))
		assertEquals(PairError.VERSION, error(link.replace("v=1", "v=2")))
		assertEquals(PairError.NOT_HTTPS, error("activitytrack://pair?v=1&u=http%3A%2F%2Fa.b&c=x"))
		assertEquals(PairError.BAD_PIN, error("$link&f=abc"))
		assertEquals("http://10.0.2.2:8000", PairLink.parse("activitytrack://pair?v=1&u=http%3A%2F%2F10.0.2.2%3A8000&c=x", allowHttp = true).server)
	}

	@Test
	fun answers() {
		val ok = PairApi.reply(
			200,
			"""{"deviceId":"d","token":"t","user":{"username":"alice","name":"Alice","profile":"user"},"org":{"name":"Infra"},"serverTime":5,"new":1}""",
		)
		assertEquals("alice", ok.user.username)
		assertEquals(PairError.INVALID_CODE, assertFailsWith<PairException> { PairApi.reply(400, """{"error":"invalid_code","detail":""}""") }.error)
		assertEquals(PairError.USER_DISABLED, assertFailsWith<PairException> { PairApi.reply(403, """{"error":"user_disabled"}""") }.error)
		assertEquals(PairError.SERVER, assertFailsWith<PairException> { PairApi.reply(502, "<html>") }.error)
	}
}
