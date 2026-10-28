/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* One HTTP exchange, as the transport sees it. */
data class HttpCall(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null)

data class HttpReply(val status: Int, val body: String, val headers: Map<String, List<String>> = emptyMap()) {
	fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/* The network, given by the app (HttpURLConnection) or by the tests. Throws IOException when unreachable. */
fun interface Transport {
	suspend fun send(call: HttpCall): HttpReply
}

/*
 * Adds the credentials to a call. [refused] is told about a 401: true when
 * a new attempt makes sense (an OAuth access token was dropped and will be
 * refreshed), false when the user must act (log in, fix the password).
 */
interface Authorizer {
	suspend fun authorize(call: HttpCall): HttpCall

	suspend fun refused(): Boolean = false
}

/* An HTTP error of the remote service; [body] carries its details (validation errors of a 400…). */
class PluginHttpException(val status: Int, val body: String, val url: String) : IOException("HTTP $status ${body.take(300)}")

/* A response that is not the JSON the descriptor expects. */
class PluginDataException(message: String) : IOException(message)

/*
 * The HTTP engine of a plugin (issue #48 B2): URLs from the descriptor's
 * expressions, credentials, paging, retries. HTTPS only. Retries: a lost
 * connection, 429 and 502/503/504 are tried again up to [attempts] times,
 * waiting Retry-After or 1, 2, 4… s; a 401 once more if the authorizer can
 * renew its token.
 */
class PluginClient(
	private val plugin: Plugin,
	private val transport: Transport,
	private val authorizer: Authorizer,
	private val sleep: suspend (Long) -> Unit,
	private val attempts: Int = 3,
) {
	/* One call; the parsed JSON response (JsonNull for an empty body). */
	suspend fun call(req: Request, env: EvalEnv, body: JsonElement? = null): JsonElement =
		parse(exchange(req.method, url(req.path, req.query, env), body, env))

	/* A call by method and path expression (export calls). */
	suspend fun call(method: String, path: Expr, env: EvalEnv, body: JsonElement? = null): JsonElement =
		parse(exchange(method, url(path, emptyMap(), env), body, env))

	/* Every record of a list, following the plugin's paging when [Request.paged]. */
	suspend fun list(req: Request, env: EvalEnv): List<JsonElement> {
		val paging = if (req.paged) plugin.http.paging else Paging.None
		val out = ArrayList<JsonElement>()
		var page = 0
		var cursor: String? = null
		var next: String? = null
		while (true) {
			if (page >= MAX_PAGES)
				throw PluginDataException("more than $MAX_PAGES pages from ${text(req.path.eval(null, env))}")
			val extra = LinkedHashMap<String, String>()
			when (paging) {
				is Paging.OData -> {
					extra["\$top"] = "${paging.pageSize}"
					extra["\$skip"] = "${page * paging.pageSize}"
				}
				is Paging.Page -> {
					extra[paging.pageParam] = "${paging.firstPage + page}"
					extra[paging.sizeParam] = "${paging.pageSize}"
				}
				is Paging.Cursor -> cursor?.let { extra[paging.cursorParam] = it }
				is Paging.JsonParam -> extra[paging.param] = jsonPage(paging, req.query[paging.param]?.eval(null, env), page).toString()
				Paging.Link, Paging.None -> Unit
			}
			/* a JSON paging parameter replaces the query's own */
			val query = if (paging is Paging.JsonParam) req.query - paging.param else req.query
			val target = next ?: url(req.path, query, env, extra)
			val reply = exchange(req.method, target, null, env)
			val json = parse(reply)
			val items = req.items?.eval(json, env) ?: json
			val rows = items as? JsonArray ?: throw PluginDataException("not a list from $target")
			out.addAll(rows)
			when (paging) {
				is Paging.OData -> if (rows.size < paging.pageSize) return out
				is Paging.Page -> if (rows.size < paging.pageSize) return out
				is Paging.Cursor -> {
					cursor = text(paging.next.eval(json, env))?.takeIf { it.isNotEmpty() } ?: return out
				}
				Paging.Link -> next = nextLink(reply.header("Link")) ?: return out
				/* "true" only: the API's samples also show has_more_rows as the string "false" */
				is Paging.JsonParam -> if (rows.size < paging.pageSize || text(paging.more.eval(json, env))?.lowercase() != "true") return out
				Paging.None -> return out
			}
			page++
		}
	}

	/* The base URL joined with [path] (or [path] itself when absolute), with the query encoded. */
	fun url(path: Expr, query: Map<String, Expr>, env: EvalEnv, extra: Map<String, String> = emptyMap()): String {
		val p = text(path.eval(null, env)) ?: throw PluginDataException("empty path")
		val base = if (p.startsWith("https://")) p else {
			val b = text(plugin.http.baseUrl.eval(null, env))?.trimEnd('/') ?: throw PluginDataException("no base URL: set it in the integration's options")
			b + (if (p.startsWith("/")) p else "/$p")
		}
		if (!base.startsWith("https://"))
			throw PluginDataException("only https:// addresses are allowed: $base")
		val params = query.mapNotNull { (k, v) -> queryText(v.eval(null, env))?.let { k to it } } + extra.toList()
		if (params.isEmpty())
			return base
		return base + (if ('?' in base) "&" else "?") + params.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
	}

	private suspend fun exchange(method: String, url: String, body: JsonElement?, env: EvalEnv): HttpReply {
		val headers = LinkedHashMap<String, String>()
		headers["Accept"] = "application/json"
		for ((k, v) in plugin.http.headers)
			text(v.eval(null, env))?.let { headers[k] = it }
		/* a form field holding the JSON (ServiceDesk Plus' input_data), else the JSON itself */
		val form = plugin.http.bodyForm
		if (body != null)
			headers["Content-Type"] = if (form != null) "application/x-www-form-urlencoded" else "application/json"
		val payload = body?.let { if (form != null) enc(form) + "=" + enc(it.toString()) else it.toString() }
		val plain = HttpCall(method, url, headers, payload)
		var renewed = false
		var attempt = 0
		while (true) {
			attempt++
			val reply = try {
				transport.send(authorizer.authorize(plain))
			} catch (e: IOException) {
				if (attempt >= attempts)
					throw e
				sleep(backoff(attempt, null))
				continue
			}
			when {
				reply.status in 200..299 -> return reply
				reply.status == 401 && !renewed && authorizer.refused() -> renewed = true
				reply.status in RETRY && attempt < attempts -> sleep(backoff(attempt, reply.header("Retry-After")))
				else -> throw PluginHttpException(reply.status, reply.body, url)
			}
		}
	}

	private fun parse(reply: HttpReply): JsonElement {
		if (reply.body.isBlank())
			return JsonNull
		return try {
			Json.parseToJsonElement(reply.body)
		} catch (e: SerializationException) {
			throw PluginDataException("not JSON: ${reply.body.take(120)}")
		}
	}

	companion object {
		const val MAX_PAGES = 1000
		private val RETRY = setOf(429, 502, 503, 504)

		/* Retry-After in seconds (capped at a minute), else 1, 2, 4… s. */
		fun backoff(attempt: Int, retryAfter: String?): Long =
			retryAfter?.trim()?.toLongOrNull()?.coerceIn(0, 60)?.times(1000) ?: (1000L shl (attempt - 1).coerceAtMost(5))

		/* rel="next" of an RFC 8288 Link header */
		fun nextLink(header: String?): String? = header?.split(',')?.firstOrNull { Regex(";\\s*rel=\"?next\"?").containsMatchIn(it) }
			?.substringAfter('<')?.substringBefore('>')?.takeIf { it.startsWith("https://") }

		private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
	}
}

/*
 * Credentials that need no login: none, Basic, API key, bearer token. The
 * values come from the options (secrets included) through [env].
 */
class StaticAuthorizer(private val auth: Auth, private val env: EvalEnv) : Authorizer {
	override suspend fun authorize(call: HttpCall): HttpCall = when (auth) {
		Auth.None, is Auth.OAuth2Pkce -> call
		is Auth.Basic -> {
			val token = Base64.getEncoder().encodeToString("${value(auth.user)}:${value(auth.password)}".toByteArray(Charsets.UTF_8))
			call.copy(headers = call.headers + ("Authorization" to "Basic $token"))
		}
		is Auth.Bearer -> call.copy(headers = call.headers + ("Authorization" to "Bearer ${value(auth.token)}"))
		is Auth.ApiKey -> when {
			auth.header != null -> call.copy(headers = call.headers + (auth.header to value(auth.key)))
			else -> call.copy(url = call.url + (if ('?' in call.url) "&" else "?") + URLEncoder.encode(auth.query!!, Charsets.UTF_8) + "=" +
				URLEncoder.encode(value(auth.key), Charsets.UTF_8).replace("+", "%20"))
		}
	}

	private fun value(e: Expr): String = text(e.eval(null, env)) ?: throw PluginDataException("a credential is missing: set it in the integration's options")
}

/* JsonPrimitive helper for the tests and callers that build env values. */
fun json(value: Any?): JsonElement = when (value) {
	null -> JsonNull
	is JsonElement -> value
	is Boolean -> JsonPrimitive(value)
	is Number -> JsonPrimitive(value)
	else -> JsonPrimitive(value.toString())
}

/* a query value: text, or the JSON of an object or a list (ServiceDesk Plus' input_data) */
private fun queryText(v: JsonElement): String? = if (v is JsonObject || v is JsonArray) v.toString() else text(v)

/* the JSON paging parameter of [page]: the query's own object with start and size set */
private fun jsonPage(p: Paging.JsonParam, base: JsonElement?, page: Int): JsonObject {
	val root = base as? JsonObject ?: JsonObject(emptyMap())
	val inner = (p.inside?.let { root[it] as? JsonObject } ?: if (p.inside == null) root else null) ?: JsonObject(emptyMap())
	val paged = JsonObject(inner + mapOf(p.start to JsonPrimitive(p.firstStart + page * p.pageSize), p.size to JsonPrimitive(p.pageSize)))
	return if (p.inside == null) paged else JsonObject(root + (p.inside to paged))
}
