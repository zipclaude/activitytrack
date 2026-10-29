/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.sync

import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import it.infraconsulting.activitytrack.data.sync.SyncFiles
import java.io.IOException
import java.time.LocalDate

/*
 * Attachment files in the user data folder for the sync (issue #116): read
 * by their relative path, downloads written where the app writes its own
 * (attachments/<yyyy>/<entryId>/<uid>.<ext>).
 */
class AttachmentFiles(private val app: ActivityTrackApp) : SyncFiles {
	private val dir = app.container.userDataDir

	private suspend fun tree() = app.container.settings.current().rootUri?.toUri()?.takeIf(dir::isAccessible)
		?: throw IOException("data folder not accessible")

	override suspend fun read(relPath: String): ByteArray? {
		val uri = dir.resolve(tree(), relPath) ?: return null
		return dir.openInput(uri).use { it.readBytes() }
	}

	override suspend fun write(entryId: Long, uid: String, mime: String, bytes: ByteArray): String {
		val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
		val path = listOf(UserDataDir.Sub.ATTACHMENTS.dirName, LocalDate.now().year.toString(), entryId.toString())
		val name = "$uid.$ext"
		val target = dir.createAt(tree(), path, name, mime)
		dir.openOutput(target).use { it.write(bytes) }
		return (path + name).joinToString("/")
	}
}
