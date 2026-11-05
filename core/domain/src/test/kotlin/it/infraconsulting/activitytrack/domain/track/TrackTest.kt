/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.track

import it.infraconsulting.activitytrack.domain.geo.Geo
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackTest {
	private val t0 = Instant.parse("2026-11-03T08:00:00Z").toEpochMilli()

	private fun pt(i: Int, lat: Double, lon: Double, acc: Double? = 12.5) = TrackPoint(t0 + i * 60_000L, lat, lon, acc)

	private val entry = TrackEntry(
		entryId = 7,
		zoneId = "Europe/Rome",
		activity = "Visita <cliente> & \"collaudo\"",
		company = "Acme",
		client = "Rossi S.r.l.",
		role = "Tecnico",
		locality = "Torino",
		tags = listOf("urgente", "sede"),
		segments = listOf(
			TrackSegment(SegmentKind.TRAVEL, t0, t0 + 120_000, 11.5, null, listOf(pt(0, 45.0, 7.0), pt(1, 45.001, 7.001), pt(2, 45.002, 7.002))),
			TrackSegment(SegmentKind.WORK, t0 + 120_000, t0 + 600_000, null, null, listOf(pt(2, 45.002, 7.002, null))),
		),
	)

	private fun parse(xml: String) =
		DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
			.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

	@Test
	fun declaredKmWinOverEstimated() {
		assertEquals(3.0, TrackSegment(null, null, null, 2.0, 3.0, emptyList()).km)
		assertEquals(2.0, TrackSegment(null, null, null, 2.0, null, emptyList()).km)
	}

	@Test
	fun entrySpanAndDay() {
		assertEquals(t0, entry.startUtcMs)
		assertEquals(t0 + 600_000, entry.endUtcMs)
		assertEquals("2026-11-03", entry.day.toString())
		assertEquals(11.5, entry.km)
	}

	@Test
	fun gpxIsWellFormedAndKeepsEveryPoint() {
		val doc = parse(GpxWriter.write(listOf(entry), "Test", t0, "ActivityTrack 1"))
		assertEquals(4, doc.getElementsByTagName("trkpt").length)
		assertEquals(2, doc.getElementsByTagName("trkseg").length)
		assertEquals(1, doc.getElementsByTagName("trk").length)
		assertEquals(1, doc.getElementsByTagName("wpt").length)
		val first = doc.getElementsByTagName("trkpt").item(0)
		assertEquals("45.0000000", first.attributes.getNamedItem("lat").nodeValue)
		assertEquals("7.0000000", first.attributes.getNamedItem("lon").nodeValue)
		/* the accuracy travels as recorded, none where it was not */
		assertEquals(3, doc.getElementsByTagNameNS(GpxWriter.NAMESPACE, "accuracy").length)
		assertEquals("12.5", doc.getElementsByTagNameNS(GpxWriter.NAMESPACE, "accuracy").item(0).textContent)
	}

	@Test
	fun gpxCarriesTheActivityAndEscapesIt() {
		val xml = GpxWriter.write(listOf(entry), "Test", t0, "x")
		val doc = parse(xml)
		val name = doc.getElementsByTagName("name").let { (0 until it.length).map { i -> it.item(i).textContent } }
		assertTrue("Visita <cliente> & \"collaudo\"" in name)
		assertTrue("<cliente>" !in xml)
		assertEquals("Rossi S.r.l.", doc.getElementsByTagNameNS(GpxWriter.NAMESPACE, "client").item(0).textContent)
		assertEquals("TRAVEL", doc.getElementsByTagNameNS(GpxWriter.NAMESPACE, "kind").item(0).textContent)
		assertTrue("<time>2026-11-03T08:00:00Z</time>" in xml)
	}

	@Test
	fun gpxSegmentExtensionsComeAfterThePoints() {
		val seg = parse(GpxWriter.write(listOf(entry), "T", t0, "x")).getElementsByTagName("trkseg").item(0)
		assertEquals("extensions", seg.lastChild.let { if (it.nodeType == 3.toShort()) it.previousSibling else it }.nodeName)
	}

	@Test
	fun kmlPutsLongitudeFirstAndFilesByDay() {
		val xml = KmlWriter.write(listOf(entry), "Test")
		val doc = parse(xml)
		assertEquals("2026-11-03", doc.getElementsByTagName("Folder").item(0).firstChild.textContent)
		val line = doc.getElementsByTagName("coordinates").item(0).textContent.trim().split(" ")
		assertEquals("7.0000000,45.0000000,0", line.first())
		assertEquals(3, line.size)
		/* a single sample is a Point */
		assertEquals(1, doc.getElementsByTagName("Point").length)
		assertEquals(1, doc.getElementsByTagName("LineString").length)
		assertEquals(2, doc.getElementsByTagName("TimeSpan").length)
		assertTrue("#travel" in xml && "#work" in xml)
	}

	@Test
	fun kmlFilesAnActivityOfManyDaysByItsSamples() {
		/* the activity began on 1 November, the samples are of the 3rd and of the 4th */
		val day = 24 * 3_600_000L
		val long = entry.copy(
			segments = listOf(
				TrackSegment(SegmentKind.WORK, t0 - 2 * day, null, null, null, listOf(pt(0, 45.0, 7.0))),
				TrackSegment(SegmentKind.WORK, t0 - 2 * day, null, null, null, listOf(TrackPoint(t0 + day, 45.1, 7.1, 5.0), TrackPoint(t0 + day + 60_000, 45.2, 7.2, 5.0))),
			),
		)
		val doc = parse(KmlWriter.write(listOf(long), "T"))
		val folders = doc.getElementsByTagName("Folder")
		assertEquals(listOf("2026-11-03", "2026-11-04"), (0 until folders.length).map { folders.item(it).firstChild.textContent })
		val spans = doc.getElementsByTagName("begin")
		assertEquals("2026-11-03T08:00:00Z", spans.item(0).textContent)
		assertEquals("2026-11-04T08:00:00Z", spans.item(1).textContent)
	}

	@Test
	fun kmlBalloonIsEscapedTwice() {
		val desc = parse(KmlWriter.write(listOf(entry), "T")).getElementsByTagName("description").item(0).textContent
		/* as parsed it is HTML: markup of ours, text of the user escaped */
		assertTrue("<b>Client</b>: Rossi S.r.l." in desc)
		assertTrue("Visita &lt;cliente&gt; &amp; &quot;collaudo&quot;" in desc)
	}

	@Test
	fun entriesWithoutPointsAreLeftOut() {
		val empty = entry.copy(segments = listOf(TrackSegment(SegmentKind.WORK, t0, t0, null, null, emptyList())))
		assertEquals(0, parse(GpxWriter.write(listOf(empty), "T", t0, "x")).getElementsByTagName("trk").length)
		assertEquals(0, parse(KmlWriter.write(listOf(empty), "T")).getElementsByTagName("Placemark").length)
	}

	@Test
	fun controlCharactersAreDropped() {
		assertEquals("ab", Xml.escape("a\u0000b"))
	}

	@Test
	fun simplifyKeepsEndsAndStaysWithinTolerance() {
		/* a dog-leg with a dense straight part: the corner must stay, the collinear points go */
		val pts = (0..100).map { pt(it, 45.0 + it * 0.0001, 7.0) } + (1..100).map { pt(100 + it, 45.01, 7.0 + it * 0.0001) }
		val s = TrackGeometry.simplify(pts, 5.0)
		assertEquals(3, s.size)
		assertEquals(pts.first(), s.first())
		assertEquals(pts.last(), s.last())
		assertEquals(pts[100], s[1])
		for (p in pts)
			assertTrue(s.zipWithNext().minOf { (a, b) -> distanceToSegment(p, a, b) } <= 5.5)
	}

	@Test
	fun simplifyOfShortOrZeroToleranceIsIdentity() {
		val pts = listOf(pt(0, 45.0, 7.0), pt(1, 45.1, 7.1))
		assertEquals(pts, TrackGeometry.simplify(pts, 10.0))
		val three = pts + pt(2, 45.2, 7.0)
		assertEquals(three, TrackGeometry.simplify(three, 0.0))
	}

	@Test
	fun lengthIsTheSumOfLegs() {
		val a = pt(0, 45.0, 7.0)
		val b = pt(1, 45.0, 7.01)
		assertEquals(2 * Geo.distanceM(a.point, b.point), TrackGeometry.lengthM(listOf(a, b, a)), 1e-6)
	}

	@Test
	fun nearestPicksTheClosestEntryWithinTolerance() {
		val far = entry.copy(entryId = 8, segments = listOf(TrackSegment(SegmentKind.WORK, t0, t0, null, null, listOf(pt(0, 46.0, 8.0)))))
		val tap = GeoPoint(45.0021, 7.0021)
		assertEquals(7L, TrackGeometry.nearest(listOf(far, entry), tap, 100.0)?.entryId)
		assertEquals(null, TrackGeometry.nearest(listOf(far, entry), GeoPoint(45.5, 7.5), 100.0))
	}

	/* planar distance in a local projection, enough for the test */
	private fun distanceToSegment(p: TrackPoint, a: TrackPoint, b: TrackPoint): Double {
		val k = Math.cos(Math.toRadians(p.lat))
		fun x(q: TrackPoint) = Math.toRadians(q.lon) * k * Geo.EARTH_RADIUS_M
		fun y(q: TrackPoint) = Math.toRadians(q.lat) * Geo.EARTH_RADIUS_M
		val dx = x(b) - x(a)
		val dy = y(b) - y(a)
		val l2 = dx * dx + dy * dy
		val t = if (l2 == 0.0) 0.0 else (((x(p) - x(a)) * dx + (y(p) - y(a)) * dy) / l2).coerceIn(0.0, 1.0)
		return Math.hypot(x(p) - x(a) - t * dx, y(p) - y(a) - t * dy)
	}
}
