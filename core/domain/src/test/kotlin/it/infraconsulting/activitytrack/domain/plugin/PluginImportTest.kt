/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.io.File
import java.time.ZoneId
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class PluginImportTest {
	private fun <T> run(block: suspend () -> T): T {
		var result: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
		return result!!.getOrThrow()
	}

	private val antares = (PluginParser.parse(File("../../plugins/antares.json").readText()) as PluginResult.Valid).plugin

	/* answers by path, so the order of the calls does not matter */
	private class ByPath(val routes: Map<String, String>) : Transport {
		val urls = ArrayList<String>()
		override suspend fun send(call: HttpCall): HttpReply {
			urls += call.url
			val path = call.url.substringAfter("/public/api").substringBefore('?')
			return HttpReply(200, routes[path] ?: error("unexpected $path"))
		}
	}

	private val auth = object : Authorizer {
		override suspend fun authorize(call: HttpCall) = call
	}

	private fun env() = PluginEnv(antares, mapOf("clientId" to JsonPrimitive("app")), { null }, ZoneId.of("UTC")).also {
		it.identity["personId"] = JsonPrimitive("42")
		it.lookups["serviceStates"] = Json.parseToJsonElement("[1]")
		it.lookups["serviceLifecycles"] = Json.parseToJsonElement("[3]")
		it.lookups["projectStates"] = Json.parseToJsonElement("[5]")
		it.lookups["ticketStates"] = Json.parseToJsonElement("[1, 2]")
	}

	@Test
	fun listsWithChildrenPerActiveParent() {
		val t = ByPath(
			mapOf(
				"/v3/ServicePortfolios" to """[ { "Id": "S1", "Name": "Assistenza", "CustomerId": "C1", "State": 1, "Lifecycle": 3 },
				                               { "Id": "S2", "Name": "Vecchio", "CustomerId": "C1", "State": 9, "Lifecycle": 3 } ]""",
				"/v3/Projects" to """[ { "Id": "P1", "Name": "Migrazione", "CustomerId": "C2", "Status": 5 } ]""",
				"/v3/ServicePortfolios/S1/ActivityTypes" to """[ { "ActivityTypeId": "T1", "ActivityTypeName": "Intervento", "Included": true, "Order": 2 } ]""",
				"/v3/Projects/P1/ActivityTypes" to """[ { "ActivityTypeId": "T1", "ActivityTypeName": "Intervento", "Included": false, "Order": 1 } ]""",
			),
		)
		val items = run { PluginImport.lists(antares, PluginClient(antares, t, auth, { }), env()) }
		val portfolios = items.filter { it.list == "portfolio" }
		assertEquals(listOf("S1" to true, "S2" to false, "P1" to true), portfolios.map { it.id to it.active })
		assertEquals("service", portfolios.first().kind)
		val types = items.filter { it.list == "activityType" }
		/* the same type under two parents, each with its own "included"; none for the inactive S2 */
		assertEquals(listOf("S1", "P1"), types.map { it.parentId })
		assertEquals(listOf(JsonPrimitive(true), JsonPrimitive(false)), types.map { it.extra["included"] })
		assertTrue(t.urls.none { "S2/ActivityTypes" in it })
	}

	@Test
	fun ticketsBecomeRecords() {
		val t = ByPath(
			mapOf(
				"/v3/Tickets" to """[
					{ "Id": "K1", "Code": "T-1", "Name": "", "Description": "Printer", "State": 1, "PortfolioId": "S1", "CustomerId": "C1", "Priority": 2 },
					{ "Id": "K2", "Code": "T-2", "Name": "Closed one", "State": 7, "PortfolioId": "S1", "CustomerId": "C1" },
					{ "Code": "no id" } ]""",
			),
		)
		val records = run { PluginImport.records(antares, ImportTarget.TODOS, PluginClient(antares, t, auth, { }), env()) }
		assertEquals(listOf("K1", "K2"), records.map { it.key })
		val k1 = records[0]
		assertEquals("T-1", k1.text("title"))		/* empty name: the code */
		assertEquals(false, k1.bool("done"))
		assertEquals(false, k1.skipIfNew)
		assertEquals(JsonPrimitive("S1"), k1.fields["portfolio"])
		assertEquals(true, records[1].skipIfNew)
		assertEquals(true, records[1].bool("done"))
	}
}
