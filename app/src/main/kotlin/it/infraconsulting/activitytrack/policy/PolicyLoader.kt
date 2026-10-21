/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.policy

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.backup.BackupWorker
import it.infraconsulting.activitytrack.data.storage.DatabaseBackup
import it.infraconsulting.activitytrack.domain.policy.PolicyError
import it.infraconsulting.activitytrack.domain.policy.PolicyGate
import it.infraconsulting.activitytrack.domain.policy.PolicyParser
import it.infraconsulting.activitytrack.notify.NotificationChannels
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.notify.EntryLinks
import java.security.KeyStore
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * policy.json in the root of the data folder (issue #48), checked at app
 * start and whenever the app comes to the foreground. A new valid file:
 * extra backup (kept), the policy in force dumped to policy_old.json, the
 * new one stored and its settings written, a notification with the
 * summary. A refused file: a notification with the reasons, then ignored
 * until it changes.
 */
object PolicyLoader {
	private const val TAG = "PolicyLoader"
	const val FILE = "policy.json"
	const val SIGNATURE = "policy.json.sig"
	const val OLD = "policy_old.json"
	private const val NOTIFICATION_ID = 48_000

	private val lock = Mutex()

	suspend fun check(context: Context) = lock.withLock {
		try {
			run(context.applicationContext)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "policy check failed", e)
		}
	}

	private suspend fun run(app: Context) {
		val c = (app as ActivityTrackApp).container
		val tree = c.settings.current().rootUri?.toUri() ?: return
		if (!c.userDataDir.isAccessible(tree))
			return
		val policy = read(app, tree, FILE) ?: return
		val sig = read(app, tree, SIGNATURE)
		val state = c.policies.gateState()
		val outcome = if (policy.size > MAX_BYTES || (sig?.size ?: 0) > MAX_BYTES) {
			val fp = PolicyGate.fingerprint(policy, sig)
			if (fp == state.lastFingerprint) PolicyGate.Outcome.Unchanged
			else PolicyGate.Outcome.Refuse(listOf(PolicyError("", null, app.getString(R.string.policy_too_large))), fp)
		} else {
			PolicyGate.decide(policy, sig, state, ::trustAnchors)
		}
		when (val o = outcome) {
			PolicyGate.Outcome.Unchanged -> Unit
			is PolicyGate.Outcome.Refuse -> {
				c.policies.recordRefused(o.errors, o.fingerprint)
				notify(app, app.getString(R.string.policy_refused_title), o.errors.take(8).joinToString("\n") + more(app, o.errors))
				Log.w(TAG, "policy.json refused: ${o.errors.joinToString("; ")}")
			}
			is PolicyGate.Outcome.Apply -> {
				val now = Instant.now()
				/* the backup first: without it the policy waits for the next check */
				if (!BackupWorker.write(app, tree, DatabaseBackup.policyFileName(now, ZoneId.systemDefault()), now))
					return
				writeRoot(app, tree, OLD, c.policies.inForceText() ?: EMPTY)
				val text = policy.toString(Charsets.UTF_8)
				c.policies.recordApplied(o.policy, text, o.fingerprint, o.signerCn)
				c.settings.update { o.policy.applyTo(it) }
				val name = listOf(o.policy.id, o.policy.version).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { FILE }
				notify(
					app,
					app.getString(R.string.policy_applied_title, name),
					o.signerCn?.let { app.getString(R.string.policy_applied_signed, it) } ?: app.getString(R.string.policy_applied_unsigned),
				)
				Log.i(TAG, "policy $name applied, signer ${o.signerCn}")
			}
		}
	}

	/* the app as shipped, dumped when no policy was in force before */
	private val EMPTY = "{ \"format\": \"${PolicyParser.FORMAT}\", \"schema\": ${PolicyParser.SCHEMA} }\n"

	private suspend fun read(app: ActivityTrackApp, tree: Uri, name: String): ByteArray? {
		val uri = app.container.userDataDir.resolve(tree, name) ?: return null
		/* one byte more than allowed tells a file too large */
		return withContext(Dispatchers.IO) { app.container.userDataDir.openInput(uri).use { it.readNBytes(MAX_BYTES + 1) } }
	}

	private const val MAX_BYTES = 1 shl 20

	private suspend fun writeRoot(app: ActivityTrackApp, tree: Uri, name: String, text: String) {
		val dir = app.container.userDataDir
		val uri = dir.resolve(tree, name) ?: dir.createAt(tree, emptyList(), name, "application/json")
		dir.openOutput(uri).use { it.write(text.toByteArray(Charsets.UTF_8)) }
	}

	/* The phone's trusted CAs, system and user/MDM ones (owner, issue #48). */
	private fun trustAnchors(): Set<TrustAnchor> {
		val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
		return ks.aliases().toList().mapNotNull { (ks.getCertificate(it) as? X509Certificate)?.let { c -> TrustAnchor(c, null) } }.toSet()
	}

	private fun more(app: Context, errors: List<PolicyError>) =
		if (errors.size > 8) "\n" + app.getString(R.string.policy_more_errors, errors.size - 8) else ""

	private fun notify(app: Context, title: String, text: String) {
		val n = Notification.Builder(app, NotificationChannels.POLICY)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(title)
			.setContentText(text.lineSequence().first())
			.setStyle(Notification.BigTextStyle().bigText(text))
			.setContentIntent(EntryLinks.openScreen(app, Screen.Settings, NOTIFICATION_ID))
			.setAutoCancel(true)
			.build()
		app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
	}
}
