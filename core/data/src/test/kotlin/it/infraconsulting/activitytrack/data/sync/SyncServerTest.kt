/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.sync

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.JdbcSql
import it.infraconsulting.activitytrack.data.db.SyncSchema
import it.infraconsulting.activitytrack.domain.sync.PairApi
import it.infraconsulting.activitytrack.domain.sync.PairRequest
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Against a real ActivityTrack Web (development server), skipped unless
 * ATWEB_URL (e.g. http://localhost:8765) and ATWEB_CODES (two fresh pairing
 * codes of one user, comma-separated) are set: a phone's data pushed by one
 * database and pulled into another, both ways.
 */
class SyncServerTest {
	private val url = System.getenv("ATWEB_URL")
	private val codes = System.getenv("ATWEB_CODES")?.split(',')

	private fun http(method: String, path: String, body: String?, token: String?): SyncTransport.Reply {
		val c = URL("$url/api/v1/$path").openConnection() as HttpURLConnection
		c.requestMethod = method
		token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
		if (body != null) {
			c.doOutput = true
			c.setRequestProperty("Content-Type", "application/json")
			c.outputStream.use { it.write(body.toByteArray()) }
		}
		val status = c.responseCode
		val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
		return SyncTransport.Reply(status, text)
	}

	private inner class Phone(code: String) {
		val c = JdbcSql.create(ActivityTrackDatabase.VERSION)
		val sql = JdbcSql(c).also { SyncSchema.onCreate(it) }
		val token = PairApi.reply(200, http("POST", "pair", PairApi.request(PairRequest(code, "jvm", "test", "0")), null).body).token
		val db = object : SyncDb {
			override suspend fun <T> run(block: (SyncStore) -> T): T {
				sql.exec("BEGIN")
				try {
					return block(SyncStore(sql)).also { sql.exec("COMMIT") }
				} catch (e: Throwable) {
					sql.exec("ROLLBACK")
					throw e
				}
			}
		}
		val transport = object : SyncTransport {
			override suspend fun send(method: String, path: String, body: String?) = http(method, path, body, token)

			override suspend fun upload(path: String, bytes: ByteArray, mime: String, sha256: String): SyncTransport.Reply {
				val c = URL("$url/api/v1/$path").openConnection() as HttpURLConnection
				c.requestMethod = "PUT"
				c.setRequestProperty("Authorization", "Bearer $token")
				c.setRequestProperty("Content-Type", mime)
				c.setRequestProperty("X-Sha256", sha256)
				c.doOutput = true
				c.outputStream.use { it.write(bytes) }
				val status = c.responseCode
				return SyncTransport.Reply(status, (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty())
			}

			override suspend fun download(path: String): Pair<Int, ByteArray?> {
				val c = URL("$url/api/v1/$path").openConnection() as HttpURLConnection
				c.setRequestProperty("Authorization", "Bearer $token")
				val status = c.responseCode
				return status to (if (status == 200) c.inputStream.readBytes() else null)
			}
		}
		val stored = HashMap<String, ByteArray>()
		val files = object : SyncFiles {
			override suspend fun read(relPath: String) = stored[relPath]

			override suspend fun write(entryId: Long, uid: String, mime: String, bytes: ByteArray) = "dl/$uid".also { stored[it] = bytes }
		}

		fun sync() = runBlocking { SyncEngine(db, transport, files = files, downloads = true).sync() }

		fun one(q: String, vararg args: Any?) = sql.rows(q, *args).firstOrNull()?.get(0)
	}

	@Test
	fun twoDatabasesThroughTheServer() {
		assumeTrue(url != null && codes != null && codes.size >= 2)
		val a = Phone(codes!![0])
		val acme = SyncIds.random()
		val entry = SyncIds.random()
		val segment = SyncIds.random()
		a.sql.exec("INSERT INTO company (name, archived, uid) VALUES ('Acme JVM', 0, ?)", acme)
		a.sql.exec(
			"INSERT INTO time_entry (uid, company_id, activity, status, mode, zone_id, unanswered_reminders, created_utc_ms, updated_utc_ms, plugin_fields) " +
				"VALUES (?, 1, 'Patching', 'ACTIVE', 'WORK', 'Europe/Rome', 0, 1761600000000, 1761600000000, '{\"service\":\"s1\"}')",
			entry,
		)
		a.sql.exec("INSERT INTO segment (entry_id, kind, start_utc_ms, zone_id, source, uid) VALUES (1, 'WORK', 1761600000000, 'Europe/Rome', 'TRACKED', ?)", segment)
		a.sql.exec("INSERT INTO entry_tag (entry_id, tag) VALUES (1, 'rete')")
		val receipt = SyncIds.random()
		a.stored["r.jpg"] = byteArrayOf(1, 2, 3, 4)
		a.sql.exec("INSERT INTO attachment (entry_id, kind, rel_path, mime, created_utc_ms, uid) VALUES (1, 'RECEIPT', 'r.jpg', 'image/jpeg', 1, ?)", receipt)
		val first = a.sync()
		assertEquals(emptyList(), first.errors)
		val b = Phone(codes[1])
		assertEquals(emptyList(), b.sync().errors)
		assertEquals("Patching", b.one("SELECT activity FROM time_entry WHERE uid = ?", entry))
		assertEquals("rete", b.one("SELECT tag FROM entry_tag"))
		assertEquals(segment, b.one("SELECT uid FROM segment"))
		assertEquals(listOf<Byte>(1, 2, 3, 4), b.stored.getValue(b.one("SELECT rel_path FROM attachment WHERE uid = ?", receipt) as String).toList())
		b.sql.exec("UPDATE segment SET end_utc_ms = 1761603600000 WHERE uid = ?", segment)
		b.sql.exec("UPDATE time_entry SET status = 'CLOSED', mode = NULL WHERE uid = ?", entry)
		assertEquals(emptyList(), b.sync().errors)
		assertEquals(emptyList(), a.sync().errors)
		assertEquals("CLOSED", a.one("SELECT status FROM time_entry WHERE uid = ?", entry))
		assertEquals(1761603600000L, a.one("SELECT end_utc_ms FROM segment WHERE uid = ?", segment))
		assertEquals(0L, a.one("SELECT COUNT(*) FROM sync_change"))
		assertEquals(0L, b.one("SELECT COUNT(*) FROM sync_change"))
	}
}
