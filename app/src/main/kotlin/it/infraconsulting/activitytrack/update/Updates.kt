/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.update

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.update.Release
import it.infraconsulting.activitytrack.domain.update.UpdateCheck
import it.infraconsulting.activitytrack.domain.update.UpdateFingerprint
import it.infraconsulting.activitytrack.domain.update.UpdateManifest
import it.infraconsulting.activitytrack.domain.update.UpdateSource
import it.infraconsulting.activitytrack.domain.update.Versions
import it.infraconsulting.activitytrack.notify.EntryLinks
import it.infraconsulting.activitytrack.notify.NotificationChannels
import it.infraconsulting.activitytrack.policy.PolicyLoader
import it.infraconsulting.activitytrack.ui.Screen
import it.infraconsulting.activitytrack.ui.settings.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

/*
 * Newer releases from a signed manifest (ActivityTrack Web SYNC.md §12.4, issue #1): once a day, for an
 * app not installed from a store (the store updates it) and not a debug build, and when the paired server
 * announces one at a sync. The manifest comes from the project (GitLab), the paired server or an address
 * of the user's; who may sign it follows the policy (UpdateManifest.Trust). A release found shows in
 * Settings → Updates and, once per version, as a notification; its package is downloaded from the server or
 * from the manifest's address, checked against the manifest's SHA-256 and handed to the system installer.
 * From a store the check stays available (Check now) but the download is never offered.
 */
class Updates(private val context: Context) {
	private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
	private val app get() = context.applicationContext as ActivityTrackApp
	private val _available = MutableStateFlow(load())
	val available: StateFlow<Release?> = _available.asStateFlow()

	/* why the last check came to nothing: the source is unreachable, or its manifest is refused */
	private val _error = MutableStateFlow<String?>(null)
	val error: StateFlow<String?> = _error.asStateFlow()

	sealed interface Download {
		data object Idle : Download
		data class Running(val percent: Int) : Download
		data class Failed(val message: String) : Download
	}

	private val _download = MutableStateFlow<Download>(Download.Idle)
	val download: StateFlow<Download> = _download.asStateFlow()

	val checkedUtcMs: Long get() = prefs.getLong(CHECKED, 0)

	/* installed by a store, which keeps it up to date */
	val fromStore: Boolean by lazy {
		val installer = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
		installer != null && installer in STORES
	}

	private val version: String by lazy { app.container.appVersion }

	/* debug builds go on by adb */
	private val debug: Boolean get() = version.endsWith("-debug")

	/* the daily check and its notification: an app from a store is left to it */
	val applies: Boolean get() = !fromStore && !debug

	/* "Check now": from a store too, where it only tells */
	val canCheck: Boolean get() = !debug

	/* the SHA-256 of the certificates this app is signed with (a rotated key keeps its history) */
	private fun ownCertificates(): Set<String> {
		val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
		val signing = info.signingInfo ?: return emptySet()
		val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
		return signers.orEmpty().map { UpdateFingerprint.of(it.toByteArray()) }.toSet()
	}

	/*
	 * Who may sign (issue #1). A signed policy in force: only its signer's identity (CN), vouched for by a CA of
	 * the phone or by the paired server's key. Otherwise this app's own certificate, or the paired server's key.
	 */
	private suspend fun trust(): UpdateManifest.Trust {
		val server = app.syncAccount.info.value?.signerKey
		val cn = app.container.policies.gateState().pinnedCn
		return if (cn != null) UpdateManifest.Trust(setOfNotNull(server), cn, PolicyLoader::trustAnchors)
		else UpdateManifest.Trust(ownCertificates() + setOfNotNull(server))
	}

	/* the manifest in [text] checked and read for this app; [source] keeps its own "issued" clock */
	private suspend fun accept(text: String, source: UpdateSource): Release? {
		val settings = app.container.settings.current()
		val verified = UpdateManifest.verify(text, trust())
		val key = ISSUED + source.name
		val last = prefs.getString(key, null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
		val found = UpdateCheck.newer(verified, version, settings.updateChannels, last, relative = source == UpdateSource.SERVER)
		prefs.edit().putString(key, verified.issued.toString()).apply()
		return found
	}

	/* the manifest at [address], https only */
	private fun fetch(address: String): String {
		if (!address.startsWith("https://")) throw UpdateManifest.Invalid(context.getString(R.string.update_err_https))
		val c = URL(address).openConnection() as HttpURLConnection
		try {
			c.connectTimeout = 15_000
			c.readTimeout = 30_000
			c.setRequestProperty("Accept", "application/json")
			c.setRequestProperty("User-Agent", "ActivityTrack/$version")
			if (c.responseCode == 404) throw UpdateManifest.Invalid(context.getString(R.string.update_err_none))
			if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
			/* one byte more than allowed tells a file too large */
			val bytes = c.inputStream.use { it.readNBytes(UpdateManifest.MAX_BYTES + 1) }
			if (bytes.size > UpdateManifest.MAX_BYTES) throw UpdateManifest.Invalid("too large")
			return bytes.toString(Charsets.UTF_8)
		} finally {
			c.disconnect()
		}
	}

	/*
	 * Asks the chosen source now; the newer release or null. Network errors are thrown (the daily worker tries again),
	 * a refused manifest is [error] and no release.
	 */
	suspend fun check(): Release? = withContext(Dispatchers.IO) {
		val settings = app.container.settings.current()
		val source = settings.updateSource
		_error.value = null
		val found = try {
			when (source) {
				UpdateSource.UPSTREAM -> accept(fetch(UpdateCheck.UPSTREAM_MANIFEST), source)
				UpdateSource.CUSTOM -> accept(fetch(settings.updateUrl), source)
				UpdateSource.SERVER -> {
					if (app.syncAccount.info.value == null) throw UpdateManifest.Invalid(context.getString(R.string.update_err_not_paired))
					app.syncAccount.head()
					app.syncAccount.updateManifest?.let { accept(it, source) }
				}
			}
		} catch (e: UpdateManifest.Invalid) {
			_error.value = context.getString(R.string.update_err_refused, e.message)
			Log.w(TAG, "manifest refused: ${e.message}")
			return@withContext null
		} catch (e: IOException) {
			_error.value = context.getString(R.string.update_err_network, e.message)
			throw e
		}
		prefs.edit().putLong(CHECKED, System.currentTimeMillis()).apply()
		store(found)
		found
	}

	/*
	 * The manifest the paired server announced with its head answer (issue #1), whatever the chosen source: shown
	 * and notified like a release found by the check, when it is newer than the one known.
	 */
	suspend fun fromServer(text: String?) {
		if (text == null || !canCheck || !app.container.settings.current().updateCheck) return
		val found = try {
			accept(text, UpdateSource.SERVER)
		} catch (e: UpdateManifest.Invalid) {
			Log.w(TAG, "server manifest refused: ${e.message}")
			return
		} ?: return
		val known = _available.value
		if (known == null || Versions.compare(found.version, known.version) > 0) store(found)
		if (applies) notifyOnce(found)
	}

	private fun store(found: Release?) {
		prefs.edit()
			.putString(VERSION, found?.version).putString(CHANNEL, found?.channel).putString(URL_KEY, found?.url)
			.putString(PACKAGE, found?.packageUrl).putString(SHA, found?.sha256).putLong(SIZE, found?.size ?: 0).apply()
		_available.value = found
	}

	/* a notification the first time a version is found; it opens Settings → Updates */
	fun notifyOnce(release: Release) {
		if (prefs.getString(NOTIFIED, null) == release.version) return
		prefs.edit().putString(NOTIFIED, release.version).apply()
		val open = EntryLinks.openScreen(context, Screen.SettingsPage(SettingsSection.UPDATES.name), NOTICE)
		val text = context.getString(if (release.beta) R.string.update_text_beta else R.string.update_text, release.version)
		val n = Notification.Builder(context, NotificationChannels.UPDATES)
			.setSmallIcon(R.drawable.ic_stat_timer)
			.setContentTitle(context.getString(R.string.update_title))
			.setContentText(text)
			.setContentIntent(open)
			.setAutoCancel(true)
			.build()
		context.getSystemService(NotificationManager::class.java).notify(NOTICE, n)
	}

	/*
	 * Downloads [release]'s package (from the paired server for a server path, else from its https address),
	 * checks its SHA-256 and the package it is, and opens the system installer. Never from a store's install.
	 */
	fun startDownload(release: Release) {
		if (fromStore || _download.value is Download.Running) return
		app.appScope.launch {
			_download.value = Download.Running(0)
			try {
				val file = withContext(Dispatchers.IO) { fetchPackage(release) }
				_download.value = Download.Idle
				install(file)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "download failed", e)
				_download.value = Download.Failed(e.message.orEmpty())
			}
		}
	}

	private suspend fun fetchPackage(release: Release): File {
		val dir = File(context.cacheDir, DIR).apply { mkdirs() }
		val dest = File(dir, "ActivityTrack-${release.version}.apk")
		dir.listFiles()?.filter { it != dest }?.forEach { it.delete() }
		/* already here and right: the installer was refused the first time (unknown sources) */
		if (dest.isFile && sha256Of(dest) == release.sha256) return checked(dest)
		val digest = MessageDigest.getInstance("SHA-256")
		var total = 0L
		fun copy(input: InputStream) {
			dest.outputStream().use { out ->
				val buf = ByteArray(64 * 1024)
				while (true) {
					val n = input.read(buf)
					if (n < 0) break
					total += n
					if (total > MAX_PACKAGE) throw IOException("too large")
					digest.update(buf, 0, n)
					out.write(buf, 0, n)
					if (release.size > 0) _download.value = Download.Running((total * 100 / release.size).toInt().coerceIn(0, 100))
				}
			}
		}
		try {
			if (release.packageUrl.startsWith("/")) {
				val account = app.syncAccount
				val http = account.http() ?: throw IOException(context.getString(R.string.update_err_not_paired))
				http.stream(release.packageUrl.removePrefix("/"), account.token(), ::copy)
			} else {
				if (!release.packageUrl.startsWith("https://")) throw IOException(context.getString(R.string.update_err_https))
				val c = URL(release.packageUrl).openConnection() as HttpURLConnection
				try {
					c.connectTimeout = 15_000
					c.readTimeout = 60_000
					c.setRequestProperty("User-Agent", "ActivityTrack/$version")
					if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
					c.inputStream.use(::copy)
				} finally {
					c.disconnect()
				}
			}
			if (!UpdateManifest.sha256Matches(digest.digest(), release.sha256)) throw IOException(context.getString(R.string.update_err_sha))
		} catch (e: Exception) {
			dest.delete()
			throw e
		}
		return checked(dest)
	}

	private fun sha256Of(file: File): String {
		val d = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buf = ByteArray(64 * 1024)
			while (true) {
				val n = input.read(buf)
				if (n < 0) break
				d.update(buf, 0, n)
			}
		}
		return d.digest().joinToString("") { "%02x".format(it) }
	}

	/* the file is this app, not another package that happens to have the right digest */
	private fun checked(file: File): File {
		val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
		if (info == null || info.packageName != context.packageName.removeSuffix(".debug")) {
			file.delete()
			throw IOException(context.getString(R.string.update_err_package))
		}
		return file
	}

	/* the system installer on [file]; the user first allows this app to install packages */
	private fun install(file: File) {
		if (!context.packageManager.canRequestPackageInstalls()) {
			context.startActivity(
				Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			)
			_download.value = Download.Failed(context.getString(R.string.update_install_allow))
			return
		}
		val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
		context.startActivity(
			Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
		)
	}

	fun dismissDownloadError() {
		if (_download.value is Download.Failed) _download.value = Download.Idle
	}

	private fun load(): Release? {
		val v = prefs.getString(VERSION, null) ?: return null
		/* updated meanwhile: nothing to offer */
		val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("")
		if (Versions.compare(v, installed) <= 0) return null
		val packageUrl = prefs.getString(PACKAGE, null)?.takeIf { it.isNotBlank() } ?: return null
		val sha = prefs.getString(SHA, null)?.takeIf { it.isNotBlank() } ?: return null
		/* no address stored: the releases page */
		val url = prefs.getString(URL_KEY, null)?.takeIf { it.isNotBlank() } ?: UpdateCheck.RELEASES_PAGE
		return Release(v, prefs.getString(CHANNEL, null) ?: "prod", url, packageUrl, sha, prefs.getLong(SIZE, 0))
	}

	companion object {
		private const val TAG = "Updates"
		private const val CHECKED = "checked"
		private const val VERSION = "version"
		private const val CHANNEL = "channel"
		private const val URL_KEY = "url"
		private const val PACKAGE = "package"
		private const val SHA = "sha256"
		private const val SIZE = "size"
		private const val ISSUED = "issued."
		private const val NOTIFIED = "notified"
		private const val NOTICE = 7401
		private const val DAILY = "update-daily"
		private const val NOW = "update-now"
		private const val DIR = "updates"
		private const val MAX_PACKAGE = 400L * 1024 * 1024

		/* installers that update the app themselves */
		private val STORES = setOf(
			"com.android.vending", "org.fdroid.fdroid", "org.fdroid.basic", "com.aurora.store", "dev.imranr.obtainium",
			"com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.amazon.venezia",
		)

		private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

		/* the daily check follows the setting (a policy may fix it) */
		fun schedule(context: Context, on: Boolean) {
			val wm = WorkManager.getInstance(context)
			if (on) wm.enqueueUniquePeriodicWork(
				DAILY, ExistingPeriodicWorkPolicy.KEEP,
				PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).setConstraints(network).addTag(UpdateWorker.DAILY_TAG).build(),
			) else wm.cancelUniqueWork(DAILY)
		}

		/* "Check now" */
		fun now(context: Context) {
			WorkManager.getInstance(context).enqueueUniqueWork(
				NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(network).build(),
			)
		}
	}
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val app = applicationContext as ActivityTrackApp
		val settings = app.container.settings.settings.first()
		/* the daily run: only when the user wants it; "Check now" is the one-time work */
		val daily = tags.contains(DAILY_TAG)
		if (!app.updates.canCheck || (daily && !(settings.updateCheck && app.updates.applies))) return Result.success()
		return try {
			app.updates.check()?.let { if (app.updates.applies) app.updates.notifyOnce(it) }
			Result.success()
		} catch (e: java.io.IOException) {
			Result.retry()
		}
	}

	companion object {
		const val DAILY_TAG = "update-daily-run"
	}
}
