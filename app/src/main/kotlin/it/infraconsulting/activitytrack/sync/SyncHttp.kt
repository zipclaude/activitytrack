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
class SyncHttp(private val server: String, private val pin: String?, private val userAgent: String) {
	class Reply(val status: Int, val body: String)

	suspend fun send(method: String, path: String, body: String? = null, token: String? = null): Reply = withContext(Dispatchers.IO) {
		val c = URL("$server/$path").openConnection() as HttpURLConnection
		try {
			if (c is HttpsURLConnection && pin != null) {
				c.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(pin)), null) }.socketFactory
				c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
			}
			c.requestMethod = method
			c.connectTimeout = 15_000
			c.readTimeout = 60_000
			c.instanceFollowRedirects = false
			c.setRequestProperty("User-Agent", userAgent)
			c.setRequestProperty("Accept", "application/json")
			if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
			if (body != null) {
				c.doOutput = true
				c.setRequestProperty("Content-Type", "application/json")
				c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
			}
			val status = c.responseCode
			val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			Reply(status, text)
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
