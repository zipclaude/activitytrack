/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.sync

import it.infraconsulting.activitytrack.domain.sync.WireVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/* The server, as the engine sees it: paths of api/v1, the device token added by the implementation. */
interface SyncTransport {
	class Reply(val status: Int, val body: String)

	suspend fun send(method: String, path: String, body: String? = null): Reply

	/* PUT of a file (issue #116); the answer is JSON */
	suspend fun upload(path: String, bytes: ByteArray, mime: String, sha256: String): Reply

	/* GET of a file: its bytes, null with the status when the server has none */
	suspend fun download(path: String): Pair<Int, ByteArray?>
}

/* The attachments' files in the data folder (issue #116); absent without a data folder. */
interface SyncFiles {
	suspend fun read(relPath: String): ByteArray?

	/* stores a downloaded file of an attachment of entry [entryId]; returns its relative path */
	suspend fun write(entryId: Long, uid: String, mime: String, bytes: ByteArray): String
}

/* A transaction on the database, with the sync's view of it. */
interface SyncDb {
	suspend fun <T> run(block: (SyncStore) -> T): T
}

/* the server revoked this phone (401): the sync stops until it is paired again */
class SyncRevoked : Exception("revoked")

class SyncFailed(message: String) : Exception(message)

/* the server does not speak this app's schema (426, SYNC.md §12): [code] says which side to update */
class SyncIncompatible(val code: String) : Exception(code) {
	companion object {
		fun of(body: String): SyncIncompatible = SyncIncompatible(
			runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: WireVersion.APP_TOO_OLD,
		)
	}
}

/*
 * One sync with ActivityTrack Web (issue #114, server SYNC.md §5, §7): the
 * first time a full pull, adoption and the push of everything the phone
 * has; then push what changed here and pull what changed there. Network
 * calls happen between transactions, never inside one.
 */
class SyncEngine(
	private val db: SyncDb,
	private val server: SyncTransport,
	private val now: () -> Long = System::currentTimeMillis,
	private val files: SyncFiles? = null,
	/* files are downloaded now (unmetered network, or asked for) */
	private val downloads: Boolean = false,
) {
	class Result(val pushed: Int, val pulled: Int, val errors: List<String>)

	private val json = Json { ignoreUnknownKeys = true }

	suspend fun sync(): Result {
		val errors = ArrayList<String>()
		var offset = 0L
		try {
			var pulled = 0
			db.run { it.clearErrors() }
			if (!db.run { it.state().enabled }) {
				db.run { it.enable() }
				pull(errors) { offset = it }.also { pulled += it }
				db.run { it.seed() }
			}
			var pushed = 0
			/* a pull may log changes of its own (adoption): they go out in the same sync */
			for (round in 1..3) {
				val out = push(errors) { offset = it }
				val came = pull(errors) { offset = it }
				pushed += out
				pulled += came
				/* a merge may leave overlapping segments: the mark travels too */
				db.run { it.review() }
				/* only refused changes left: another round would send them again */
				if (db.run { it.pending(1).isEmpty() } || (out == 0 && came == 0)) break
			}
			files?.let { transferFiles(it, errors) }
			db.run { it.finished(offset, errors.firstOrNull()) }
			return Result(pushed, pulled, errors)
		} catch (e: SyncRevoked) {
			db.run { it.failed("revoked") }
			throw e
		} catch (e: SyncIncompatible) {
			db.run { it.failed("incompatible: ${e.code}") }
			throw e
		} catch (e: SyncFailed) {
			db.run { it.failed(e.message ?: "error") }
			throw e
		}
	}

	private suspend fun call(method: String, path: String, body: String? = null): JsonObject {
		val reply = server.send(method, path, body)
		if (reply.status == 401) throw SyncRevoked()
		if (reply.status == 426) throw SyncIncompatible.of(reply.body)
		if (reply.status != 200) throw SyncFailed("HTTP ${reply.status} on $path")
		return runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: throw SyncFailed("bad answer on $path")
	}

	private fun offsetOf(answer: JsonObject): Long? = answer["serverTime"]?.jsonPrimitive?.longOrNull?.let { it - now() }

	/* pushes every pending change, 500 at a time; returns how many the server took */
	private suspend fun push(errors: MutableList<String>, offset: (Long) -> Unit): Int {
		var taken = 0
		repeat(MAX_ROUNDS) {
			val batch = db.run { store ->
				store.pending(BATCH).mapNotNull { p ->
					val change = store.change(p)
					if (change == null) store.skip(p)
					change?.let { p to it }
				}
			}
			if (batch.isEmpty()) return taken
			val body = JsonObject(mapOf("deviceTime" to JsonPrimitive(now()), "changes" to JsonArray(batch.map { it.second })))
			val answer = call("POST", "sync/push", body.toString())
			offsetOf(answer)?.let(offset)
			val results = answer["results"]?.jsonArray ?: throw SyncFailed("push answer without results")
			if (results.size != batch.size) throw SyncFailed("push answer: ${results.size} results for ${batch.size} changes")
			val failed = db.run { store -> batch.zip(results).mapNotNull { (b, r) -> store.pushed(b.first, r.jsonObject) } }
			errors += failed
			taken += batch.size - failed.size
			/* only errors left: they stay in the log for the next sync */
			if (failed.size == batch.size) return taken
		}
		return taken
	}

	/* pulls everything after the cursor; returns how many records came */
	private suspend fun pull(errors: MutableList<String>, offset: (Long) -> Unit): Int {
		var count = 0
		repeat(MAX_ROUNDS * 20) {
			val cursor = db.run { it.state().cursor }
			val answer = call("GET", "sync/pull?cursor=$cursor&limit=$BATCH")
			offsetOf(answer)?.let(offset)
			val records = answer["records"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
			val gone = answer["gone"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
			val next = answer["cursor"]?.jsonPrimitive?.longOrNull ?: throw SyncFailed("pull answer without cursor")
			db.run { store ->
				/* a record may point at one later in the page: retry while that helps */
				var left = records
				var failures = emptyList<String>()
				while (left.isNotEmpty()) {
					val failedNow = left.mapNotNull { r -> store.pulled(r)?.let { r to it } }
					if (failedNow.size == left.size) {
						failures = failedNow.map { it.second }
						break
					}
					left = failedNow.map { it.first }
				}
				errors += failures
				for (g in gone) {
					val type = g["type"]?.jsonPrimitive?.contentOrNull ?: continue
					val uid = g["uid"]?.jsonPrimitive?.contentOrNull ?: continue
					store.gone(type, uid)
				}
				store.setCursor(next)
			}
			count += records.size
			if (answer["more"]?.jsonPrimitive?.booleanOrNull != true) return count
		}
		return count
	}

	/* uploads the files the server lacks, then (when allowed) downloads the ones this phone lacks */
	private suspend fun transferFiles(files: SyncFiles, errors: MutableList<String>) {
		val maxBytes = (runCatching { call("GET", "sync/head")["filesMaxMb"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 25L) * 1024 * 1024
		for (f in db.run { it.filesToUpload() }) {
			val bytes = files.read(f.relPath)
			if (bytes == null) {
				errors += "attachment ${f.uid}: file missing here"
				db.run { it.recordError("attachment", f.uid, "file missing here") }
				continue
			}
			if (bytes.size > maxBytes) {
				errors += "attachment ${f.uid}: larger than the server accepts"
				db.run { it.recordError("attachment", f.uid, "larger than the server accepts (${maxBytes / 1024 / 1024} MB)") }
				continue
			}
			val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
			val reply = server.upload("files/${f.uid}", bytes, f.mime, sha)
			if (reply.status == 401) throw SyncRevoked()
			if (reply.status == 426) throw SyncIncompatible.of(reply.body)
			if (reply.status != 200) {
				errors += "attachment ${f.uid}: upload HTTP ${reply.status}"
				db.run { it.recordError("attachment", f.uid, "upload HTTP ${reply.status}") }
				continue
			}
			val record = runCatching { json.parseToJsonElement(reply.body).jsonObject["record"]?.jsonObject }.getOrNull() ?: continue
			db.run { it.fileUploaded(record) }
		}
		if (!downloads) return
		var budget = MAX_DOWNLOAD
		for (f in db.run { it.filesToDownload() }) {
			if ((f.size ?: 0) > budget) continue
			val (status, bytes) = server.download("files/${f.uid}")
			if (status == 401) throw SyncRevoked()
			if (status == 426) throw SyncIncompatible(WireVersion.APP_TOO_OLD)
			if (bytes == null) {
				errors += "attachment ${f.uid}: download HTTP $status"
				continue
			}
			budget -= bytes.size
			val path = files.write(f.entryId, f.uid, f.mime, bytes)
			db.run { it.fileDownloaded(f.uid, path) }
		}
	}

	private companion object {
		/* per sync, so a phone that just paired does not fetch everything at once */
		const val MAX_DOWNLOAD = 200L * 1024 * 1024
		const val BATCH = 500
		const val MAX_ROUNDS = 50
	}
}
