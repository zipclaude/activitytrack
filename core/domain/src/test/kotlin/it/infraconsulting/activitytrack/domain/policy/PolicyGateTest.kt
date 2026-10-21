/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
 * Fixtures in src/test/resources/policy, made by scripts/sign-policy.sh
 * with TEST keys (not kept): root "TEST Policy CA" → intermediate → RSA
 * signers "ACME\, Inc. Policies" and "Other Signer"; an EC signer
 * straight under the root.
 */
class PolicyGateTest {
	private fun res(name: String): ByteArray = javaClass.getResourceAsStream("/policy/$name")!!.readBytes()

	private val ca = CertificateFactory.getInstance("X.509").generateCertificate(res("ca.pem").inputStream()) as X509Certificate
	private val anchors = setOf(TrustAnchor(ca, null))
	private val fresh = PolicyGate.State(null, null)

	@Test
	fun signatureGivesTheSignerCn() {
		assertEquals("ACME, Inc. Policies", PolicySignature.verify(res("policy.json"), res("policy.json.sig"), anchors))
		assertEquals("EC Signer", PolicySignature.verify(res("policy-ec.json"), res("policy-ec.json.sig"), anchors))
	}

	@Test
	fun changedPolicyUntrustedCaAndExpiryAreRefused() {
		val changed = res("policy.json") + ' '.code.toByte()
		assertTrue(assertFailsWith<PolicySignature.Invalid> { PolicySignature.verify(changed, res("policy.json.sig"), anchors) }.message!!.contains("does not match"))
		assertFailsWith<PolicySignature.Invalid> { PolicySignature.verify(res("policy.json"), res("policy.json.sig"), emptySet()) }
		/* the signer is no CA: trusting it alone is not enough */
		val leaf = CertificateFactory.getInstance("X.509").generateCertificate(res("policy-ec.json.sig").let { String(it) }
			.substringAfter("\"chain\": [").substringAfter('"').substringBefore('"').replace("\\n", "\n").byteInputStream()) as X509Certificate
		assertFailsWith<PolicySignature.Invalid> { PolicySignature.verify(res("policy.json"), res("policy.json.sig"), setOf(TrustAnchor(leaf, null))) }
		/* certificates valid for 100 years from 2026 */
		val later = Date(System.currentTimeMillis() + 200L * 365 * 86_400_000)
		assertFailsWith<PolicySignature.Invalid> { PolicySignature.verify(res("policy.json"), res("policy.json.sig"), anchors, later) }
	}

	@Test
	fun commonNameUnescapes() {
		assertEquals("Acme, Inc.", PolicySignature.commonName(X500Principal("O=Acme, CN=\"Acme, Inc.\"")))
		assertEquals(null, PolicySignature.commonName(X500Principal("O=Acme")))
	}

	@Test
	fun firstSignedPolicyPinsTheCn() {
		val p = res("policy.json")
		val apply = assertIs<PolicyGate.Outcome.Apply>(PolicyGate.decide(p, res("policy.json.sig"), fresh, { anchors }))
		assertEquals("ACME, Inc. Policies", apply.signerCn)
		val pinned = PolicyGate.State(apply.fingerprint, apply.signerCn)
		/* the same file again: nothing to do */
		assertIs<PolicyGate.Outcome.Unchanged>(PolicyGate.decide(p, res("policy.json.sig"), pinned, { anchors }))
		/* another trusted signer, or no signature: refused */
		assertIs<PolicyGate.Outcome.Refuse>(PolicyGate.decide(res("policy-other.json"), res("policy-other.json.sig"), pinned, { anchors }))
		assertIs<PolicyGate.Outcome.Refuse>(PolicyGate.decide(p, null, pinned, { anchors }))
	}

	@Test
	fun unsignedPoliciesWhileNothingIsPinned() {
		val p = res("policy.json")
		val a = assertIs<PolicyGate.Outcome.Apply>(PolicyGate.decide(p, null, fresh, { error("no anchors needed") }))
		assertEquals(null, a.signerCn)
		/* signing the applied file makes it a new one */
		assertIs<PolicyGate.Outcome.Apply>(PolicyGate.decide(p, res("policy.json.sig"), PolicyGate.State(a.fingerprint, null), { anchors }))
		assertIs<PolicyGate.Outcome.Unchanged>(PolicyGate.decide(null, null, fresh, { anchors }))
	}

	@Test
	fun invalidPolicyIsRefusedOnceThenIgnoredUntilChanged() {
		val bad = "{ \"format\": \"activitytrack-policy\" }".toByteArray()
		val r = assertIs<PolicyGate.Outcome.Refuse>(PolicyGate.decide(bad, null, fresh, { anchors }))
		assertEquals("schema", r.errors.single().path)
		assertIs<PolicyGate.Outcome.Unchanged>(PolicyGate.decide(bad, null, PolicyGate.State(r.fingerprint, null), { anchors }))
	}
}
