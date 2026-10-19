/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.antares

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/* An HTTP error of the Public API; [body] holds the validation details of a 400. */
class AntaresApiException(val status: Int, val body: String) : IOException("Antares $status ${body.take(300)}")

/*
 * Antares Public API v3 calls for one company: JSON in PascalCase, bearer
 * token from AntaresAuth (refreshed when needed), OData query parameters
 * with their "$" prefix. Lists are plain JSON arrays.
 */
class AntaresApi(private val auth: AntaresAuth, private val companyId: Long, private val config: AntaresConfig) {
	suspend fun get(path: String, query: Map<String, String> = emptyMap()): String = call("GET", path, query, null)
	suspend fun list(path: String, query: Map<String, String> = emptyMap()): JSONArray = JSONArray(get(path, query))
	suspend fun post(path: String, body: JSONObject): JSONObject = JSONObject(call("POST", path, emptyMap(), body.toString()))
	suspend fun put(path: String, body: JSONObject): JSONObject = JSONObject(call("PUT", path, emptyMap(), body.toString()))

	/* Who the token belongs to: person id (the activities' OwnerId) and username. */
	suspend fun me(): Pair<String, String> {
		val text = get("/me/claims")
		val claims = HashMap<String, String>()
		/* either an array of {type, value} or an object of claims */
		runCatching {
			val a = JSONArray(text)
			for (i in 0 until a.length())
				a.getJSONObject(i).let { claims[it.optString("type", it.optString("Type"))] = it.optString("value", it.optString("Value")) }
		}.onFailure {
			val o = JSONObject(text)
			o.keys().forEach { k -> claims[k] = o.optString(k) }
		}
		val person = claims["antares:person:id"] ?: throw AntaresApiException(200, "no antares:person:id in claims")
		return person to claims["antares:user:username"].orEmpty()
	}

	private suspend fun call(method: String, path: String, query: Map<String, String>, body: String?): String = withContext(Dispatchers.IO) {
		val token = auth.accessToken(companyId, config)
		val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { (k, v) ->
			URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")
		}
		val c = URL(config.apiUrl + path + q).openConnection() as HttpURLConnection
		try {
			c.requestMethod = method
			c.connectTimeout = 20_000
			c.readTimeout = 60_000
			c.setRequestProperty("Authorization", "Bearer $token")
			c.setRequestProperty("Accept", "application/json")
			if (body != null) {
				c.doOutput = true
				c.setRequestProperty("Content-Type", "application/json")
				c.outputStream.use { it.write(body.toByteArray()) }
			}
			val code = c.responseCode
			val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
			if (code >= 400)
				throw AntaresApiException(code, text)
			text
		} finally {
			c.disconnect()
		}
	}
}
