/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.track

import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/* The formats the track export writes (issue #155). */
enum class TrackFormat(val extension: String, val mimeType: String) {
	GPX("gpx", "application/gpx+xml"),
	KML("kml", "application/vnd.google-earth.kml+xml"),
}

internal object Xml {
	fun escape(s: String): String = buildString(s.length) {
		for (c in s) when (c) {
			'&' -> append("&amp;")
			'<' -> append("&lt;")
			'>' -> append("&gt;")
			'"' -> append("&quot;")
			'\'' -> append("&apos;")
			/* characters XML 1.0 cannot carry */
			else -> if (c >= ' ' || c == '\t' || c == '\n' || c == '\r') append(c)
		}
	}

	fun instant(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

	/* 7 decimals are about 1 cm; Locale.ROOT keeps the dot */
	fun degrees(v: Double): String = String.format(Locale.ROOT, "%.7f", v)

	fun km(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
}

/*
 * The text of an entry, shared by both formats: what was done, for whom, where
 * and when. Times are the recorded ones (no rounding, which is for the reports).
 */
internal object TrackText {
	fun name(e: TrackEntry): String = e.activity?.takeIf { it.isNotBlank() } ?: e.client ?: e.company ?: "ActivityTrack"

	/* label/value pairs, empty values left out */
	fun facts(e: TrackEntry): List<Pair<String, String>> {
		val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(e.zone)
		val from = e.startUtcMs?.let { fmt.format(Instant.ofEpochMilli(it)) }
		val to = e.endUtcMs?.let { fmt.format(Instant.ofEpochMilli(it)) }
		return listOf(
			"Company" to e.company,
			"Client" to e.client,
			"Role" to e.role,
			"Activity" to e.activity,
			"Locality" to e.locality,
			"Tags" to e.tags.joinToString(", ").ifEmpty { null },
			"Start" to from,
			"End" to to,
			"Zone" to e.zoneId,
			"Km" to e.km?.let { Xml.km(it) },
		).mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }
	}
}

/* GPX 1.1: a track per entry, a segment per recorded segment, a waypoint at the first point. */
object GpxWriter {
	const val NAMESPACE = "https://www.infraconsulting.it/activitytrack/gpx/1"

	fun write(entries: List<TrackEntry>, title: String, generatedAtMs: Long, generator: String): String = buildString {
		append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
		append("<gpx version=\"1.1\" creator=\"").append(Xml.escape(generator))
		append("\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:at=\"").append(NAMESPACE).append("\"")
		append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
		append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
		append("<metadata><name>").append(Xml.escape(title)).append("</name><time>")
		append(Xml.instant(generatedAtMs)).append("</time></metadata>\n")
		for (e in entries) {
			val first = e.points.firstOrNull() ?: continue
			/* waypoint: element order of the schema is ele, time, name, desc, type, extensions */
			append("<wpt lat=\"").append(Xml.degrees(first.lat)).append("\" lon=\"").append(Xml.degrees(first.lon)).append("\">")
			append("<time>").append(Xml.instant(first.tsUtcMs)).append("</time>")
			append("<name>").append(Xml.escape(TrackText.name(e))).append("</name>")
			append("<desc>").append(Xml.escape(TrackText.facts(e).joinToString("\n") { (k, v) -> "$k: $v" })).append("</desc>")
			append("</wpt>\n")
		}
		for (e in entries) {
			if (e.points.isEmpty())
				continue
			append("<trk><name>").append(Xml.escape(TrackText.name(e))).append("</name>")
			append("<desc>").append(Xml.escape(TrackText.facts(e).joinToString("\n") { (k, v) -> "$k: $v" })).append("</desc>")
			append("<extensions>")
			ext("entry", e.entryId.toString())
			ext("company", e.company)
			ext("client", e.client)
			ext("role", e.role)
			ext("activity", e.activity)
			ext("locality", e.locality)
			ext("tags", e.tags.joinToString(",").ifEmpty { null })
			ext("zone", e.zoneId)
			ext("km", e.km?.let { Xml.km(it) })
			append("</extensions>\n")
			for (s in e.segments) {
				if (s.points.isEmpty())
					continue
				append("<trkseg>\n")
				for (p in s.points) {
					append("<trkpt lat=\"").append(Xml.degrees(p.lat)).append("\" lon=\"").append(Xml.degrees(p.lon)).append("\">")
					append("<time>").append(Xml.instant(p.tsUtcMs)).append("</time>")
					p.accuracyM?.let { append("<extensions><at:accuracy>").append(String.format(Locale.ROOT, "%.1f", it)).append("</at:accuracy></extensions>") }
					append("</trkpt>\n")
				}
				/* trksegType: the points, then the extensions */
				append("<extensions>")
				ext("kind", s.kind?.name)
				ext("km", s.km?.let { Xml.km(it) })
				append("</extensions>\n</trkseg>\n")
			}
			append("</trk>\n")
		}
		append("</gpx>\n")
	}

	private fun StringBuilder.ext(name: String, value: String?) {
		if (value.isNullOrBlank())
			return
		append("<at:").append(name).append(">").append(Xml.escape(value)).append("</at:").append(name).append(">")
	}
}

/*
 * KML 2.2 as Google Earth Pro and the other viewers read it: a folder per day,
 * a placemark per segment with a LineString (a Point for a single sample), a
 * TimeSpan for the time slider, the facts in a balloon text and in ExtendedData.
 * Day and TimeSpan are those of the exported samples: an activity running for
 * days shows each day's points on that day, not from its start.
 */
object KmlWriter {
	/* KML colours are aabbggrr */
	private const val WORK_COLOR = "ff327d2e"		/* green */
	private const val TRAVEL_COLOR = "ffc06515"		/* blue */
	private const val OTHER_COLOR = "ff808080"

	fun write(entries: List<TrackEntry>, title: String): String = buildString {
		append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
		append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
		append("<name>").append(Xml.escape(title)).append("</name>\n")
		style("work", WORK_COLOR)
		style("travel", TRAVEL_COLOR)
		style("other", OTHER_COLOR)
		val parts = entries.flatMap { e ->
			e.segments.filter { it.points.isNotEmpty() }.map { s ->
				Triple(Instant.ofEpochMilli(s.points.first().tsUtcMs).atZone(e.zone).toLocalDate(), e, s)
			}
		}
		for ((day, list) in parts.groupBy { it.first }.toSortedMap()) {
			append("<Folder><name>").append(day.toString()).append("</name>\n")
			for ((_, e, s) in list.sortedBy { it.third.points.first().tsUtcMs })
				placemark(e, s)
			append("</Folder>\n")
		}
		append("</Document>\n</kml>\n")
	}

	private fun StringBuilder.style(id: String, color: String) {
		append("<Style id=\"").append(id).append("\"><LineStyle><color>").append(color).append("</color><width>4</width></LineStyle>")
		append("<IconStyle><color>").append(color).append("</color></IconStyle></Style>\n")
	}

	private fun StringBuilder.placemark(e: TrackEntry, s: TrackSegment) {
		if (s.points.isEmpty())
			return
		val kind = when (s.kind) {
			SegmentKind.WORK -> "work"
			SegmentKind.TRAVEL -> "travel"
			else -> "other"
		}
		val label = TrackText.name(e) + if (s.kind == SegmentKind.TRAVEL) " (travel)" else ""
		append("<Placemark><name>").append(Xml.escape(label)).append("</name>")
		append("<description>").append(Xml.escape(balloon(e, s))).append("</description>")
		append("<styleUrl>#").append(kind).append("</styleUrl>")
		append("<TimeSpan><begin>").append(Xml.instant(s.points.first().tsUtcMs)).append("</begin><end>")
		append(Xml.instant(s.points.last().tsUtcMs)).append("</end></TimeSpan>")
		append("<ExtendedData>")
		data("entry", e.entryId.toString())
		data("kind", s.kind?.name)
		for ((k, v) in TrackText.facts(e))
			data(k.lowercase(Locale.ROOT), v)
		data("segmentKm", s.km?.let { Xml.km(it) })
		data("points", s.points.size.toString())
		append("</ExtendedData>")
		if (s.points.size == 1) {
			val p = s.points.first()
			append("<Point><coordinates>").append(coord(p)).append("</coordinates></Point>")
		} else {
			append("<LineString><tessellate>1</tessellate><coordinates>")
			s.points.joinTo(this, " ") { coord(it) }
			append("</coordinates></LineString>")
		}
		append("</Placemark>\n")
	}

	/* lon,lat,altitude: KML wants the longitude first */
	private fun coord(p: TrackPoint) = Xml.degrees(p.lon) + "," + Xml.degrees(p.lat) + ",0"

	private fun StringBuilder.data(name: String, value: String?) {
		if (value.isNullOrBlank())
			return
		append("<Data name=\"").append(Xml.escape(name)).append("\"><value>").append(Xml.escape(value)).append("</value></Data>")
	}

	/* Earth shows a description as HTML: user text is escaped once for HTML, and the whole once more for XML. */
	private fun balloon(e: TrackEntry, s: TrackSegment): String {
		val rows = TrackText.facts(e).joinToString("<br/>") { (k, v) -> "<b>${Xml.escape(k)}</b>: ${Xml.escape(v)}" }
		val seg = s.kind?.let { "<br/><i>${it.name}</i>" } ?: ""
		return rows + seg
	}
}
