/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import java.security.cert.TrustAnchor
import java.util.Date

/*
 * What to do with the policy.json found at start (issue #48). Pure
 * decision; the app does the backup, policy_old.json, storage and
 * notification. Owner answers: the first signed policy pins its signer's
 * CN, later ones must carry the same; after a signed policy an unsigned
 * one is refused; a missing file leaves the current policy in force.
 */
object PolicyGate {
	/* What the app remembers between starts. */
	data class State(
		val lastFingerprint: String?,		/* of the last file applied or refused */
		val pinnedCn: String?,			/* set by the first signed policy applied */
	)

	sealed interface Outcome {
		/* no file, or the same file already handled */
		data object Unchanged : Outcome
		data class Apply(val policy: Policy, val fingerprint: String, val signerCn: String?) : Outcome
		data class Refuse(val errors: List<PolicyError>, val fingerprint: String) : Outcome
	}

	/* The file's identity: the policy and its signature, so signing an applied file counts as new. */
	fun fingerprint(policy: ByteArray, sig: ByteArray?): String =
		PolicyParser.fingerprint(if (sig == null) policy else policy + byteArrayOf(0) + sig)

	fun decide(policy: ByteArray?, sig: ByteArray?, state: State, anchors: () -> Set<TrustAnchor>, at: Date = Date()): Outcome {
		if (policy == null)
			return Outcome.Unchanged
		val fp = fingerprint(policy, sig)
		if (fp == state.lastFingerprint)
			return Outcome.Unchanged
		fun refuse(message: String) = Outcome.Refuse(listOf(PolicyError("", null, message)), fp)

		val parsed = when (val r = PolicyParser.parse(policy.toString(Charsets.UTF_8))) {
			is PolicyResult.Invalid -> return Outcome.Refuse(r.errors, fp)
			is PolicyResult.Valid -> r.policy
		}
		if (sig == null)
			return if (state.pinnedCn != null) refuse("policy.json must be signed by \"${state.pinnedCn}\" like the policy in force") else Outcome.Apply(parsed, fp, null)
		val cn = try {
			PolicySignature.verify(policy, sig, anchors(), at)
		} catch (e: PolicySignature.Invalid) {
			return refuse(e.message.orEmpty())
		}
		if (state.pinnedCn != null && cn != state.pinnedCn)
			return refuse("signed by \"$cn\", but policies on this phone must be signed by \"${state.pinnedCn}\"")
		return Outcome.Apply(parsed, fp, cn)
	}
}
