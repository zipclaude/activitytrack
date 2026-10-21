/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.PolicyRecordEntity
import it.infraconsulting.activitytrack.domain.policy.Policy
import it.infraconsulting.activitytrack.domain.policy.PolicyError
import it.infraconsulting.activitytrack.domain.policy.PolicyGate
import it.infraconsulting.activitytrack.domain.policy.PolicyParser
import it.infraconsulting.activitytrack.domain.policy.PolicyResult
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/*
 * The policy in force (issue #48) and the history of the policy.json files
 * handled. [current] is the app as shipped until the stored one is read.
 */
class PolicyRepository(private val db: ActivityTrackDatabase, private val clock: Clock, scope: CoroutineScope) {
	private val dao get() = db.policyDao()

	val current: StateFlow<Policy> = dao.observeInForce().map { r -> r?.text?.let(::parsed) ?: Policy.NONE }
		.stateIn(scope, SharingStarted.Eagerly, Policy.NONE)

	val inForce: Flow<PolicyRecordEntity?> = dao.observeInForce()

	fun history(limit: Int = 20): Flow<List<PolicyRecordEntity>> = dao.observeHistory(limit)

	suspend fun gateState(): PolicyGate.State = PolicyGate.State(dao.latest()?.fingerprint, dao.pinnedCn())

	/*
	 * The policy in force read now from the database: for receivers,
	 * widgets and tiles woken in a fresh process, before [current] loads.
	 */
	suspend fun now(): Policy = dao.inForce()?.text?.let(::parsed) ?: Policy.NONE

	/* The text of the policy in force, null = none ever applied. */
	suspend fun inForceText(): String? = dao.inForce()?.text

	suspend fun recordApplied(policy: Policy, text: String, fingerprint: String, signerCn: String?) {
		dao.insert(
			PolicyRecordEntity(
				handledUtcMs = clock.millis(), fingerprint = fingerprint, applied = true,
				policyId = policy.id.ifEmpty { null }, policyVersion = policy.version.ifEmpty { null },
				signerCn = signerCn, text = text,
			),
		)
	}

	suspend fun recordRefused(errors: List<PolicyError>, fingerprint: String) {
		dao.insert(
			PolicyRecordEntity(
				handledUtcMs = clock.millis(), fingerprint = fingerprint, applied = false,
				errors = errors.joinToString("\n"),
			),
		)
	}

	/* A stored text was valid when applied; an app update could only make it stricter, then the shipped app applies. */
	private fun parsed(text: String): Policy = (PolicyParser.parse(text) as? PolicyResult.Valid)?.policy ?: Policy.NONE
}
