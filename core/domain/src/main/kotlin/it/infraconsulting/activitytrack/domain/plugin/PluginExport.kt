/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import it.infraconsulting.activitytrack.domain.model.EntryTimeline
import it.infraconsulting.activitytrack.domain.model.Interval
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.report.DayAttribution
import it.infraconsulting.activitytrack.domain.report.DayAttributionPolicy
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* A closed segment of an entry, with the km of a travel. */
data class ExportSegment(val kind: SegmentKind, val start: Instant, val end: Instant, val km: Double? = null)

/*
 * One remote record of an entry (docs/PLUGINS.md §9). [key] is stable while
 * the entry's shape stays ("2026-10-19#0" for the first block of a day),
 * so an edit updates the record instead of replacing it. [kind] is set
 * for the "segment" grouping and for separate travel records.
 */
data class ExportBlock(
	val key: String,
	val day: LocalDate,
	val index: Int,
	val from: Instant,
	val to: Instant,
	val workMinutes: Long,
	val travelMinutes: Long,
	val travelKm: Long,
	val kind: SegmentKind? = null,
) {
	/* the block as @block sees it: instants in epoch ms, day ISO */
	fun asJson(): JsonObject = JsonObject(
		buildMap {
			put("key", JsonPrimitive(key))
			put("day", JsonPrimitive(day.toString()))
			put("index", JsonPrimitive(index))
			put("from", JsonPrimitive(from.toEpochMilli()))
			put("to", JsonPrimitive(to.toEpochMilli()))
			put("workMinutes", JsonPrimitive(workMinutes))
			put("travelMinutes", JsonPrimitive(travelMinutes))
			put("travelKm", JsonPrimitive(travelKm))
			kind?.let { put("kind", JsonPrimitive(it.name.lowercase())) }
		},
	)
}

/* A record already sent: its block key, remote id and the hash of the body sent. */
data class SentRecord(val key: String, val remoteId: String, val hash: String)

sealed interface ExportAction {
	data class Create(val block: ExportBlock, val body: JsonObject, val hash: String) : ExportAction
	data class Update(val block: ExportBlock, val body: JsonObject, val hash: String, val sent: SentRecord) : ExportAction
	data class Delete(val sent: SentRecord) : ExportAction
}

/*
 * Export side of the engine (issue #48 B4): an entry's segments become
 * blocks by the descriptor's grouping (AntaresPlan of PR #29, generalized),
 * blocks become bodies, and bodies against the records sent before become
 * create / update / delete actions; an unchanged body sends nothing.
 */
object PluginExport {
	fun blocks(
		group: Group,
		segments: List<ExportSegment>,
		zone: ZoneId,
		rounding: ReportRounding,
		policy: DayAttributionPolicy,
		splitPauseMin: Long?,
	): List<ExportBlock> {
		if (segments.isEmpty())
			return emptyList()
		return when (group.by) {
			GroupBy.SEGMENT -> segments.sortedBy { it.start }
				.filter { group.travel != TravelMode.IGNORE || it.kind != SegmentKind.TRAVEL }
				.mapIndexed { i, s ->
					val minutes = round(Duration.between(s.start, s.end), s.kind, group.round, rounding)
					ExportBlock(
						"seg#$i", s.start.atZone(zone).toLocalDate(), i, s.start, s.start + minutes,
						if (s.kind == SegmentKind.WORK) minutes.toMinutes() else 0,
						if (s.kind == SegmentKind.TRAVEL) minutes.toMinutes() else 0,
						if (s.kind == SegmentKind.TRAVEL) (s.km ?: 0.0).roundToLong() else 0, s.kind,
					)
				}
			GroupBy.ENTRY -> byDay(group, segments, zone, rounding, policy, null, wholeEntry = true)
			GroupBy.DAY -> byDay(group, segments, zone, rounding, policy, null, wholeEntry = false)
			GroupBy.DAY_BLOCK -> byDay(group, segments, zone, rounding, policy, splitPauseMin?.let(Duration::ofMinutes), wholeEntry = false)
		}
	}

	private fun round(d: Duration, kind: SegmentKind, rule: String, r: ReportRounding): Duration = when {
		rule == "none" -> d
		kind == SegmentKind.TRAVEL -> r.travel.apply(d)
		kind == SegmentKind.PAUSE -> r.pause.apply(d)
		else -> r.work.apply(d)
	}

	/*
	 * Work blocks per report day (night carry-over included), from the first
	 * work start, the pauses inside, the length rounded; a pause longer than
	 * [split] starts a new block. [wholeEntry]: one block for the whole entry.
	 * Travel to the nearest block of the day, or records of its own.
	 */
	private fun byDay(
		group: Group,
		segments: List<ExportSegment>,
		zone: ZoneId,
		rounding: ReportRounding,
		policy: DayAttributionPolicy,
		split: Duration?,
		wholeEntry: Boolean,
	): List<ExportBlock> {
		val timeline = EntryTimeline(0, segments.map { Segment(it.kind, it.start, it.end) })
		val parts = DayAttribution.attribute(timeline, zone, segments.maxOf { it.end }, policy)
		/* km of a travel go with its first part */
		val kmAt = segments.filter { it.kind == SegmentKind.TRAVEL }.associate { it.start to (it.km ?: 0.0) }
		val byDay = if (wholeEntry) mapOf(parts.minOf { it.day } to parts) else parts.groupBy { it.day }.toSortedMap()
		val out = ArrayList<ExportBlock>()
		for ((day, dayParts) in byDay) {
			val work = dayParts.filter { it.kind == SegmentKind.WORK }.map { it.interval }.sortedBy { it.start }
			val travel = dayParts.filter { it.kind == SegmentKind.TRAVEL }
			val blocks = ArrayList<MutableList<Interval>>()
			for (iv in work) {
				val last = blocks.lastOrNull()
				if (last == null || (split != null && Duration.between(last.last().end, iv.start) > split))
					blocks += mutableListOf(iv)
				else
					last += iv
			}
			val travelMs = LongArray(blocks.size)
			val km = DoubleArray(blocks.size)
			if (group.travel == TravelMode.NEAREST_BLOCK && blocks.isNotEmpty())
				for (t in travel) {
					val i = blocks.indices.minBy { b ->
						val from = blocks[b].first().start
						val to = blocks[b].last().end
						when {
							!t.interval.end.isAfter(from) -> Duration.between(t.interval.end, from).toMillis()
							!t.interval.start.isBefore(to) -> Duration.between(to, t.interval.start).toMillis()
							else -> 0L
						}
					}
					travelMs[i] += Duration.between(t.interval.start, t.interval.end).toMillis()
					km[i] += kmAt[t.interval.start] ?: 0.0
				}
			val key = if (wholeEntry) "entry" else day.toString()
			blocks.forEachIndexed { i, b ->
				val from = b.first().start
				val raw = Duration.between(from, b.last().end)
				val length = if (group.round == "none") raw else rounding.work.apply(raw)
				val tr = when {
					travelMs[i] == 0L -> Duration.ZERO
					group.round == "none" -> Duration.ofMillis(travelMs[i])
					else -> rounding.travel.apply(Duration.ofMillis(travelMs[i]))
				}
				out += ExportBlock(if (wholeEntry) key else "$key#$i", day, i, from, from + length, length.toMinutes(), tr.toMinutes(), km[i].roundToLong())
			}
			if (group.travel == TravelMode.SEPARATE)
				travel.sortedBy { it.interval.start }.forEachIndexed { i, t ->
					val raw = Duration.between(t.interval.start, t.interval.end)
					val length = if (group.round == "none") raw else rounding.travel.apply(raw)
					out += ExportBlock(
						"$key#travel#$i", day, i, t.interval.start, t.interval.start + length, 0, length.toMinutes(),
						(kmAt[t.interval.start] ?: 0.0).roundToLong(), SegmentKind.TRAVEL,
					)
				}
		}
		return out
	}

	/* The body of a record: every key of the descriptor; a null value leaves the key out. */
	fun body(spec: Map<String, Expr>, env: EvalEnv): JsonObject =
		JsonObject(spec.mapNotNull { (k, x) -> x.eval(null, env).takeIf { it !is JsonNull }?.let { k to it } }.toMap())

	fun hash(body: JsonElement): String =
		MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

	/* What to send: new blocks created, changed ones updated, vanished ones deleted. */
	fun plan(bodies: List<Pair<ExportBlock, JsonObject>>, sent: List<SentRecord>): List<ExportAction> {
		val old = sent.associateBy { it.key }
		val out = ArrayList<ExportAction>()
		for ((b, body) in bodies) {
			val h = hash(body)
			val s = old[b.key]
			when {
				s == null -> out += ExportAction.Create(b, body, h)
				s.hash != h -> out += ExportAction.Update(b, body, h, s)
			}
		}
		val keep = bodies.map { it.first.key }.toSet()
		sent.filter { it.key !in keep }.forEach { out += ExportAction.Delete(it) }
		return out
	}
}
