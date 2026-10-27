/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.io.File
import java.net.URLDecoder
import java.time.ZoneId
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The ServiceDesk Plus descriptor (issue #102) and the engine pieces it
 * needs: input_data JSON in the query with its paging, the form-encoded
 * body, the "object" transform, several to-do sources, todoDone.
 */
class ServiceDeskPlusTest {
	private fun <T> run(block: suspend () -> T): T {
		var result: Result<T>? = null
		block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
		return result!!.getOrThrow()
	}

	private val sdp = assertIs<PluginResult.Valid>(
		PluginParser.parse(File("../../plugins/servicedesk-plus.json").readText(), "servicedesk-plus").also { r ->
			(r as? PluginResult.Invalid)?.let { error(it.errors.joinToString("\n")) }
		},
	).plugin

	private class Fake(val replies: MutableList<String>) : Transport {
		val calls = ArrayList<HttpCall>()
		override suspend fun send(call: HttpCall): HttpReply {
			calls += call
			return HttpReply(200, replies.removeAt(0))
		}
	}

	private fun env(vararg options: Pair<String, JsonElement>) = PluginEnv(
		sdp,
		mapOf("serverUrl" to JsonPrimitive("https://sdp.example.com")) + options.toMap(),
		{ if (it == "apiKey") "KEY" else null },
		ZoneId.of("Europe/Rome"),
	)

	private fun client(fake: Fake, e: PluginEnv) = PluginClient(sdp, fake, StaticAuthorizer(sdp.auth, e), { })

	/* the input_data parameter of a call, decoded */
	private fun inputData(call: HttpCall): JsonObject {
		val q = call.url.substringAfter("?").split("&").first { it.startsWith("input_data=") }.removePrefix("input_data=")
		return Json.parseToJsonElement(URLDecoder.decode(q, Charsets.UTF_8)).jsonObject
	}

	private fun request(id: Int, department: String? = "7", status: String = "Open") = """
		{ "id": "$id", "display_id": "#$id", "subject": "Printer $id", "description": "<p>Toner &amp; paper</p>",
		  "status": { "name": "$status" }, "priority": { "name": "High" }, "technician": { "id": "900", "name": "Mario Rossi" },
		  "due_by_time": { "value": "1793001600000", "display_value": "26/10/2026" },
		  "requester": { "id": "50", "name": "Rossi", ${department?.let { "\"department\": { \"id\": \"$it\", \"name\": \"Amministrazione\" }," } ?: ""} "email_id": "r@example.com" } }"""

	@Test
	fun descriptorIsValid() {
		assertEquals("servicedesk-plus", sdp.id)
		assertEquals("input_data", sdp.http.bodyForm)
		assertIs<Paging.JsonParam>(sdp.http.paging)
		assertEquals(2, sdp.imports.getValue(ImportTarget.TODOS).size)
		assertTrue(sdp.export!!.todoDone != null)
	}

	@Test
	fun requestsPageInsideInputData() {
		val page1 = (1..100).joinToString(",") { request(it) }
		val fake = Fake(
			mutableListOf(
				"""{ "requests": [ $page1 ], "list_info": { "has_more_rows": true, "start_index": 1, "row_count": 100 } }""",
				"""{ "requests": [ ${request(101)} ], "list_info": { "has_more_rows": false } }""",
			),
		)
		val e = env()
		val rows = run { PluginImport.records(sdp, ImportTarget.TODOS, client(fake, e), e) }
		assertEquals(101, rows.size)
		/* the view in filter_by, the paging set inside list_info, the key in its header */
		val first = inputData(fake.calls[0]).getValue("list_info").jsonObject
		assertEquals("My_Open_Requests", first.getValue("filter_by").jsonObject.getValue("name").jsonPrimitive.content)
		assertEquals(1, first.getValue("start_index").jsonPrimitive.content.toInt())
		assertEquals(100, first.getValue("row_count").jsonPrimitive.content.toInt())
		assertEquals(101, inputData(fake.calls[1]).getValue("list_info").jsonObject.getValue("start_index").jsonPrimitive.content.toInt())
		assertEquals("KEY", fake.calls[0].headers["authtoken"])
		assertEquals("application/vnd.manageengine.sdp.v3+json", fake.calls[0].headers["Accept"])
		/* tasks are optional: not asked for by default */
		assertEquals(2, fake.calls.size)
		val r = rows.first()
		assertEquals("request-1", r.key)
		assertEquals("Printer 1", r.text("title"))
		assertEquals("Toner & paper", r.text("notes"))
		assertEquals("department-7", r.text("clientCode"))
		assertEquals(false, r.bool("done"))
		assertEquals("2026-10-26T09:00:00+01:00", r.text("due"))
		assertEquals(JsonPrimitive("requests/1"), r.fields["path"])
		assertEquals(JsonPrimitive("900"), r.fields["owner"])
	}

	@Test
	fun clientsFromTheRequesterAndOptionalTasks() {
		val fake = Fake(
			mutableListOf(
				"""{ "requests": [ ${request(1, department = null, status = "Resolved")} ], "list_info": { "has_more_rows": false } }""",
				"""{ "tasks": [ { "id": "77", "title": "Backup check", "status": { "name": "Open" }, "owner": { "id": "900" } } ], "list_info": { "has_more_rows": false } }""",
			),
		)
		val e = env("includeTasks" to JsonPrimitive(true))
		val rows = run { PluginImport.records(sdp, ImportTarget.TODOS, client(fake, e), e) }
		/* no department: the requester; a resolved request comes done */
		assertEquals("requester-50", rows[0].text("clientCode"))
		assertEquals(true, rows[0].bool("done"))
		assertEquals("task-77", rows[1].key)
		assertEquals(JsonPrimitive("tasks/77"), rows[1].fields["path"])
		assertTrue(fake.calls[1].url.startsWith("https://sdp.example.com/api/v3/tasks?"))
	}

	@Test
	fun worklogBodyIsNestedAndSentAsAFormField() {
		val e = env().with { r ->
			when (r.root) {
				"entry" -> PluginEnv.dig(JsonObject(mapOf("activity" to JsonPrimitive("Toner replaced"), "notes" to JsonPrimitive(""))), r.path)
				"todo" -> PluginEnv.dig(Json.parseToJsonElement("""{ "fields": { "path": "requests/1", "owner": "900" } }"""), r.path)
				"block" -> PluginEnv.dig(Json.parseToJsonElement("""{ "from": 1793001600000, "to": 1793005200000 }"""), r.path)
				"field" -> PluginEnv.dig(Json.parseToJsonElement("""{ "worklogType": "3" }"""), r.path)
				else -> null
			}
		}
		val body = PluginExport.body(sdp.export!!.body, e)
		val w = body.getValue("worklog").jsonObject
		assertEquals("900", w.getValue("owner").jsonObject.getValue("id").jsonPrimitive.content)
		assertEquals(1793001600000, w.getValue("start_time").jsonObject.getValue("value").jsonPrimitive.content.toLong())
		assertEquals("3", w.getValue("worklog_type").jsonObject.getValue("id").jsonPrimitive.content)
		/* empty notes and materials left out of the description */
		assertEquals("Toner replaced", w.getValue("description").jsonPrimitive.content)

		val fake = Fake(mutableListOf("""{ "worklog": { "id": "456" }, "response_status": { "status_code": 2000 } }"""))
		val create = sdp.export.create
		val reply = run { client(fake, e).call(create.method, create.path, e, body) }
		assertEquals("https://sdp.example.com/api/v3/requests/1/worklogs", fake.calls[0].url)
		assertEquals("application/x-www-form-urlencoded", fake.calls[0].headers["Content-Type"])
		val form = fake.calls[0].body!!
		assertTrue(form.startsWith("input_data="))
		assertEquals(body, Json.parseToJsonElement(URLDecoder.decode(form.removePrefix("input_data="), Charsets.UTF_8)))
		/* the remote id holds the whole path, for the later update and delete */
		assertEquals("requests/1/worklogs/456", text(create.id!!.eval(reply, e)))
	}

	@Test
	fun todoDoneResolvesARequest() {
		val e = env().with { r ->
			if (r.root != "todo") null
			else PluginEnv.dig(Json.parseToJsonElement("""{ "title": "Printer 1", "activities": "Toner replaced", "fields": { "path": "requests/1", "kind": "request" } }"""), r.path)
		}
		val done = sdp.export!!.todoDone!!
		val body = PluginExport.body(done.body, e)
		assertEquals(setOf("request"), body.keys)
		val req = body.getValue("request").jsonObject
		assertEquals("Resolved", req.getValue("status").jsonObject.getValue("name").jsonPrimitive.content)
		assertEquals("Toner replaced", req.getValue("resolution").jsonObject.getValue("content").jsonPrimitive.content)
		assertEquals("PUT", done.method)
	}
}
