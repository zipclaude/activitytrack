/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.plugin

import it.infraconsulting.activitytrack.domain.plugin.HttpCall
import it.infraconsulting.activitytrack.domain.plugin.HttpReply
import it.infraconsulting.activitytrack.domain.plugin.Transport
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* The plugins' network (issue #48 B2): HttpURLConnection, no extra library. */
class HttpTransport(private val timeoutSec: Int, private val userAgent: String) : Transport {
	override suspend fun send(call: HttpCall): HttpReply = withContext(Dispatchers.IO) {
		val c = URL(call.url).openConnection() as HttpURLConnection
		try {
			c.requestMethod = call.method
			c.connectTimeout = minOf(timeoutSec, 30) * 1000
			c.readTimeout = timeoutSec * 1000
			c.instanceFollowRedirects = false
			c.setRequestProperty("User-Agent", userAgent)
			for ((k, v) in call.headers)
				c.setRequestProperty(k, v)
			if (call.body != null) {
				c.doOutput = true
				c.outputStream.use { it.write(call.body!!.toByteArray(Charsets.UTF_8)) }
			}
			val code = c.responseCode
			val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			HttpReply(code, text, c.headerFields.filterKeys { it != null })
		} finally {
			c.disconnect()
		}
	}
}
