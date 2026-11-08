/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.update

import it.infraconsulting.activitytrack.domain.policy.PolicyGate
import it.infraconsulting.activitytrack.domain.policy.PolicySignature
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * One management server, one key (owner, 2026-11-08): it signs the phones' policies and the update manifests it relays
 * with the same self-signed certificate, known to the phone from the pairing link's `k`. The same checks the app runs
 * (PolicyGate on the policy, UpdateManifest.Trust on the manifest), with the fixtures of the other update tests.
 */
class ServerIdentityTest {
	private fun res(name: String): ByteArray = javaClass.getResourceAsStream("/update/$name")!!.readBytes()

	private val server = CertificateFactory.getInstance("X.509").generateCertificate(res("server.crt").inputStream()) as X509Certificate
	private val serverKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(res("server.key")))
	private val k = UpdateFingerprint.of(server)
	private val ca = CertificateFactory.getInstance("X.509").generateCertificate(res("ca.crt").inputStream()) as X509Certificate
	private val anchors = setOf(TrustAnchor(ca, null))

	private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

	/* a policy as the server writes it: SHA256withECDSA over the exact text, the certificate as PEM */
	private fun signedPolicy(text: ByteArray): ByteArray {
		val signature = Signature.getInstance("SHA256withECDSA").run { initSign(serverKey); update(text); sign() }
		val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(server.encoded) + "\n-----END CERTIFICATE-----\n"
		return """{"format": "activitytrack-policy-signature", "algorithm": "SHA256withECDSA", "chain": [${kotlinx.serialization.json.JsonPrimitive(pem)}], "signature": "${Base64.getEncoder().encodeToString(signature)}"}""".toByteArray()
	}

	/* a manifest signed by the same key (ES256) */
	private fun signedManifest(): String {
		val header = b64u("""{"alg":"ES256","x5c":["${Base64.getEncoder().encodeToString(server.encoded)}"]}""".toByteArray())
		val payload = b64u("""{"format":"${UpdateManifest.FORMAT}","issued":"2026-11-07T10:00:00Z","releases":[{"version":"0.9.9","packages":[{"component":"android","url":"/api/v1/update/files/x","sha256":"${"ab".repeat(32)}"}]}]}""".toByteArray())
		val der = Signature.getInstance("SHA256withECDSA").run { initSign(serverKey); update("$header.$payload".toByteArray()); sign() }
		/* DER → R||S */
		var i = 2
		fun int(): ByteArray {
			val n = der[i + 1].toInt() and 0xff
			val v = BigInteger(1, der.copyOfRange(i + 2, i + 2 + n)).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
			i += 2 + n
			return ByteArray(32 - v.size) + v
		}
		val raw = int() + int()
		return """{"payload":"$payload","signatures":[{"protected":"$header","signature":"${b64u(raw)}"}]}"""
	}

	private val policy = javaClass.getResourceAsStream("/policy/policy.json")!!.readBytes()

	@Test
	fun policyThenManifestOfTheSameServerCertificate() {
		val sig = signedPolicy(policy)
		assertTrue(PolicySignature.signerIsSelfSigned(sig))
		/* the real path: PolicyGate with the pairing's k pinned (PolicyLoader.fromServer passes it) */
		val apply = assertIs<PolicyGate.Outcome.Apply>(PolicyGate.decide(policy, sig, PolicyGate.State(null, null), { anchors }, pinned = setOf(k)))
		assertEquals("TEST ActivityTrack server", apply.signerCn)
		/* the CN is now pinned (recordApplied → gateState().pinnedCn): the signed policy fixes the update signer's identity */
		val state = PolicyGate.State(apply.fingerprint, apply.signerCn)
		val trust = UpdateManifest.Trust(setOf(k), apply.signerCn, { anchors })
		assertNotNull(UpdateManifest.verify(signedManifest(), trust))
		/* the next policy of the server, same certificate, is accepted again; an unsigned one is refused */
		val again = policy + ' '.code.toByte()
		assertIs<PolicyGate.Outcome.Apply>(PolicyGate.decide(again, signedPolicy(again), state, { anchors }, pinned = setOf(k)))
		assertIs<PolicyGate.Outcome.Refuse>(PolicyGate.decide(again, null, state, { anchors }, pinned = setOf(k)))
	}

	@Test
	fun phonePairedBeforeThisVersionKeepsItsPolicy() {
		/* no k: the server's policy is refused by the gate (the loader then tells the user to pair again, and records nothing) */
		val sig = signedPolicy(policy)
		assertTrue(PolicySignature.signerIsSelfSigned(sig))
		assertIs<PolicyGate.Outcome.Refuse>(PolicyGate.decide(policy, sig, PolicyGate.State("in-force", null), { anchors }))
		/* and its manifests are not trusted either: nothing pinned, no policy identity */
		assertFalse(runCatching { UpdateManifest.verify(signedManifest(), UpdateManifest.Trust(emptySet())) }.isSuccess)
		/* the same file once paired anew: applied (the refusal was not recorded, so it is looked at again) */
		assertIs<PolicyGate.Outcome.Apply>(PolicyGate.decide(policy, sig, PolicyGate.State("in-force", null), { anchors }, pinned = setOf(k)))
	}

	@Test
	fun aCaIssuedSignerIsNotSelfSigned() {
		assertFalse(PolicySignature.signerIsSelfSigned(javaClass.getResourceAsStream("/policy/policy.json.sig")!!.readBytes()))
		assertFalse(PolicySignature.signerIsSelfSigned("not json".toByteArray()))
		assertFalse(PolicySignature.signerIsSelfSigned("{}".toByteArray()))
	}
}
