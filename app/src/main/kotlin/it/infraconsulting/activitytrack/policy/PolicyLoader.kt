/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.policy

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.backup.BackupWorker
import it.infraconsulting.activitytrack.data.storage.DatabaseBackup
import it.infraconsulting.activitytrack.domain.policy.PolicyChanges
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
 * until it changes. An unsigned file waits for the user's confirmation
 * (TrustPrompts; a notification leads there); refused by the user, it is
 * recorded as refused. The server's policy needs none (trusted through the
 * pairing).
 */
object PolicyLoader {
	private const val TAG = "PolicyLoader"
	const val FILE = "policy.json"
	const val SIGNATURE = "policy.json.sig"
	const val OLD = "policy_old.json"
	private const val NOTIFICATION_ID = 48_000
	private const val TRUST_PREFS = "policy_trust"
	private const val ASKED = "asked"

	private val lock = Mutex()

	/* the unsigned policy.json waiting for the user, found by the run under way */
	private var asking: TrustPrompts.PolicyPrompt? = null

	/* [confirmed]: the fingerprint of the unsigned policy.json the user has just confirmed */
	suspend fun check(context: Context, confirmed: String? = null) = lock.withLock {
		val app = context.applicationContext as ActivityTrackApp
		asking = null
		try {
			run(app, confirmed)
			app.trust.policy.value = asking
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "policy check failed", e)
		}
		/* the plugins follow the policy in force: which ones, signed or not (issue #48 B2) */
		try {
			app.plugins.reload()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "plugin check failed", e)
		}
	}

	/* The user refused the unsigned policy.json [fingerprint]: refused like a bad file, not asked again until it changes. */
	suspend fun decline(context: Context, fingerprint: String) = lock.withLock {
		val app = context.applicationContext as ActivityTrackApp
		val c = app.container
		if (c.policies.gateState().lastFingerprint != fingerprint)
			c.policies.recordRefused(listOf(PolicyError("", null, app.getString(R.string.trust_refused_error))), fingerprint)
		if (app.trust.policy.value?.fingerprint == fingerprint)
			app.trust.policy.value = null
		app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
		Log.i(TAG, "unsigned policy $fingerprint refused by the user")
	}

	/* The plugins again, after the user answered for one. */
	suspend fun reloadPlugins(context: Context) = lock.withLock {
		val app = context.applicationContext as ActivityTrackApp
		try {
			app.plugins.reload()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "plugin check failed", e)
		}
	}

	private suspend fun run(app: Context, confirmed: String?) {
		val c = (app as ActivityTrackApp).container
		val tree = c.settings.current().rootUri?.toUri() ?: return
		if (!c.userDataDir.isAccessible(tree))
			return
		val policy = read(app, tree, FILE) ?: return
		val sig = read(app, tree, SIGNATURE)
		/* paired with a server that sends a policy: it wins over the file (issue #117) */
		if (app.syncAccount.serverPolicyInForce()) {
			val fp = PolicyGate.fingerprint(policy, sig)
			if (app.syncAccount.noteIgnoredFile(fp))
				notify(app, app.getString(R.string.policy_file_ignored_title), app.getString(R.string.policy_file_ignored_text))
			return
		}
		handle(app, tree, policy, sig, FILE, confirmed)
	}

	/*
	 * The policy of ActivityTrack Web (issue #117, SYNC.md §8): the same
	 * checks as policy.json (unsigned accepted until a signer's CN is pinned,
	 * then signed by it), the same backup and notification.
	 */
	suspend fun fromServer(context: Context, policy: ByteArray, sig: ByteArray?) = lock.withLock {
		val app = context.applicationContext as ActivityTrackApp
		val tree = app.container.settings.current().rootUri?.toUri()?.takeIf(app.container.userDataDir::isAccessible) ?: return@withLock
		try {
			/* the server's own certificate may be self-signed: its fingerprint comes from the pairing link (issue #1) */
			val pinned = setOfNotNull(app.syncAccount.info.value?.signerKey)
			if (handle(app, tree, policy, sig, app.getString(R.string.policy_from_server), pinned = pinned)) {
				app.syncAccount.markServerPolicy()
				/* the file no longer counts: nothing to confirm */
				app.trust.policy.value = null
			}
			app.plugins.reload()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "server policy failed", e)
		}
	}

	/* true when the policy is (or already was) in force */
	private suspend fun handle(
		app: ActivityTrackApp, tree: Uri, policy: ByteArray, sig: ByteArray?, source: String, confirmed: String? = null, pinned: Set<String> = emptySet(),
	): Boolean {
		val c = app.container
		val state = c.policies.gateState()
		val outcome = if (policy.size > MAX_BYTES || (sig?.size ?: 0) > MAX_BYTES) {
			val fp = PolicyGate.fingerprint(policy, sig)
			if (fp == state.lastFingerprint) PolicyGate.Outcome.Unchanged
			else PolicyGate.Outcome.Refuse(listOf(PolicyError("", null, app.getString(R.string.policy_too_large))), fp)
		} else {
			PolicyGate.decide(policy, sig, state, ::trustAnchors, askUnsigned = source == FILE, confirmed = confirmed, pinned = pinned)
		}
		when (val o = outcome) {
			/* handled before: in force or refused, as it was */
			PolicyGate.Outcome.Unchanged -> return source != FILE && app.syncAccount.serverPolicyInForce()
			is PolicyGate.Outcome.Refuse -> {
				c.policies.recordRefused(o.errors, o.fingerprint)
				notify(app, app.getString(R.string.policy_refused_title), o.errors.take(8).joinToString("\n") + more(app, o.errors))
				Log.w(TAG, "$source refused: ${o.errors.joinToString("; ")}")
				return false
			}
			is PolicyGate.Outcome.Confirm -> {
				val name = listOf(o.policy.id, o.policy.version).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { FILE }
				asking = TrustPrompts.PolicyPrompt(o.fingerprint, name, PolicyChanges.of(c.policies.inForceText(), policy.toString(Charsets.UTF_8)))
				/* one notification per file; tapping it opens the app, where the confirmation waits */
				val prefs = app.getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE)
				if (prefs.getString(ASKED, null) != o.fingerprint) {
					prefs.edit { putString(ASKED, o.fingerprint) }
					notify(app, app.getString(R.string.trust_policy_title), app.getString(R.string.trust_policy_notice, o.fingerprint.take(16)))
				}
				Log.i(TAG, "unsigned $source ${o.fingerprint} waits for confirmation")
				return false
			}
			is PolicyGate.Outcome.Apply -> {
				val now = Instant.now()
				/* the backup first: without it the policy waits for the next check */
				if (!BackupWorker.write(app, tree, DatabaseBackup.policyFileName(now, ZoneId.systemDefault()), now))
					return false
				val text = policy.toString(Charsets.UTF_8)
				/* what changes against the policy in force, for the notification (security review 0.9.8, R3) */
				val changes = PolicyChanges.of(c.policies.inForceText(), text)
				writeRoot(app, tree, OLD, c.policies.inForceText() ?: EMPTY)
				c.policies.recordApplied(o.policy, text, o.fingerprint, o.signerCn)
				c.settings.update { o.policy.applyTo(it) }
				val name = listOf(o.policy.id, o.policy.version).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { source }
				notify(
					app,
					app.getString(R.string.policy_applied_title, name),
					(o.signerCn?.let { app.getString(R.string.policy_applied_signed, it) } ?: app.getString(R.string.policy_applied_unsigned)) +
						changesSummary(app, changes),
				)
				Log.i(TAG, "policy $name applied from $source, signer ${o.signerCn}")
				return true
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
	private const val CHANGE_LINES = 8
	private const val CHANGE_LINE_MAX = 200

	private suspend fun writeRoot(app: ActivityTrackApp, tree: Uri, name: String, text: String) {
		val dir = app.container.userDataDir
		val uri = dir.resolve(tree, name) ?: dir.createAt(tree, emptyList(), name, "application/json")
		dir.openOutput(uri).use { it.write(text.toByteArray(Charsets.UTF_8)) }
	}

	/* The phone's trusted CAs, system and user/MDM ones (owner, issue #48). */
	fun trustAnchors(): Set<TrustAnchor> {
		val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
		return ks.aliases().toList().mapNotNull { (ks.getCertificate(it) as? X509Certificate)?.let { c -> TrustAnchor(c, null) } }.toSet()
	}

	/*
	 * The first changes, one per line, for the "applied" notification: the server may change them without the
	 * user asking. A value is already cut to 80 characters (PolicyChanges); a line is cut again, as a key can be
	 * long and a notification that is too big makes the system refuse it.
	 */
	private fun changesSummary(app: Context, changes: List<PolicyChanges.Change>) =
		changes.take(CHANGE_LINES).joinToString("") { "\n" + changeLine(it).take(CHANGE_LINE_MAX) } +
			if (changes.size > CHANGE_LINES) "\n" + app.getString(R.string.policy_more_changes, changes.size - CHANGE_LINES) else ""

	private fun more(app: Context, errors: List<PolicyError>) =
		if (errors.size > 8) "\n" + app.getString(R.string.policy_more_errors, errors.size - 8) else ""

	fun notify(app: Context, title: String, text: String, id: Int = NOTIFICATION_ID) {
		val n = Notification.Builder(app, NotificationChannels.POLICY)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(title)
			.setContentText(text.lineSequence().first())
			.setStyle(Notification.BigTextStyle().bigText(text))
			.setContentIntent(EntryLinks.openScreen(app, Screen.Settings, id))
			.setAutoCancel(true)
			.build()
		app.getSystemService(NotificationManager::class.java).notify(id, n)
	}
}
