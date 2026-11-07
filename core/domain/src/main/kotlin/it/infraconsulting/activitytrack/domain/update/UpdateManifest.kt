/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.update

import it.infraconsulting.activitytrack.domain.policy.PolicySignature
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/* SHA-256 of a certificate (DER) as lowercase hex: what a pairing link carries and a policy or an administrator pins. */
object UpdateFingerprint {
	fun of(cert: X509Certificate): String = of(cert.encoded)

	fun of(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

	/* what is pasted ("AB:CD ...") as the comparable form */
	fun normalize(text: String): String = text.lowercase().filter { it in "0123456789abcdef" }
}

/*
 * The signed update manifest (issue #1, python3-activitytrack manifest.py is the other implementation):
 * one JSON file with the signature inline, a JWS in JSON serialization (RFC 7515),
 *
 *   { "payload": <base64url of the manifest>,
 *     "signatures": [ { "protected": <base64url of {"alg","x5c","x5t#S256"}>, "signature": <base64url> } ] }
 *
 * Only ES256 (raw R||S) and RS256: "none", HS* and anything else are refused. The signer's certificate
 * travels in x5c (standard base64 DER, leaf first); the signing input is "protected.payload".
 */
object UpdateManifest {
	const val FORMAT = "activitytrack-update-manifest"
	const val COMPONENT = "android"
	val ALGORITHMS = setOf("ES256", "RS256")
	val CHANNELS = listOf("prod", "beta", "test", "dev")
	const val MAX_BYTES = 1 shl 20

	class Invalid(message: String) : Exception(message)

	/* Who may sign (the rule of issue #1): see [Trust.accepts]. */
	class Trust(
		/* leaf fingerprints accepted when no signed policy is in force: the app's own certificate, the paired server's */
		val pins: Set<String>,
		/* the CN pinned by the signed policy in force: then only this identity signs */
		val policyCn: String? = null,
		val anchors: () -> Set<TrustAnchor> = { emptySet() },
	) {
		fun accepts(chain: List<X509Certificate>, at: Date): Boolean {
			val leaf = chain.first()
			val pinned = UpdateFingerprint.of(leaf) in pins
			if (policyCn == null) return pinned
			/* a signed policy fixes the identity: the same CN, vouched for by a CA or by the server's pinned key */
			if (PolicySignature.commonName(leaf.subjectX500Principal) != policyCn) return false
			if (pinned) return true
			return try {
				PolicySignature.checkChain(chain, anchors(), at)
				true
			} catch (e: PolicySignature.Invalid) {
				false
			}
		}
	}

	data class Package(val component: String, val url: String, val sha256: String, val size: Long)

	data class Entry(val version: String, val channel: String, val published: String, val notesUrl: String, val packages: List<Package>)

	class Verified(val issued: Instant, val entries: List<Entry>, val signerFingerprint: String)

	/* The manifest in [text] when one of its signatures is valid and its signer is trusted by [trust]; else [Invalid]. */
	fun verify(text: String, trust: Trust, at: Date = Date()): Verified {
		if (text.length > MAX_BYTES) throw Invalid("too large")
		val doc = try {
			Json.parseToJsonElement(text) as? JsonObject
		} catch (e: Exception) {
			null
		} ?: throw Invalid("not a signed manifest")
		val payloadB64 = (doc["payload"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Invalid("not a signed manifest")
		val signatures = doc["signatures"] as? JsonArray ?: throw Invalid("not a signed manifest")
		if (trust.pins.isEmpty() && trust.policyCn == null) throw Invalid("no trusted signer")
		for (s in signatures) {
			val signer = signer(s as? JsonObject ?: continue) ?: continue
			if (signer.alg !in ALGORITHMS || !trust.accepts(signer.chain, at)) continue
			if (!check(signer, "${signer.protectedB64}.$payloadB64".toByteArray(Charsets.US_ASCII))) continue
			return parse(String(b64u(payloadB64) ?: throw Invalid("bad payload"), Charsets.UTF_8), UpdateFingerprint.of(signer.chain.first()))
		}
		throw Invalid("no valid signature of a trusted signer")
	}

	private class Signer(val alg: String, val chain: List<X509Certificate>, val protectedB64: String, val signature: ByteArray)

	private fun b64u(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

	private fun signer(s: JsonObject): Signer? {
		val protectedB64 = (s["protected"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
		val signature = (s["signature"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::b64u) ?: return null
		val header = runCatching { Json.parseToJsonElement(String(b64u(protectedB64) ?: return null, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
		val alg = (header["alg"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
		val x5c = (header["x5c"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null } ?: return null
		if (x5c.isEmpty()) return null
		val cf = CertificateFactory.getInstance("X.509")
		val chain = try {
			x5c.map { cf.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(it))) as X509Certificate }
		} catch (e: Exception) {
			return null
		}
		return Signer(alg, chain, protectedB64, signature)
	}

	private fun check(s: Signer, input: ByteArray): Boolean {
		val key = s.chain.first().publicKey
		return try {
			when (s.alg) {
				"ES256" -> {
					if (key !is ECPublicKey || key.params.curve.field.fieldSize != 256 || s.signature.size != 64) return false
					Signature.getInstance("SHA256withECDSA").run {
						initVerify(key)
						update(input)
						verify(derOf(s.signature))
					}
				}
				else -> {
					if (key !is RSAPublicKey) return false
					Signature.getInstance("SHA256withRSA").run {
						initVerify(key)
						update(input)
						verify(s.signature)
					}
				}
			}
		} catch (e: GeneralSecurityException) {
			false
		}
	}

	/* R||S (JWS) as the DER SEQUENCE of two INTEGERs the JCA wants */
	private fun derOf(raw: ByteArray): ByteArray {
		fun int(b: ByteArray): ByteArray {
			val v = BigInteger(1, b).toByteArray()
			return byteArrayOf(0x02, v.size.toByte()) + v
		}
		val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
		return byteArrayOf(0x30, body.size.toByte()) + body
	}

	private fun parse(payload: String, fingerprint: String): Verified {
		val o = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: throw Invalid("bad payload")
		if (str(o["format"]) != FORMAT) throw Invalid("not an update manifest")
		val issued = runCatching { Instant.parse(str(o["issued"])) }.getOrNull() ?: throw Invalid("no issue time")
		val entries = (o["releases"] as? JsonArray).orEmpty().mapNotNull { entry(it) }
		return Verified(issued, entries, fingerprint)
	}

	private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

	/* a malformed release is left out, the others stay */
	private fun entry(e: JsonElement): Entry? {
		val o = e as? JsonObject ?: return null
		val version = str(o["version"])?.takeIf { it.isNotBlank() } ?: return null
		val packages = (o["packages"] as? JsonArray)?.mapNotNull { p ->
			val po = p as? JsonObject ?: return@mapNotNull null
			val url = str(po["url"])?.trim() ?: return@mapNotNull null
			val sha = UpdateFingerprint.normalize(str(po["sha256"]) ?: return@mapNotNull null)
			if (sha.length != 64 || !(url.startsWith("https://") || url.startsWith("/"))) return@mapNotNull null
			Package(str(po["component"]) ?: return@mapNotNull null, url, sha, po["size"]?.jsonPrimitive?.longOrNull ?: 0)
		}.orEmpty()
		if (packages.isEmpty()) return null
		return Entry(version, str(o["channel"]) ?: "prod", str(o["published"]).orEmpty(), str(o["notes_url"]).orEmpty(), packages)
	}

	/* the package's bytes against the manifest's sha256 */
	fun sha256Matches(digest: ByteArray, expected: String): Boolean =
		digest.joinToString("") { "%02x".format(it) } == UpdateFingerprint.normalize(expected)
}
