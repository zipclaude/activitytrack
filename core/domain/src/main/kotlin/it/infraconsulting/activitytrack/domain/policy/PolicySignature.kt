/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Detached signature of policy.json (issue #48, owner's choice b):
 * policy.json.sig is a small JSON written by scripts/sign-policy.sh,
 *
 *   { "format": "activitytrack-policy-signature",
 *     "algorithm": "SHA256withECDSA" | "SHA256withRSA",
 *     "chain": [ "<PEM signer certificate>", "<PEM intermediate>", ... ],
 *     "signature": "<base64 of the signature over the exact bytes of policy.json>" }
 *
 * The chain must lead to one of the phone's trusted CAs (system or user,
 * owner answer 4). No CRL/OCSP: the app works offline (answer 7).
 */
object PolicySignature {
	const val FORMAT = "activitytrack-policy-signature"
	val ALGORITHMS = setOf("SHA256withECDSA", "SHA256withRSA")

	class Invalid(message: String) : Exception(message)

	/* Checks [sig] over [policy]; returns the signer's CN. Throws [Invalid]. */
	fun verify(policy: ByteArray, sig: ByteArray, anchors: Set<TrustAnchor>, at: Date = Date()): String {
		val o = try {
			Json.parseToJsonElement(sig.toString(Charsets.UTF_8)) as? JsonObject
		} catch (e: SerializationException) {
			null
		} ?: throw Invalid("policy.json.sig is not a signature file")
		fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
		if (text("format") != FORMAT)
			throw Invalid("policy.json.sig: format must be \"$FORMAT\"")
		val algorithm = text("algorithm")?.takeIf { it in ALGORITHMS }
			?: throw Invalid("policy.json.sig: algorithm must be one of ${ALGORITHMS.joinToString()}")
		val signature = text("signature")?.let { runCatching { Base64.getMimeDecoder().decode(it) }.getOrNull() }
			?: throw Invalid("policy.json.sig: no signature")
		val pems = (o["chain"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: "" }.orEmpty()
		if (pems.isEmpty())
			throw Invalid("policy.json.sig: no certificate chain")
		if (anchors.isEmpty())
			throw Invalid("no trusted certification authority on this phone")

		val cf = CertificateFactory.getInstance("X.509")
		val chain = try {
			pems.map { cf.generateCertificate(ByteArrayInputStream(it.toByteArray(Charsets.US_ASCII))) as X509Certificate }
		} catch (e: GeneralSecurityException) {
			throw Invalid("policy.json.sig: unreadable certificate (${e.message})")
		}
		val signer = chain.first()
		/* a root shipped in the chain is dropped: PKIX wants the path up to, not including, the anchor */
		val path = listOf(signer) + chain.drop(1).filter { it.subjectX500Principal != it.issuerX500Principal }
		try {
			val params = PKIXParameters(anchors).apply {
				isRevocationEnabled = false
				date = at
			}
			CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(path), params)
		} catch (e: GeneralSecurityException) {
			throw Invalid("the signer's certificate is not trusted on this phone (${e.message})")
		}
		/* digitalSignature, when the certificate restricts its key usage */
		if (signer.keyUsage?.getOrNull(0) == false)
			throw Invalid("the signer's certificate is not for signatures")
		val ok = try {
			Signature.getInstance(algorithm).run {
				initVerify(signer.publicKey)
				update(policy)
				verify(signature)
			}
		} catch (e: GeneralSecurityException) {
			false
		}
		if (!ok)
			throw Invalid("the signature does not match policy.json (changed after signing?)")
		return commonName(signer.subjectX500Principal) ?: throw Invalid("the signer's certificate has no CN")
	}

	/* The CN of an RFC 2253 name, escapes undone ("CN=Acme\, Inc.,O=Acme" -> "Acme, Inc."). */
	fun commonName(p: X500Principal): String? {
		val name = p.getName(X500Principal.RFC2253)
		var i = 0
		while (i < name.length) {
			val start = i
			val value = StringBuilder()
			var escaped = false
			while (i < name.length) {
				val c = name[i]
				when {
					escaped -> { value.append(c); escaped = false }
					c == '\\' -> escaped = true
					c == ',' || c == '+' -> break
					else -> value.append(c)
				}
				i++
			}
			i++
			val rdn = value.toString()
			if (name.regionMatches(start, "CN=", 0, 3, ignoreCase = true))
				return rdn.substring(3)
		}
		return null
	}
}
