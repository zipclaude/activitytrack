/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.storage

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
