/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.sync

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
}

/* A transaction on the database, with the sync's view of it. */
interface SyncDb {
	suspend fun <T> run(block: (SyncStore) -> T): T
}

/* the server revoked this phone (401): the sync stops until it is paired again */
class SyncRevoked : Exception("revoked")

class SyncFailed(message: String) : Exception(message)

/*
 * One sync with ActivityTrack Web (issue #114, server SYNC.md §5, §7): the
 * first time a full pull, adoption and the push of everything the phone
 * has; then push what changed here and pull what changed there. Network
 * calls happen between transactions, never inside one.
 */
class SyncEngine(private val db: SyncDb, private val server: SyncTransport, private val now: () -> Long = System::currentTimeMillis) {
	class Result(val pushed: Int, val pulled: Int, val errors: List<String>)

	private val json = Json { ignoreUnknownKeys = true }

	suspend fun sync(): Result {
		val errors = ArrayList<String>()
		var offset = 0L
		try {
			var pulled = 0
			if (!db.run { it.state().enabled }) {
				db.run { it.enable() }
				pull(errors) { offset = it }.also { pulled += it }
				db.run { it.seed() }
			}
			var pushed = 0
			/* a pull may log changes of its own (adoption): they go out in the same sync */
			for (round in 1..3) {
				pushed += push(errors) { offset = it }
				pulled += pull(errors) { offset = it }
				if (db.run { it.pending(1).isEmpty() }) break
			}
			db.run { it.finished(offset, errors.firstOrNull()) }
			return Result(pushed, pulled, errors)
		} catch (e: SyncRevoked) {
			db.run { it.failed("revoked") }
			throw e
		} catch (e: SyncFailed) {
			db.run { it.failed(e.message ?: "error") }
			throw e
		}
	}

	private suspend fun call(method: String, path: String, body: String? = null): JsonObject {
		val reply = server.send(method, path, body)
		if (reply.status == 401) throw SyncRevoked()
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

	private companion object {
		const val BATCH = 500
		const val MAX_ROUNDS = 50
	}
}
