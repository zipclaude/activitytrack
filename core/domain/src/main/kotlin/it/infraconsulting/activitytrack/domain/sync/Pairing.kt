/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.sync

import it.infraconsulting.activitytrack.domain.update.UpdateFingerprint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder

/*
 * Pairing with ActivityTrack Web (issue #113, server SYNC.md §9). The QR
 * code holds activitytrack://pair?v=1&u=<server>&c=<one-time code>[&f=<pin>][&k=<key>];
 * f is the base64 SHA-256 of the server's public key, present when its
 * certificate is not trusted by the phone (self-signed). k is the SHA-256
 * (64 hex digits) of the certificate the server signs with (its policy and
 * the update manifest it relays, issue #1): the phone trusts that signer
 * from the pairing on; a link without k leaves the server's manifests untrusted.
 */
data class PairLink(val server: String, val code: String, val pin: String?, val signerKey: String? = null) {
	companion object {
		/* [allowHttp]: debug builds only, for a development server */
		fun parse(text: String, allowHttp: Boolean = false): PairLink {
			val uri = runCatching { URI(text.trim()) }.getOrNull() ?: throw PairException(PairError.NOT_A_LINK)
			if (uri.scheme != "activitytrack" || uri.host != "pair") throw PairException(PairError.NOT_A_LINK)
			val q = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
				val k = it.substringBefore('=')
				k to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
			}
			if (q["v"] != "1") throw PairException(PairError.VERSION)
			val server = q["u"]?.trimEnd('/') ?: throw PairException(PairError.NOT_A_LINK)
			val code = q["c"]?.takeIf { it.isNotBlank() } ?: throw PairException(PairError.NOT_A_LINK)
			val s = runCatching { URI(server) }.getOrNull()
			val ok = s != null && s.host != null && (s.scheme == "https" || (allowHttp && s.scheme == "http"))
			if (!ok) throw PairException(PairError.NOT_HTTPS)
			val pin = q["f"]?.takeIf { it.isNotBlank() }
			if (pin != null && runCatching { java.util.Base64.getDecoder().decode(pin) }.getOrNull()?.size != 32)
				throw PairException(PairError.BAD_PIN)
			val signerKey = q["k"]?.takeIf { it.isNotBlank() }?.let(UpdateFingerprint::normalize)
			if (signerKey != null && signerKey.length != 64) throw PairException(PairError.BAD_PIN)
			return PairLink(server, code, pin, signerKey)
		}
	}
}

enum class PairError { NOT_A_LINK, VERSION, NOT_HTTPS, BAD_PIN, INVALID_CODE, USER_DISABLED, NETWORK, CERTIFICATE, SERVER, APP_TOO_OLD, SERVER_TOO_OLD }

class PairException(val error: PairError, detail: String? = null) : Exception(detail ?: error.name)

@Serializable
data class PairRequest(
	val code: String,
	val deviceName: String,
	val model: String,
	val appVersion: String,
)

@Serializable
data class PairUser(val username: String, val name: String = "", val profile: String = "")

@Serializable
data class PairOrg(val name: String)

@Serializable
data class PairReply(
	val deviceId: String,
	val token: String,
	val user: PairUser,
	val org: PairOrg,
	val serverTime: Long,
	/* this phone sends to the management systems (issue #118) */
	val pluginHolder: Boolean = true,
)

@Serializable
data class ApiError(val error: String, val detail: String = "")

/* Who this phone is paired with (issue #113). */
@Serializable
data class SyncAccountInfo(
	val server: String,
	val deviceId: String,
	val username: String,
	val userName: String,
	val profile: String,
	val org: String,
	/* the server's key is pinned (self-signed certificate) */
	val pinned: Boolean,
	val pairedUtcMs: Long,
	/* this phone sends to the management systems; the others of the user only import (issue #118) */
	val pluginHolder: Boolean = true,
	/* SHA-256 (hex) of the certificate the server signs its policy and manifests with, from the pairing link (issue #1) */
	val signerKey: String? = null,
)

object PairApi {
	val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

	fun request(r: PairRequest): String = json.encodeToString(PairRequest.serializer(), r)

	/* the server's answer: the reply, or the error it names */
	fun reply(status: Int, body: String): PairReply {
		if (status == 200) return runCatching { json.decodeFromString(PairReply.serializer(), body) }.getOrNull()
			?: throw PairException(PairError.SERVER, "bad answer")
		val error = runCatching { json.decodeFromString(ApiError.serializer(), body).error }.getOrNull()
		throw when (error) {
			"invalid_code" -> PairException(PairError.INVALID_CODE)
			"user_disabled" -> PairException(PairError.USER_DISABLED)
			WireVersion.APP_TOO_OLD -> PairException(PairError.APP_TOO_OLD)
			WireVersion.SERVER_TOO_OLD -> PairException(PairError.SERVER_TOO_OLD)
			else -> PairException(PairError.SERVER, "HTTP $status ${error.orEmpty()}")
		}
	}
}
