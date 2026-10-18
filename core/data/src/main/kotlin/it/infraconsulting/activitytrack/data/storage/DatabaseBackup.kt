/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.storage

import android.database.sqlite.SQLiteDatabase
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Consistent database snapshot without closing Room (A16, DESIGN §8.2):
 * VACUUM INTO a cache file, zipped with a manifest.json.
 */
class DatabaseBackup(
	private val db: ActivityTrackDatabase,
	private val cacheDir: File,
) {
	data class Manifest(val appVersion: String, val dbVersion: Int, val createdUtc: Instant, val attachments: Int) {
		fun toJson(): String =
			"{\n" +
				"  \"app\": \"ActivityTrack\",\n" +
				"  \"appVersion\": \"${appVersion.replace("\"", "")}\",\n" +
				"  \"dbVersion\": $dbVersion,\n" +
				"  \"createdUtc\": \"$createdUtc\",\n" +
				"  \"attachments\": $attachments\n" +
				"}\n"
	}

	/* Snapshot + zip into [out]; returns the manifest written. */
	suspend fun writeZip(appVersion: String, now: Instant, out: OutputStream): Manifest = withContext(Dispatchers.IO) {
		val snapshot = File(cacheDir, "snapshot.db")
		snapshot.delete()
		try {
			/* VACUUM cannot run inside a transaction; Room's writer connection is used directly */
			db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf<Any?>(snapshot.absolutePath))
			val manifest = Manifest(
				appVersion = appVersion,
				dbVersion = db.openHelper.readableDatabase.version,
				createdUtc = now,
				attachments = db.entryDao().attachmentTotal(),
			)
			ZipOutputStream(out).use { zip ->
				zip.putNextEntry(ZipEntry(ActivityTrackDatabase.FILE_NAME))
				snapshot.inputStream().use { it.copyTo(zip) }
				zip.closeEntry()
				zip.putNextEntry(ZipEntry("manifest.json"))
				zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
				zip.closeEntry()
			}
			manifest
		} finally {
			snapshot.delete()
		}
	}

	/* A checked backup database waiting in the cache to replace the live one. */
	data class Staged(val file: File, val dbVersion: Int, val createdUtc: String, val appVersion: String)

	class InvalidBackup(reason: String) : Exception(reason)

	/*
	 * Unpacks a backup zip into the cache and checks it: manifest, SQLite
	 * header, integrity, schema not newer than this app. The caller closes
	 * the database, swaps the file and restarts (Room migrates older ones).
	 */
	suspend fun stage(input: InputStream, currentVersion: Int): Staged = withContext(Dispatchers.IO) {
		val target = File(cacheDir, "restore.db")
		target.delete()
		var manifest: JSONObject? = null
		ZipInputStream(input).use { zip ->
			while (true) {
				val e = zip.nextEntry ?: break
				when (e.name) {
					ActivityTrackDatabase.FILE_NAME -> target.outputStream().use { zip.copyTo(it) }
					"manifest.json" -> manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
				}
			}
		}
		val m = manifest ?: throw InvalidBackup("no manifest.json")
		if (!target.exists())
			throw InvalidBackup("no database in the backup")
		val header = target.inputStream().use { it.readNBytes(16) }
		if (!header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
			throw InvalidBackup("not a SQLite database")
		val version = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
			sql.rawQuery("PRAGMA integrity_check", null).use { c ->
				c.moveToFirst()
				if (c.getString(0) != "ok")
					throw InvalidBackup("damaged database: ${c.getString(0)}")
			}
			sql.version
		}
		if (version > currentVersion)
			throw InvalidBackup("backup of a newer app version (database $version > $currentVersion)")
		Staged(target, version, m.optString("createdUtc"), m.optString("appVersion"))
	}

	/*
	 * Closes Room and puts [staged] in place of [dbFile] (WAL and SHM of the
	 * old database removed). The process must restart afterwards.
	 */
	fun swapIn(dbFile: File, staged: Staged) {
		db.close()
		File(dbFile.path + "-wal").delete()
		File(dbFile.path + "-shm").delete()
		staged.file.copyTo(dbFile, overwrite = true)
		staged.file.delete()
	}

	companion object {
		const val PREFIX = "activitytrack-"
		const val SUFFIX = ".zip"
		private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

		fun fileName(now: Instant, zone: ZoneId): String = PREFIX + STAMP.format(now.atZone(zone)) + SUFFIX

		/* Backups beyond the newest [keep] (names sort by time). */
		fun expired(names: List<String>, keep: Int): List<String> =
			names.filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }.sortedDescending().drop(keep)
	}
}
