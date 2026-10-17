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

	/*
	 * False when the permission was revoked, the tree was never persisted, or
	 * the folder is gone: a grant survives renaming or deleting the folder.
	 */
	fun isAccessible(tree: Uri): Boolean =
		resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission } &&
			exists(tree)

	/* The provider reports a missing document as null or as an exception, depending on the version. */
	private fun exists(tree: Uri): Boolean = runCatching {
		val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		resolver.query(root, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
	}.getOrDefault(false)

	/* Creates the missing sub-directories; returns their document URIs. */
	suspend fun ensureLayout(tree: Uri): Map<Sub, Uri> = withContext(Dispatchers.IO) {
		val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		Sub.entries.associateWith { sub ->
			findChild(tree, root, sub.dirName)
				?: DocumentsContract.createDocument(resolver, root, Document.MIME_TYPE_DIR, sub.dirName)
				?: error("cannot create ${sub.dirName}")
		}
	}

	/* A document in one of the sub-directories. */
	data class DocFile(val name: String, val uri: Uri)

	/* Creates [name] in [sub]; the provider may adjust the name if it already exists. */
	suspend fun createFile(tree: Uri, sub: Sub, name: String, mime: String): Uri = withContext(Dispatchers.IO) {
		val dir = ensureLayout(tree).getValue(sub)
		DocumentsContract.createDocument(resolver, dir, mime, name) ?: error("cannot create $name")
	}

	/* Truncating output stream on [uri]; the caller closes it. */
	suspend fun openOutput(uri: Uri): java.io.OutputStream = withContext(Dispatchers.IO) {
		resolver.openOutputStream(uri, "wt") ?: error("cannot open $uri")
	}

	suspend fun list(tree: Uri, sub: Sub): List<DocFile> = withContext(Dispatchers.IO) {
		val dir = ensureLayout(tree).getValue(sub)
		val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir))
		val proj = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
		val out = ArrayList<DocFile>()
		resolver.query(children, proj, null, null, null)?.use { c ->
			while (c.moveToNext())
				out += DocFile(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
		}
		out
	}

	suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
		DocumentsContract.deleteDocument(resolver, uri)
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
