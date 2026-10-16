/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * User data directory chosen through SAF (decision A6, DESIGN §8.1).
 * Layout: <root>/attachments, <root>/backup, <root>/export.
 * Uses DocumentsContract directly, without androidx.documentfile.
 */
class UserDataDir(context: Context) {
	private val resolver = context.applicationContext.contentResolver

	enum class Sub(val dirName: String) {
		ATTACHMENTS("attachments"),
		BACKUP("backup"),
		EXPORT("export"),
	}

	/* Keeps read/write access to [tree] across reboots. */
	fun persist(tree: Uri) {
		resolver.takePersistableUriPermission(tree, RW)
	}

	fun release(tree: Uri) {
		runCatching { resolver.releasePersistableUriPermission(tree, RW) }
	}

	/* False when the permission was revoked or the tree was never persisted. */
	fun isAccessible(tree: Uri): Boolean =
		resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }

	/* Creates the missing sub-directories; returns their document URIs. */
	suspend fun ensureLayout(tree: Uri): Map<Sub, Uri> = withContext(Dispatchers.IO) {
		val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		Sub.entries.associateWith { sub ->
			findChild(tree, root, sub.dirName)
				?: DocumentsContract.createDocument(resolver, root, Document.MIME_TYPE_DIR, sub.dirName)
				?: error("cannot create ${sub.dirName}")
		}
	}

	/* Directory child of [parent] named [name], if any. */
	private fun findChild(tree: Uri, parent: Uri, name: String): Uri? {
		val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
		val proj = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
		resolver.query(children, proj, null, null, null)?.use { c ->
			while (c.moveToNext()) {
				if (c.getString(1) == name && c.getString(2) == Document.MIME_TYPE_DIR)
					return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
			}
		}
		return null
	}

	private companion object {
		const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
	}
}
