/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.sync

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The connection to ActivityTrack Web (issue #113): JSON over HTTPS. With a
 * pin (the server's certificate is self-signed, SYNC.md §9) the server is
 * trusted by its public key alone, whatever its certificate or host name;
 * without one, the system's trust store and host name check apply.
 */
class SyncHttp(
	private val server: String,
	private val pin: String?,
	private val userAgent: String,
	/* the app's version and wire schema, on every request (SYNC.md §12) */
	private val always: Map<String, String> = emptyMap(),
) {
	class Reply(val status: Int, val body: String)

	suspend fun send(method: String, path: String, body: String? = null, token: String? = null): Reply {
		val (status, bytes) = exchange(method, path, body?.toByteArray(Charsets.UTF_8), if (body != null) "application/json" else null, token)
		return Reply(status, bytes.toString(Charsets.UTF_8))
	}

	/*
	 * A file of some MB read as a stream, not into memory (the offline maps): [use] gets the body
	 * of a 200 answer; any other status is an [java.io.IOException].
	 */
	suspend fun <T> stream(path: String, token: String?, use: suspend (java.io.InputStream) -> T): T = withContext(Dispatchers.IO) {
		val c = connect("GET", path, token, emptyMap())
		try {
			val status = c.responseCode
			if (status != 200) throw java.io.IOException("HTTP $status")
			c.inputStream.use { use(it) }
		} finally {
			c.disconnect()
		}
	}

	/* The connection to [path], with the key pin, the timeouts, the identity and the version headers. */
	private fun connect(method: String, path: String, token: String?, headers: Map<String, String>): HttpURLConnection {
		val c = URL("$server/$path").openConnection() as HttpURLConnection
		if (c is HttpsURLConnection && pin != null) {
			c.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(pin)), null) }.socketFactory
			c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
		}
		c.requestMethod = method
		c.connectTimeout = 15_000
		c.readTimeout = 60_000
		c.instanceFollowRedirects = false
		c.setRequestProperty("User-Agent", userAgent)
		if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
		for ((k, v) in always + headers) c.setRequestProperty(k, v)
		return c
	}

	/* a file up (PUT with its SHA-256) or down (GET); bytes of the answer */
	suspend fun exchange(
		method: String,
		path: String,
		body: ByteArray?,
		contentType: String?,
		token: String?,
		headers: Map<String, String> = emptyMap(),
	): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
		val c = connect(method, path, token, headers)
		try {
			if (body != null) {
				c.doOutput = true
				c.setFixedLengthStreamingMode(body.size)
				c.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
				c.outputStream.use { it.write(body) }
			}
			val status = c.responseCode
			val bytes = (if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
			status to bytes
		} finally {
			c.disconnect()
		}
	}

	/* accepts exactly the server key whose SHA-256 (base64, of the SubjectPublicKeyInfo) is [pin] */
	private class PinnedTrust(private val pin: String) : X509TrustManager {
		override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
			val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
			val digest = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
			if (Base64.encodeToString(digest, Base64.NO_WRAP) != pin) throw CertificateException("server key does not match the pairing code")
		}

		override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("client")

		override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
	}
}
