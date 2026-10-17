/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import it.infraconsulting.activitytrack.data.repo.EntryDetails
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.ui.ActionViewModel

/* One entry: details, segments, external references (M1 step 7). */
class EntryViewModel(
	val entryId: Long,
	private val entries: EntryRepository,
	master: MasterDataRepository,
) : ActionViewModel() {
	val entry = entries.entry(entryId).state(null)
	val segments = entries.segments(entryId).state(emptyList())
	val refs = entries.externalRefs(entryId).state(emptyList())
	val clients = master.clients.state(emptyList())
	val companies = master.companies.state(emptyList())
	val roles = master.roles.state(emptyList())
	val presets = master.presets.state(emptyList())

	fun save(d: EntryDetails, done: () -> Unit) = run(done) { entries.updateDetails(entryId, d) }
	fun complete(d: EntryDetails, done: () -> Unit) = run(done) { entries.complete(entryId, d) }
	fun reopen() = run { entries.apply(entryId, EntryCommand.REOPEN) }

	fun addSegment(kind: SegmentKind, startMs: Long, endMs: Long, km: Double?, done: () -> Unit) =
		run(done) { entries.addManualSegment(entryId, kind, startMs, endMs, km) }

	fun deleteSegment(segmentId: Long) = run { entries.deleteManualSegment(segmentId) }

	fun addRef(value: String, done: () -> Unit) = run(done) { entries.addExternalRef(entryId, value) }
	fun removeRef(refId: Long) = run { entries.removeExternalRef(entryId, refId) }
}
