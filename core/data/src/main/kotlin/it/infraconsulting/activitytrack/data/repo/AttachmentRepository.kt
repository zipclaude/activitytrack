/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.AttachmentEntity
import it.infraconsulting.activitytrack.data.db.AttachmentKind
import it.infraconsulting.activitytrack.data.storage.UserDataDir
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/*
 * Attachments (SPEC §4, DESIGN §8.1): files live in the user data folder at
 * attachments/<yyyy>/<entryId>/<uuid>.<ext>; the database keeps the path
 * relative to the root, so the folder can move with its contents.
 */
class AttachmentRepository(
	private val db: ActivityTrackDatabase,
	private val dir: UserDataDir,
	private val clock: Clock,
) {
	private val dao = db.entryDao()

	fun observe(entryId: Long) = dao.observeAttachments(entryId)

	/* Copies [source] (camera/recorder capture, picked file) into the folder and records it. */
	suspend fun add(tree: Uri, entryId: Long, kind: AttachmentKind, source: Uri, mime: String, ext: String): Long {
		val path = listOf(UserDataDir.Sub.ATTACHMENTS.dirName, LocalDate.now(clock).year.toString(), entryId.toString())
		val name = "${UUID.randomUUID()}.$ext"
		val target = dir.createAt(tree, path, name, mime)
		try {
			dir.openInput(source).use { input -> dir.openOutput(target).use { input.copyTo(it) } }
		} catch (e: Exception) {
			dir.delete(target)
			throw e
		}
		return db.withTransaction {
			val id = dao.insertAttachment(
				AttachmentEntity(
					entryId = entryId,
					kind = kind,
					relPath = (path + name).joinToString("/"),
					mime = mime,
					createdUtcMs = clock.millis(),
				),
			)
			/* completion rules look at attachments: signal the change */
			dao.entry(entryId)?.let { dao.updateEntry(it.copy(updatedUtcMs = clock.millis())) }
			id
		}
	}

	/* Document URI to open the file; null when it was removed outside the app. */
	suspend fun uriOf(tree: Uri, a: AttachmentEntity): Uri? = dir.resolve(tree, a.relPath)

	/* Removes the row and the file (an expense keeps its row, without receipt). */
	suspend fun delete(tree: Uri, a: AttachmentEntity) {
		dao.deleteAttachment(a.id)
		try {
			dir.resolve(tree, a.relPath)?.let { dir.delete(it) }
		} catch (e: Exception) {
			Log.w("AttachmentRepository", "file of attachment ${a.id} not removed", e)
		}
	}
}
