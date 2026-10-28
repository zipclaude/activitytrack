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

/*
 * The pairing with ActivityTrack Web: the token and the pin live in the
 * SecretStore (Keystore-encrypted, never in backups), the server address
 * also in sync_state for the engine (issue #114).
 */
class SyncAccount(private val context: Context, private val secrets: SecretStore, private val dao: SyncRepository, private val userAgent: String) {
	private val _info = MutableStateFlow(load())
	val info: StateFlow<SyncAccountInfo?> = _info.asStateFlow()

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
		)
		dao.forgetServer()
		secrets.putApp(TOKEN, answer.token)
		secrets.putApp(PIN, link.pin)
		secrets.putApp(INFO, PairApi.json.encodeToString(SyncAccountInfo.serializer(), info))
		dao.setServer(link.server)
		_info.value = info
		return info
	}

	/* forgets the server; the phone keeps all its data */
	suspend fun unpair() {
		secrets.putApp(TOKEN, null)
		secrets.putApp(PIN, null)
		secrets.putApp(INFO, null)
		dao.forgetServer()
		_info.value = null
	}

	private companion object {
		const val TOKEN = "sync.token"
		const val PIN = "sync.pin"
		const val INFO = "sync.info"
	}
}
