/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.maps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import it.infraconsulting.activitytrack.domain.geo.Geo
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.geo.OfflineMapCatalog
import it.infraconsulting.activitytrack.domain.geo.OfflineMapEntry
import it.infraconsulting.activitytrack.domain.geo.PmtilesHeader
import it.infraconsulting.activitytrack.sync.SyncHttp
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/* Where a map came from: the ActivityTrack Web server (kept in step with it) or the data folder (kept until deleted). */
enum class MapOrigin { SERVER, MANUAL }

/* An offline map on the phone: what the file is, the area it covers and the zooms it holds. */
data class InstalledMap(
	val id: String,
	val name: String,
	val file: String,
	val bytes: Long,
	val sha256: String,
	val minLon: Double,
	val minLat: Double,
	val maxLon: Double,
	val maxLat: Double,
	val maxZoom: Int,
	val updated: String?,
	val origin: MapOrigin,
	val addedUtcMs: Long,
) {
	val centre: GeoPoint get() = GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2)

	fun covers(p: GeoPoint) = p.lat in minLat..maxLat && p.lon in minLon..maxLon
}

enum class OfflineMapPhase { SERVER, FOLDER }

enum class OfflineMapError { NO_FOLDER, NOTHING_FOUND, INVALID_FILE, TOO_LARGE, CORRUPT, NO_NETWORK, FAILED }

sealed interface OfflineMapState {
	data object Idle : OfflineMapState
	data class Working(val phase: OfflineMapPhase, val fraction: Float? = null) : OfflineMapState
	/* [imported] maps taken from the data folder; [skipped] files that were not valid maps */
	data class Imported(val imported: Int, val skipped: Int) : OfflineMapState
	data class Failed(val error: OfflineMapError) : OfflineMapState
}

private class Failure(val error: OfflineMapError) : Exception()

/*
 * The offline maps of the track map (issue #155, docs/OFFLINE-MAPS.md): vector
 * PMTiles files of OpenStreetMap data, kept in the app's private storage, any
 * number of them, each for an area. They come from
 *  - ActivityTrack Web, with the sync: the maps the server offers are downloaded
 *    on an unmetered network and kept in step with it (a map the server no longer
 *    offers goes away);
 *  - the data folder: the files copied into <data folder>/maps are imported on
 *    request and kept until deleted.
 * What comes from outside is checked: a catalog entry by [OfflineMapCatalog], a
 * downloaded file against the size and SHA-256 the catalog declares, a file of the
 * folder by its PMTiles header ([PmtilesHeader]). Work runs in the app scope, so it
 * goes on when the screen is left.
 */
class OfflineMaps(
	context: Context,
	private val settings: SettingsRepository,
	private val scope: CoroutineScope,
) {
	private val dir = File(context.applicationContext.filesDir, "maps").also { d ->
		/* a download or import the process did not finish */
		d.listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") }?.forEach { it.delete() }
	}
	private val index = File(dir, INDEX)
	private val lock = Mutex()
	private var serverRev = ""
	private val _installed = MutableStateFlow(read())
	val installed: StateFlow<List<InstalledMap>> = _installed.asStateFlow()
	private val _state = MutableStateFlow<OfflineMapState>(OfflineMapState.Idle)
	val state: StateFlow<OfflineMapState> = _state.asStateFlow()
	private var job: Job? = null

	/* The map file, when it is there and whole. */
	fun file(m: InstalledMap): File? = File(dir, m.file).takeIf { it.isFile && it.length() == m.bytes }

	/* The map for [at]: of those that cover it, the one whose centre is nearest. */
	fun best(at: GeoPoint, among: List<InstalledMap> = _installed.value): InstalledMap? =
		among.filter { it.covers(at) && file(it) != null }.minByOrNull { Geo.distanceM(it.centre, at) }

	fun clearState() {
		if (_state.value !is OfflineMapState.Working)
			_state.value = OfflineMapState.Idle
	}

	/* Deletes one map. */
	fun delete(id: String) {
		scope.launch {
			lock.withLock {
				val m = _installed.value.firstOrNull { it.id == id } ?: return@withLock
				File(dir, m.file).delete()
				save(_installed.value - m)
			}
		}
	}

	/*
	 * Takes the maps of <data folder>/maps into the app: new and changed ones are copied,
	 * checked and added; a file that is not a valid vector PMTiles map is skipped.
	 */
	fun importFromFolder(userDataDir: UserDataDir, tree: Uri?) {
		if (job?.isActive == true)
			return
		job = scope.launch {
			_state.value = OfflineMapState.Working(OfflineMapPhase.FOLDER)
			_state.value = try {
				if (tree == null)
					throw Failure(OfflineMapError.NO_FOLDER)
				val files = userDataDir.list(tree, UserDataDir.Sub.MAPS).filter { it.name.endsWith(".pmtiles", ignoreCase = true) }
				if (files.isEmpty())
					throw Failure(OfflineMapError.NOTHING_FOUND)
				var imported = 0
				var skipped = 0
				for ((i, f) in files.withIndex()) {
					_state.value = OfflineMapState.Working(OfflineMapPhase.FOLDER, i.toFloat() / files.size)
					try {
						if (lock.withLock { importOne(userDataDir, f) }) imported++
					} catch (e: Failure) {
						skipped++
					}
				}
				OfflineMapState.Imported(imported, skipped)
			} catch (e: CancellationException) {
				_state.value = OfflineMapState.Idle
				throw e
			} catch (e: Failure) {
				OfflineMapState.Failed(e.error)
			} catch (e: IOException) {
				OfflineMapState.Failed(OfflineMapError.FAILED)
			}
		}
	}

	/* True when the file is new or changed (and now installed); throws [Failure] when it is not a usable map. */
	private suspend fun importOne(userDataDir: UserDataDir, f: UserDataDir.DocFile): Boolean {
		dir.mkdirs()
		val id = MANUAL_PREFIX + f.name.removeSuffix(".pmtiles").removeSuffix(".PMTILES").lowercase()
			.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(50).ifEmpty { "map" }
		val part = File(dir, "$id.part")
		try {
			val sha = withContext(Dispatchers.IO) {
				val digest = MessageDigest.getInstance("SHA-256")
				var size = 0L
				userDataDir.openInput(f.uri).use { input ->
					part.outputStream().use { out ->
						val buf = ByteArray(32 * 1024)
						while (true) {
							val n = input.read(buf)
							if (n < 0) break
							size += n
							if (size > OfflineMapCatalog.MAX_BYTES) throw Failure(OfflineMapError.TOO_LARGE)
							digest.update(buf, 0, n)
							out.write(buf, 0, n)
						}
					}
				}
				digest.digest().hex()
			}
			val current = _installed.value.firstOrNull { it.id == id }
			if (current != null && current.sha256 == sha && file(current) != null)
				return false
			val header = withContext(Dispatchers.IO) { part.inputStream().use { it.readNBytes(PmtilesHeader.SIZE) } }
				.let(PmtilesHeader::parse) ?: throw Failure(OfflineMapError.INVALID_FILE)
			if (header.problem(part.length()) != null)
				throw Failure(OfflineMapError.INVALID_FILE)
			val name = f.name.removeSuffix(".pmtiles").removeSuffix(".PMTILES").replace(Regex("[-_]+"), " ").trim().ifEmpty { id }
			val file = "$id-${sha.take(8)}.pmtiles"
			if (!part.renameTo(File(dir, file)))
				throw IOException("cannot move the map")
			val m = InstalledMap(
				id, name, file, File(dir, file).length(), sha, header.minLon, header.minLat, header.maxLon, header.maxLat, header.maxZoom,
				java.time.LocalDate.now().toString(), MapOrigin.MANUAL, System.currentTimeMillis(),
			)
			current?.let { File(dir, it.file).delete() }
			save(_installed.value.filter { it.id != id } + m)
			return true
		} finally {
			part.delete()
		}
	}

	/*
	 * Brings the server's maps in line with the catalog it offers (`mapsRev` is its fingerprint,
	 * "" when it offers none): new and changed ones are downloaded, those it no longer offers
	 * are deleted. Downloads wait for an unmetered network ([downloads]); the revision is
	 * remembered only when everything is in place, so a missed download is tried again.
	 */
	suspend fun syncFromServer(http: SyncHttp, token: String, rev: String, downloads: Boolean) {
		if (!settings.current().offlineMapsEnabled)
			return
		lock.withLock {
			val have = _installed.value.filter { it.origin == MapOrigin.SERVER }
			if (rev == serverRev && have.all { file(it) != null })
				return
			if (rev.isEmpty()) {
				have.forEach { File(dir, it.file).delete() }
				serverRev = ""
				save(_installed.value - have.toSet())
				return
			}
			val r = http.send("GET", "api/v1/maps", token = token)
			if (r.status != 200)
				return
			val catalog = try {
				OfflineMapCatalog.parse(r.body)
			} catch (e: OfflineMapCatalog.Invalid) {
				return
			}
			val gone = have.filter { m -> catalog.none { it.id == m.id } }
			val todo = catalog.filter { e -> have.none { it.id == e.id && it.sha256 == e.sha256 && file(it) != null } }
			if (todo.isNotEmpty() && !downloads) {
				/* the ones no longer offered still go */
				if (gone.isNotEmpty()) {
					gone.forEach { File(dir, it.file).delete() }
					save(_installed.value - gone.toSet())
				}
				return
			}
			dir.mkdirs()
			var installed = _installed.value - gone.toSet()
			gone.forEach { File(dir, it.file).delete() }
			var complete = true
			for ((i, e) in todo.withIndex()) {
				_state.value = OfflineMapState.Working(OfflineMapPhase.SERVER, i.toFloat() / todo.size)
				try {
					val m = fetch(http, token, e, i, todo.size)
					installed.filter { it.id == e.id }.forEach { File(dir, it.file).takeIf { f -> f.name != m.file }?.delete() }
					installed = installed.filter { it.id != e.id } + m
					save(installed)
				} catch (x: Failure) {
					complete = false
				} catch (x: IOException) {
					complete = false
				}
			}
			if (complete)
				serverRev = rev
			save(installed)
			_state.value = OfflineMapState.Idle
		}
	}

	/* Streams a map to a part file, checking the size and the SHA-256 of the catalog; installs it. */
	private suspend fun fetch(http: SyncHttp, token: String, e: OfflineMapEntry, n: Int, of: Int): InstalledMap {
		val part = File(dir, "${e.id}.part")
		try {
			val sha = http.stream("api/v1/maps/${e.id}", token) { input -> copy(input, part, e) { f -> _state.value = OfflineMapState.Working(OfflineMapPhase.SERVER, (n + f) / of) } }
			if (sha != e.sha256)
				throw Failure(OfflineMapError.CORRUPT)
			val header = part.inputStream().use { it.readNBytes(PmtilesHeader.SIZE) }.let(PmtilesHeader::parse)
			if (header == null || header.problem(part.length()) != null)
				throw Failure(OfflineMapError.INVALID_FILE)
			val file = "${e.id}-${sha.take(8)}.pmtiles"
			if (!part.renameTo(File(dir, file)))
				throw IOException("cannot move the map")
			return InstalledMap(
				e.id, e.name, file, e.bytes, sha, e.minLon, e.minLat, e.maxLon, e.maxLat, e.maxZoom, e.updated, MapOrigin.SERVER, System.currentTimeMillis(),
			)
		} finally {
			part.delete()
		}
	}

	/* Copies [input] to [out] and returns the SHA-256; a file larger than the catalog declared is not its file. */
	private suspend fun copy(input: InputStream, out: File, e: OfflineMapEntry, progress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
		val digest = MessageDigest.getInstance("SHA-256")
		var done = 0L
		out.outputStream().use { o ->
			val buf = ByteArray(32 * 1024)
			while (true) {
				val n = input.read(buf)
				if (n < 0) break
				done += n
				if (done > e.bytes) throw Failure(OfflineMapError.CORRUPT)
				digest.update(buf, 0, n)
				o.write(buf, 0, n)
				progress(done.toFloat() / e.bytes)
				ensureActive()
			}
		}
		if (done != e.bytes) throw Failure(OfflineMapError.CORRUPT)
		digest.digest().hex()
	}

	private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

	private fun read(): List<InstalledMap> = try {
		val o = JSONObject(index.readText())
		serverRev = o.optString("serverRev")
		val a = o.getJSONArray("maps")
		(0 until a.length()).map { i ->
			val m = a.getJSONObject(i)
			val bbox = m.getJSONArray("bbox")
			InstalledMap(
				m.getString("id"), m.getString("name"), m.getString("file"), m.getLong("bytes"), m.getString("sha256"),
				bbox.getDouble(0), bbox.getDouble(1), bbox.getDouble(2), bbox.getDouble(3), m.getInt("maxZoom"),
				m.optString("updated").ifEmpty { null }, MapOrigin.valueOf(m.getString("origin")), m.getLong("added"),
			)
		}.filter { File(dir, it.file).isFile }
	} catch (e: IOException) {
		emptyList()
	} catch (e: JSONException) {
		emptyList()
	} catch (e: IllegalArgumentException) {
		emptyList()
	}

	private fun save(list: List<InstalledMap>) {
		writeIndex(list)
		_installed.value = list
	}

	private fun writeIndex(list: List<InstalledMap>) {
		dir.mkdirs()
		val tmp = File(dir, "$INDEX.tmp")
		tmp.writeText(
			JSONObject().put("serverRev", serverRev).put(
				"maps",
				JSONArray().also { a ->
					list.forEach { m ->
						a.put(
							JSONObject().put("id", m.id).put("name", m.name).put("file", m.file).put("bytes", m.bytes).put("sha256", m.sha256)
								.put("bbox", JSONArray(listOf(m.minLon, m.minLat, m.maxLon, m.maxLat))).put("maxZoom", m.maxZoom)
								.put("updated", m.updated ?: "").put("origin", m.origin.name).put("added", m.addedUtcMs),
						)
					}
				},
			).toString(),
		)
		if (!tmp.renameTo(index))
			throw IOException("cannot write the maps' index")
	}

	companion object {
		private const val INDEX = "offline-maps.json"
		private const val MANUAL_PREFIX = "manual-"

		/* True when the system has a validated connection: what decides between the OSM tiles and the offline map. */
		fun networkAvailable(context: Context): Boolean {
			val cm = context.getSystemService(ConnectivityManager::class.java)
			val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
			return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
		}
	}
}
