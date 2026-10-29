/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import it.infraconsulting.activitytrack.connect.SecretStore
import it.infraconsulting.activitytrack.data.repo.SyncRepository
import it.infraconsulting.activitytrack.domain.sync.PairApi
import it.infraconsulting.activitytrack.domain.sync.PairError
import it.infraconsulting.activitytrack.domain.sync.PairException
import it.infraconsulting.activitytrack.domain.sync.PairLink
import it.infraconsulting.activitytrack.domain.sync.PairRequest
import it.infraconsulting.activitytrack.domain.sync.SyncAccountInfo
import java.io.IOException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/*
 * The pairing with ActivityTrack Web: the token and the pin live in the
 * SecretStore (Keystore-encrypted, never in backups), the server address
 * also in sync_state for the engine (issue #114).
 */
class SyncAccount(private val context: Context, private val secrets: SecretStore, private val dao: SyncRepository, private val userAgent: String) {
	private val _info = MutableStateFlow(load())
	val info: StateFlow<SyncAccountInfo?> = _info.asStateFlow()

	/* the server revoked this phone (issue #114): no more syncs until it is paired again */
	private val _revoked = MutableStateFlow(secrets.getApp(REVOKED) != null)
	val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

	fun markRevoked() {
		secrets.putApp(REVOKED, "1")
		_revoked.value = true
	}

	/* a policy from the server is in force: policy.json in the data folder is ignored (issue #117) */
	fun serverPolicyInForce(): Boolean = _info.value != null && secrets.getApp(SERVER_POLICY) != null

	fun markServerPolicy() = secrets.putApp(SERVER_POLICY, "1")

	/* true the first time a given ignored policy.json is seen (one notification per file) */
	fun noteIgnoredFile(fingerprint: String): Boolean {
		if (secrets.getApp(IGNORED_FILE) == fingerprint) return false
		secrets.putApp(IGNORED_FILE, fingerprint)
		return true
	}

	/* sends to the management systems: not paired, or the phone the server names (issue #118) */
	fun sendsToPlugins(): Boolean = _info.value?.pluginHolder ?: true

	/*
	 * The server's head: its revision (null when unreachable), and the
	 * plugin holder kept up to date (the web page may move it).
	 */
	suspend fun head(): Long? {
		val http = http() ?: return null
		val r = runCatching { http.send("GET", "api/v1/sync/head", token = token()) }.getOrNull() ?: return null
		if (r.status == 401) {
			markRevoked()
			return null
		}
		val o = runCatching { PairApi.json.parseToJsonElement(r.body).jsonObject }.getOrNull() ?: return null
		o["pluginHolder"]?.jsonPrimitive?.booleanOrNull?.let { holder ->
			val info = _info.value
			if (info != null && info.pluginHolder != holder) {
				val updated = info.copy(pluginHolder = holder)
				secrets.putApp(INFO, PairApi.json.encodeToString(SyncAccountInfo.serializer(), updated))
				_info.value = updated
			}
		}
		return o["rev"]?.jsonPrimitive?.long
	}

	/* true when the server has changes after [cursor] (the cheap check while the app is in front) */
	suspend fun serverAhead(cursor: Long): Boolean = (head() ?: return false) > cursor

	/* a pairing link that opened the app, waiting for the user to confirm it */
	val pendingLink = MutableStateFlow<String?>(null)

	fun token(): String? = secrets.getApp(TOKEN)

	fun pin(): String? = secrets.getApp(PIN)

	fun http(): SyncHttp? = _info.value?.let { SyncHttp(it.server, pin(), userAgent) }

	private fun load(): SyncAccountInfo? = runCatching {
		secrets.getApp(INFO)?.let { PairApi.json.decodeFromString(SyncAccountInfo.serializer(), it) }
	}.getOrNull()?.takeIf { secrets.getApp(TOKEN) != null }

	private fun deviceName(): String =
		Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

	/* pairs with the server of [link]; throws PairException */
	suspend fun pair(link: PairLink): SyncAccountInfo {
		val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
		val request = PairRequest(link.code, deviceName(), "${Build.MANUFACTURER} ${Build.MODEL}".trim(), version)
		val reply = try {
			SyncHttp(link.server, link.pin, userAgent).send("POST", "api/v1/pair", PairApi.request(request))
		} catch (e: SSLException) {
			throw PairException(PairError.CERTIFICATE, e.message)
		} catch (e: GeneralSecurityException) {
			throw PairException(PairError.CERTIFICATE, e.message)
		} catch (e: IOException) {
			throw PairException(PairError.NETWORK, e.message)
		}
		val answer = PairApi.reply(reply.status, reply.body)
		val info = SyncAccountInfo(
			server = link.server,
			deviceId = answer.deviceId,
			username = answer.user.username,
			userName = answer.user.name,
			profile = answer.user.profile,
			org = answer.org.name,
			pinned = link.pin != null,
			pairedUtcMs = System.currentTimeMillis(),
			pluginHolder = answer.pluginHolder,
		)
		dao.forgetServer()
		secrets.putApp(TOKEN, answer.token)
		secrets.putApp(PIN, link.pin)
		secrets.putApp(INFO, PairApi.json.encodeToString(SyncAccountInfo.serializer(), info))
		dao.setServer(link.server)
		secrets.putApp(REVOKED, null)
		_revoked.value = false
		_info.value = info
		return info
	}

	/* forgets the server; the phone keeps all its data */
	suspend fun unpair() {
		secrets.putApp(TOKEN, null)
		secrets.putApp(PIN, null)
		secrets.putApp(INFO, null)
		secrets.putApp(REVOKED, null)
		/* the policy.json of the data folder applies again */
		secrets.putApp(SERVER_POLICY, null)
		secrets.putApp(IGNORED_FILE, null)
		dao.forgetServer()
		_revoked.value = false
		_info.value = null
	}

	companion object {
		/* the server's profiles that change central master data (issue #115) */
		val CENTRAL_EDITORS = setOf("admin", "manager")

		private const val TOKEN = "sync.token"
		private const val PIN = "sync.pin"
		private const val INFO = "sync.info"
		private const val REVOKED = "sync.revoked"
		private const val SERVER_POLICY = "sync.policy"
		private const val IGNORED_FILE = "sync.policyFileIgnored"
	}
}
