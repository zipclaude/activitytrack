/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.update

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * Fixtures in src/test/resources/update, made by scripts/make-update-fixtures.py with TEST keys: an app key
 * (RSA), a server key (EC), a CA with two EC signers ("ACME, Inc. Updates", "Other Signer"); py-app.json and
 * py-server.json are signed by python3-activitytrack, an independent implementation of the same format.
 */
class UpdateManifestTest {
	private fun res(name: String): ByteArray = javaClass.getResourceAsStream("/update/$name")!!.readBytes()

	private fun cert(stem: String) = CertificateFactory.getInstance("X.509").generateCertificate(res("$stem.crt").inputStream()) as X509Certificate

	private fun key(stem: String): PrivateKey {
		val spec = PKCS8EncodedKeySpec(res("$stem.key"))
		return runCatching { KeyFactory.getInstance("EC").generatePrivate(spec) }.getOrElse { KeyFactory.getInstance("RSA").generatePrivate(spec) }
	}

	private val app = cert("app")
	private val server = cert("server")
	private val ca = cert("ca")
	private val appPin = UpdateFingerprint.of(app)
	private val serverPin = UpdateFingerprint.of(server)
	private val anchors = setOf(TrustAnchor(ca, null))

	private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

	private fun payload(issued: String = "2026-11-07T10:00:00Z") =
		"""{"format":"${UpdateManifest.FORMAT}","v":1,"issued":"$issued","releases":[
			{"version":"0.9.9","channel":"prod","packages":[{"component":"android","url":"https://example.org/a.apk","sha256":"${"ab".repeat(32)}","size":5}]}]}"""

	/* a manifest signed as the release CI and the server do; [alg] and [header] let a test forge it */
	private fun sign(stem: String, payload: String = payload(), alg: String? = null, chain: List<X509Certificate> = listOf(cert(stem)), tamper: Boolean = false): String {
		val k = key(stem)
		val real = if (k.algorithm == "RSA") "RS256" else "ES256"
		val header = """{"alg":"${alg ?: real}","x5c":[${chain.joinToString(",") { "\"" + Base64.getEncoder().encodeToString(it.encoded) + "\"" }}]}"""
		val protected = b64u(header.toByteArray())
		val body = b64u(payload.toByteArray())
		val input = "$protected.$body".toByteArray()
		val signature = if (real == "RS256") Signature.getInstance("SHA256withRSA").run { initSign(k); update(input); sign() }
		else {
			val der = Signature.getInstance("SHA256withECDSA").run { initSign(k); update(input); sign() }
			rawOf(der)
		}
		val shown = if (tamper) b64u(payload().replace("0.9.9", "9.9.9").toByteArray()) else body
		return """{"payload":"$shown","signatures":[{"protected":"$protected","signature":"${b64u(signature)}"}]}"""
	}

	/* the DER SEQUENCE of two INTEGERs as the 64 bytes R||S of a JWS */
	private fun rawOf(der: ByteArray): ByteArray {
		var i = 2
		if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
		fun int(): ByteArray {
			val n = der[i + 1].toInt() and 0xff
			val v = der.copyOfRange(i + 2, i + 2 + n)
			i += 2 + n
			return v.dropWhile { it == 0.toByte() }.toByteArray().let { ByteArray(32 - it.size) + it }
		}
		return int() + int()
	}

	private val appTrust = UpdateManifest.Trust(setOf(appPin))

	@Test
	fun whatPythonSignedIsAccepted() {
		val a = UpdateManifest.verify(String(res("py-app.json")), UpdateManifest.Trust(setOf(appPin)))
		assertEquals(listOf("0.9.9", "1.0.0-beta1", "0.9.8"), a.entries.map { it.version })
		assertEquals(appPin, a.signerFingerprint)
		assertEquals(listOf("android", "desktop-deb"), a.entries[0].packages.map { it.component })
		assertNotNull(UpdateManifest.verify(String(res("py-server.json")), UpdateManifest.Trust(setOf(serverPin))))
	}

	@Test
	fun kotlinSignaturesAreAcceptedToo() {
		assertEquals("0.9.9", UpdateManifest.verify(sign("app"), appTrust).entries.single().version)
		assertEquals("0.9.9", UpdateManifest.verify(sign("server"), UpdateManifest.Trust(setOf(serverPin))).entries.single().version)
	}

	@Test
	fun pastedFingerprintMayHaveSeparators() {
		val pasted = appPin.chunked(2).joinToString(":").uppercase()
		assertNotNull(UpdateManifest.verify(sign("app"), UpdateManifest.Trust(setOf(UpdateFingerprint.normalize(pasted)))))
	}

	@Test
	fun unknownSignerIsRefused() {
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("server"), appTrust) }
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("app"), UpdateManifest.Trust(emptySet())) }
	}

	@Test
	fun tamperedPayloadIsRefused() {
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("app", tamper = true), appTrust) }
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("server", tamper = true), UpdateManifest.Trust(setOf(serverPin))) }
	}

	@Test
	fun trustedCertificateWithSomeoneElsesSignatureIsRefused() {
		/* the header names the app's certificate, the signature is by the server's key */
		val forged = sign("server", chain = listOf(app))
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(forged, appTrust) }
	}

	@Test
	fun otherAlgorithmsAreRefused() {
		for (alg in listOf("none", "HS256", "ES384", "RS512", "PS256"))
			assertFailsWith<UpdateManifest.Invalid>(alg) { UpdateManifest.verify(sign("app", alg = alg), appTrust) }
		/* the right algorithm named for the wrong key type */
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("server", alg = "RS256"), UpdateManifest.Trust(setOf(serverPin))) }
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("app", alg = "ES256"), appTrust) }
	}

	@Test
	fun garbageIsRefused() {
		for (text in listOf("", "not json", "[]", "{}", """{"payload": 1, "signatures": []}""", """{"payload": "", "signatures": [{}]}"""))
			assertFailsWith<UpdateManifest.Invalid>(text) { UpdateManifest.verify(text, appTrust) }
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("app", payload = """{"format":"other","issued":"2026-11-07T10:00:00Z"}"""), appTrust) }
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("app", payload = """{"format":"${UpdateManifest.FORMAT}"}"""), appTrust) }
	}

	@Test
	fun malformedReleasesAreLeftOut() {
		val p = """{"format":"${UpdateManifest.FORMAT}","issued":"2026-11-07T10:00:00Z","releases":[
			{"version":"2.0.0"},"x",
			{"version":"2.0.0","packages":[{"component":"android","url":"http://example.org/a.apk","sha256":"${"ab".repeat(32)}"}]},
			{"version":"2.0.0","packages":[{"component":"android","url":"https://example.org/a.apk","sha256":"short"}]},
			{"version":"1.0.0","packages":[{"component":"android","url":"/files/x","sha256":"${"AB:".repeat(31)}AB"}]}]}"""
		val m = UpdateManifest.verify(sign("app", payload = p), appTrust)
		assertEquals(listOf("1.0.0"), m.entries.map { it.version })
		assertEquals("ab".repeat(32), m.entries.single().packages.single().sha256)
	}

	@Test
	fun oddSizeNeverBreaksTheReading() {
		val sha = "ab".repeat(32)
		fun release(version: String, size: String) =
			"""{"version":"$version","packages":[{"component":"android","url":"https://example.org/a.apk","sha256":"$sha","size":$size}]}"""
		val releases = listOf("1.0.0" to "{}", "1.1.0" to "[1]", "1.2.0" to "\"12\"", "1.3.0" to "null", "1.4.0" to "7")
		val p = """{"format":"${UpdateManifest.FORMAT}","issued":"2026-11-07T10:00:00Z","releases":[${releases.joinToString(",") { release(it.first, it.second) }}]}"""
		val m = UpdateManifest.verify(sign("app", payload = p), appTrust)
		/* an object or an array is no size, not an error: the release stays, with size 0 (a number in text still reads) */
		assertEquals(releases.map { it.first }, m.entries.map { it.version })
		assertEquals(listOf(0L, 0L, 12L, 0L, 7L), m.entries.map { it.packages.single().size })
	}

	@Test
	fun signedPolicyFixesTheIdentity() {
		/* the policy pinned "ACME, Inc. Updates": its CA-vouched signer passes, the app key and the other signer do not */
		val policy = UpdateManifest.Trust(setOf(appPin), policyCn = "ACME, Inc. Updates", anchors = { anchors })
		assertNotNull(UpdateManifest.verify(sign("acme"), policy))
		assertNotNull(UpdateManifest.verify(sign("acme", chain = listOf(cert("acme"), ca)), policy))
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("app"), policy) }
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("other"), policy) }
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("acme"), UpdateManifest.Trust(emptySet(), "ACME, Inc. Updates", { emptySet() })) }
		/* a CA the phone does not trust vouches for nobody */
		val stranger = UpdateManifest.Trust(emptySet(), "ACME, Inc. Updates", { setOf(TrustAnchor(server, null)) })
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("acme"), stranger) }
	}

	@Test
	fun serverSignsForASignedPolicyOfItsOwn() {
		/* a policy signed by the paired server (CN pinned) is followed by its manifest, with no CA needed */
		val policy = UpdateManifest.Trust(setOf(serverPin), policyCn = "TEST ActivityTrack server")
		assertNotNull(UpdateManifest.verify(sign("server"), policy))
		/* but not by the app key, whatever it says: the policy decides */
		assertFailsWith<UpdateManifest.Invalid> { UpdateManifest.verify(sign("app"), policy) }
	}

	@Test
	fun expiredCertificateOfAPinnedKeyStillCounts() {
		/* a pin is the key's identity: the fingerprint, not the calendar */
		assertNotNull(UpdateManifest.verify(sign("app"), appTrust, at = Date(System.currentTimeMillis() + 200L * 365 * 86_400_000)))
	}

	@Test
	fun packageDigest() {
		val digest = java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
		assertTrue(UpdateManifest.sha256Matches(digest, "BA7816BF 8F01CFEA".replace(" ", "") + "414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
		assertTrue(!UpdateManifest.sha256Matches(digest, "ab".repeat(32)))
	}
}
