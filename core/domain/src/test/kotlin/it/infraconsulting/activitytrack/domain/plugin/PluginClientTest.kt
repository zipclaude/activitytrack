/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.io.File
import java.io.IOException
import java.time.ZoneId
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class PluginClientTest {
	/* the fakes never suspend, so a coroutine runs to its end at once */
	private fun <T> run(block: suspend () -> T): T {
		var result: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
		return result!!.getOrThrow()
	}

	private val antares = (PluginParser.parse(File("../../plugins/antares.json").readText()) as PluginResult.Valid).plugin

	private class Fake(val replies: MutableList<(HttpCall) -> HttpReply>) : Transport {
		val calls = ArrayList<HttpCall>()
		override suspend fun send(call: HttpCall): HttpReply {
			calls += call
			return replies.removeAt(0)(call)
		}
	}

	private fun ok(body: String) = { _: HttpCall -> HttpReply(200, body) }

	private val waits = ArrayList<Long>()
	private val noAuth = object : Authorizer {
		override suspend fun authorize(call: HttpCall) = call.copy(headers = call.headers + ("Authorization" to "Bearer T"))
	}

	private fun env(p: Plugin = antares) = PluginEnv(p, mapOf("clientId" to JsonPrimitive("app")), { null }, ZoneId.of("Europe/Rome"))

	private fun client(fake: Fake, auth: Authorizer = noAuth, p: Plugin = antares) = PluginClient(p, fake, auth, { waits += it })

	@Test
	fun identityAndLookupsFromTheAntaresDescriptor() {
		val fake = Fake(
			mutableListOf(
				/* the demo's /me/claims, 2026-10-22: PascalCase claims */
				ok("""[ { "Type": "sub", "Value": "603c" }, { "Type": "antares:person:id", "Value": "42" }, { "Type": "antares:user:username", "Value": "zippo" } ]"""),
				ok("""[ { "Value": 1, "EntityState": 1 }, { "Value": 2, "EntityState": null } ]"""),
				ok("[]"), ok("[]"),
				ok("""[ { "Value": 10, "EntityState": 0 } ]"""),
			),
		)
		val e = env()
		run { PluginSession.prepare(antares, client(fake), e) }
		assertEquals("42", e.identity.getValue("personId").jsonPrimitive.content)
		assertEquals(JsonArray(listOf(JsonPrimitive(1))), e.lookups["serviceStates"])
		assertEquals(JsonArray(listOf(JsonPrimitive(10))), e.lookups["ticketStates"])
		/* the default API URL, the OData filter encoded, the credentials added */
		assertEquals("https://demo.goantares.uno/public/api/me/claims", fake.calls[0].url)
		assertEquals("https://demo.goantares.uno/public/api/v3/Enums?%24filter=Type%20eq%20%27ServicePortfolioState%27", fake.calls[1].url)
		assertEquals("Bearer T", fake.calls[0].headers["Authorization"])
	}

	@Test
	fun odataPagingUntilAShortPage() {
		val page = (1..200).joinToString(",", "[", "]") { """{ "Id": "$it" }""" }
		val fake = Fake(mutableListOf(ok(page), ok("""[ { "Id": "201" } ]""")))
		val e = env().also { it.identity["personId"] = JsonPrimitive("42") }
		val rows = run { client(fake).list(antares.imports.getValue(ImportTarget.TODOS).single().request, e) }
		assertEquals(201, rows.size)
		assertTrue(fake.calls[0].url.contains("%24top=200&%24skip=0"), fake.calls[0].url)
		assertTrue(fake.calls[1].url.contains("%24skip=200"))
		assertTrue(fake.calls[0].url.contains("TechnicianId%20eq%2042"))
	}

	@Test
	fun retriesThenGivesUp() {
		val fake = Fake(mutableListOf({ _ -> throw IOException("down") }, { _ -> HttpReply(503, "", mapOf("Retry-After" to listOf("7"))) }, ok("[]")))
		run { client(fake).call(antares.identity!!.request, env()) }
		assertEquals(listOf(1000L, 7000L), waits)
		val bad = Fake(mutableListOf({ _ -> HttpReply(400, """{ "errors": ["FromDate"] }""") }))
		val e = assertFailsWith<PluginHttpException> { run { client(bad).call(antares.identity!!.request, env()) } }
		assertEquals(400, e.status)
		assertTrue(e.body.contains("FromDate"))
	}

	@Test
	fun unauthorizedRenewsTheTokenOnce() {
		var renewals = 0
		val auth = object : Authorizer {
			override suspend fun authorize(call: HttpCall) = call
			override suspend fun refused(): Boolean = true.also { renewals++ }
		}
		val fake = Fake(mutableListOf({ _ -> HttpReply(401, "") }, ok("{}")))
		run { client(fake, auth).call(antares.identity!!.request, env()) }
		assertEquals(1, renewals)
		val twice = Fake(mutableListOf({ _ -> HttpReply(401, "") }, { _ -> HttpReply(401, "") }))
		assertEquals(401, assertFailsWith<PluginHttpException> { run { client(twice, auth).call(antares.identity!!.request, env()) } }.status)
	}

	@Test
	fun httpsOnlyAndStaticCredentials() {
		val p = (PluginParser.parse(
			"""{ "format": "activitytrack-plugin", "schema": 1, "id": "demo", "name": "Demo",
				"options": [ { "key": "url", "type": "url", "default": "https://x.example/api/" }, { "key": "key", "type": "secret" } ],
				"auth": { "type": "apiKey", "header": "X-Api-Key", "key": "@option.key" },
				"http": { "baseUrl": "@option.url", "paging": { "style": "link" } },
				"import": { "clients": { "request": { "path": "customers", "paged": true }, "key": "$.Id", "map": { "name": "$.Name" } } } }""",
		) as PluginResult.Valid).plugin
		val e = PluginEnv(p, emptyMap(), { if (it == "key") "S3CRET" else null }, ZoneId.of("UTC"))
		val fake = Fake(
			mutableListOf(
				{ _ -> HttpReply(200, "[1]", mapOf("link" to listOf("<https://x.example/api/customers?page=2>; rel=\"next\""))) },
				ok("[2]"),
			),
		)
		val rows = run { PluginClient(p, fake, StaticAuthorizer(p.auth, e), { }).list(p.imports.getValue(ImportTarget.CLIENTS).single().request, e) }
		assertEquals(2, rows.size)
		assertEquals("https://x.example/api/customers", fake.calls[0].url)
		assertEquals("https://x.example/api/customers?page=2", fake.calls[1].url)
		assertEquals("S3CRET", fake.calls[1].headers["X-Api-Key"])
		val plain = PluginEnv(p, mapOf("url" to JsonPrimitive("http://x.example")), { "k" }, ZoneId.of("UTC"))
		assertFailsWith<PluginDataException> { PluginClient(p, fake, noAuth, { }).url(p.imports.getValue(ImportTarget.CLIENTS).single().request.path, emptyMap(), plain) }
	}

	@Test
	fun missingRequiredOptions() {
		assertEquals(emptyList(), env().missingOptions().map { it.key })
		/* the default client id counts; one blanked by the user does not */
		assertEquals(emptyList(), PluginEnv(antares, emptyMap(), { null }, ZoneId.of("UTC")).missingOptions().map { it.key })
		assertEquals(listOf("clientId"), PluginEnv(antares, mapOf("clientId" to JsonPrimitive(" ")), { null }, ZoneId.of("UTC")).missingOptions().map { it.key })
	}
}
