/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import it.infraconsulting.activitytrack.domain.model.EntryInvariants
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/* The sample files in docs/samples (device tests) stay readable and valid. */
class SamplesTest {
	private fun sample(name: String) = File("../../docs/samples/$name").inputStream().use { GenericConnector.read(FileFormat.XML, it) }

	@Test
	fun managementSystemSample() {
		val d = sample("gestionale-test.xml")
		assertEquals(2, d.clients.size)
		assertEquals(3, d.todos.size)
	}

	@Test
	fun agendaSampleEntriesAreValid() {
		val d = sample("agenda-test.xml")
		assertEquals(11, d.entries.size)
		for (e in d.entries) {
			val segs = e.segments.map { Segment(SegmentKind.valueOf(it.kind), Instant.parse(it.startUtc), it.endUtc?.let(Instant::parse)) }
			assertTrue(EntryInvariants.violations(EntryStatus.valueOf(e.status), null, segs).isEmpty(), e.key)
		}
	}
}
