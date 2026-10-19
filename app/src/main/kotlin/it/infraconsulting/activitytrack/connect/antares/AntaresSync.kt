/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.antares

import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.data.db.RemoteActivityEntity
import it.infraconsulting.activitytrack.data.db.RemoteActivityTypeEntity
import it.infraconsulting.activitytrack.data.db.RemotePortfolioEntity
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.domain.interchange.AntaresChunk
import it.infraconsulting.activitytrack.domain.interchange.AntaresConnector
import it.infraconsulting.activitytrack.domain.interchange.AntaresPlan
import it.infraconsulting.activitytrack.domain.interchange.PlanSegment
import it.infraconsulting.activitytrack.domain.report.DayAttributionPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/* What a sync did, for the report shown to the user. */
data class AntaresSyncReport(
	val portfolios: Int = 0,
	val tickets: Int = 0,
	val sent: Int = 0,
	val updated: Int = 0,
	val deleted: Int = 0,
	/* entry title → why it was not sent */
	val skipped: List<Pair<String, String>> = emptyList(),
	val errors: List<String> = emptyList(),
)

/*
 * "Sync" with Antares for one company (owner, 2026-10-19):
 * 1. services and projects (active ones accept activities) and their
 *    enabled activity types;
 * 2. the open tickets assigned to the logged-in person become to-dos
 *    (external key = ticket Id, reference = ticket code); closed ones are
 *    marked done;
 * 3. closed entries with Antares data are sent: one activity per report
 *    day and block (AntaresPlan), POST when new, PUT when changed since
 *    the last send (hash of the payload), DELETE for blocks gone.
 * Stops at the first API error of a phase; what was done stays recorded.
 */
class AntaresSync(private val app: ActivityTrackApp) {
	private val c get() = app.container
	private val dao get() = c.remote

	suspend fun sync(companyId: Long): AntaresSyncReport = lock.withLock {
		val company = c.masterData.company(companyId) ?: error("company $companyId")
		val config = AntaresConfig.of(company.connectorConfig)
		val api = AntaresApi(app.antares, companyId, config)
		val json = runCatching { JSONObject(company.connectorConfig ?: "{}") }.getOrDefault(JSONObject())
		val person = json.optString(AntaresConnector.PERSON_ID).ifEmpty { api.me().first }
		var report = AntaresSyncReport()

		/* 1. portfolios */
		val active = HashMap<String, Set<Long>>()
		suspend fun activeValues(type: String): Set<Long> = active.getOrPut(type) {
			val a = api.list("/v3/Enums", mapOf("\$filter" to "Type eq '$type'"))
			(0 until a.length()).map { a.getJSONObject(it) }
				.filter { !it.isNull("EntityState") }.map { it.getLong("Value") }.toSet()
		}
		val portfolios = ArrayList<RemotePortfolioEntity>()
		val serviceStates = activeValues("ServicePortfolioState")
		val lifecycles = activeValues("ServicePortfolioLifecycle")
		for (o in pages(api, "/v3/ServicePortfolios", "Id,Name,CustomerId,State,Lifecycle", "Name"))
			portfolios += RemotePortfolioEntity(
				o.getString("Id"), companyId, SERVICE, o.optString("Name"), o.optStringOrNull("CustomerId"),
				o.optLong("State") in serviceStates && o.optLong("Lifecycle") in lifecycles,
			)
		val projectStates = activeValues("ProjectStatus")
		for (o in pages(api, "/v3/Projects", "Id,Name,CustomerId,Status", "Name"))
			portfolios += RemotePortfolioEntity(
				o.getString("Id"), companyId, PROJECT, o.optString("Name"), o.optStringOrNull("CustomerId"),
				o.optLong("Status") in projectStates,
			)
		dao.replacePortfolios(companyId, portfolios)
		for (p in portfolios.filter { it.active }) {
			val path = (if (p.kind == SERVICE) "/v3/ServicePortfolios/" else "/v3/Projects/") + p.id + "/ActivityTypes"
			val types = api.list(path).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
			dao.replaceTypes(p.id, types.map {
				RemoteActivityTypeEntity(p.id, it.getString("ActivityTypeId"), it.optString("ActivityTypeName"), it.optBoolean("Included"), it.optInt("Order"))
			})
		}
		report = report.copy(portfolios = portfolios.count { it.active })

		/* 2. tickets assigned to me */
		val ticketStates = activeValues("TicketState")
		val clients = dao.clientsOf(companyId)
		val clientOf = clients.filter { it.externalCode != null }.associateBy { it.externalCode!!.lowercase() }
		var tickets = 0
		for (o in pages(api, "/v3/Tickets", "Id,Code,Name,Description,State,PortfolioId,CustomerId,Priority", "LastUpdateDate desc",
			"TechnicianId eq $person")) {
			val id = o.getString("Id")
			val open = o.optLong("State") in ticketStates
			val existing = dao.todoByKey(companyId, id)
			if (existing == null && !open)
				continue
			val now = System.currentTimeMillis()
			val clientId = o.optStringOrNull("CustomerId")?.let { clientOf[it.lowercase()]?.id }
			val base = existing ?: TodoEntity(uid = UUID.randomUUID().toString(), companyId = companyId, title = "", createdUtcMs = now, updatedUtcMs = now)
			val t = base.copy(
				externalKey = id,
				title = o.optString("Name").ifBlank { o.optString("Code") },
				notes = o.optStringOrNull("Description"),
				refs = o.optString("Code"),
				clientId = clientId ?: base.clientId,
				priority = o.optLong("Priority").toInt().takeIf { it > 0 },
				remotePortfolioId = o.optStringOrNull("PortfolioId"),
				done = if (open) base.done else true,
				updatedUtcMs = now,
			)
			if (t != existing) dao.saveTodo(t)
			tickets++
		}
		report = report.copy(tickets = tickets)

		/* 3. closed entries */
		val s = c.settings.current()
		val zoneOf = { id: String -> runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault()) }
		val split = Duration.ofMinutes(json.optLong(PAUSE_SPLIT, 60).coerceAtLeast(1))
		val policy = DayAttributionPolicy(LocalTime.of(s.carryOverMin / 60, s.carryOverMin % 60))
		for (e in dao.entriesToSync(companyId)) {
			val title = e.clientId?.let { id -> clients.firstOrNull { it.id == id }?.name } ?: company.name
			val todo = e.todoId?.let { dao.todo(it) }
			val ticket = todo?.takeIf { it.companyId == companyId && it.remotePortfolioId != null }?.externalKey
			val portfolio = e.remotePortfolioId?.let { dao.portfolio(it) }
			val type = e.remoteActivityTypeId
			val why = when {
				type == null -> "activity type"
				ticket == null && portfolio == null -> "service or project"
				else -> null
			}
			if (why != null) {
				report = report.copy(skipped = report.skipped + (title to why))
				continue
			}
			val zone = zoneOf(e.zoneId)
			val segs = dao.segments(e.id).filter { it.endUtcMs != null }.map {
				PlanSegment(it.kind, Instant.ofEpochMilli(it.startUtcMs), Instant.ofEpochMilli(it.endUtcMs!!), it.kmDeclared ?: it.kmEstimated)
			}
			val chunks = AntaresPlan.chunks(segs, zone, s.rounding, policy, split)
			val sent = dao.sent(e.id).associateBy { it.epochDay to it.idx }
			val included = e.remoteIncluded
				?: (ticket?.let { todo.remotePortfolioId } ?: portfolio?.id)?.let { pid -> dao.typeList(pid).firstOrNull { it.typeId == type }?.included }
				?: false
			for (ch in chunks) {
				val body = JSONObject()
					.put("RelationType", if (ticket != null) 2 else if (portfolio!!.kind == SERVICE) 1 else 5)
					.put("ActivityTypeId", type)
					.put("OwnerId", person)
					.put("FromDate", iso(ch.from, zone))
					.put("ToDate", iso(ch.to, zone))
					.put("TimeFormat", 1)
					.put("Status", 1)
					.put("Included", included)
					.put("ActivityReport", e.activity.orEmpty())
					.put("Note", e.notes.orEmpty())
					.put("TravelTimeMinutes", ch.travelMinutes)
					.put("TravelKm", ch.travelKm)
				if (ticket != null) body.put("TicketId", ticket) else body.put("PortfolioId", portfolio!!.id)
				val hash = sha(body.toString())
				val key = ch.day.toEpochDay() to ch.index
				val old = sent[key]
				if (old?.hash == hash)
					continue
				val saved = if (old == null) {
					report = report.copy(sent = report.sent + 1)
					api.post("/v3/Activities", body)
				} else {
					/* the current Timestamp guards against overwriting someone else's change (409) */
					val current = JSONObject(api.get("/v3/Activities/${old.remoteId}"))
					report = report.copy(updated = report.updated + 1)
					api.put("/v3/Activities/${old.remoteId}", body.put("Id", old.remoteId).put("Timestamp", current.opt("Timestamp")))
				}
				dao.recordSent(RemoteActivityEntity(e.id, key.first, key.second, saved.getString("Id"), saved.optStringOrNull("Timestamp"), hash, System.currentTimeMillis()))
			}
			/* blocks that no longer exist (shorter pause, edited times) */
			val keep = chunks.map { it.day.toEpochDay() to it.index }.toSet()
			for ((k, old) in sent)
				if (k !in keep) {
					api.delete("/v3/Activities/${old.remoteId}")
					dao.forgetSent(e.id, k.first, k.second)
					report = report.copy(deleted = report.deleted + 1)
				}
		}
		report
	}

	/* A whole list, 200 rows a page, in a stable order (the API does not page by itself). */
	private suspend fun pages(api: AntaresApi, path: String, select: String, order: String, filter: String? = null): List<JSONObject> {
		val out = ArrayList<JSONObject>()
		var skip = 0
		while (true) {
			val q = linkedMapOf("\$select" to select, "\$orderby" to order, "\$top" to "$PAGE", "\$skip" to "$skip")
			filter?.let { q["\$filter"] = it }
			val a: JSONArray = api.list(path, q)
			for (i in 0 until a.length())
				out += a.getJSONObject(i)
			if (a.length() < PAGE)
				return out
			skip += PAGE
		}
	}

	private fun iso(t: Instant, zone: ZoneId): String = ISO.format(t.atZone(zone).toOffsetDateTime())

	private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

	private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

	companion object {
		const val SERVICE = "SERVICE"
		const val PROJECT = "PROJECT"
		/* company setting: a pause longer than this many minutes splits the activity (default 60) */
		const val PAUSE_SPLIT = "pause_split_min"
		private const val PAGE = 200
		private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
		private val lock = Mutex()
	}
}
