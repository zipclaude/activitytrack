/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryInvariants
import it.infraconsulting.activitytrack.domain.model.EntryState
import it.infraconsulting.activitytrack.domain.model.EntryStateMachine
import it.infraconsulting.activitytrack.domain.model.InvariantViolationException
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentSource
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.start.KnownSite
import it.infraconsulting.activitytrack.domain.start.StartDefaults
import it.infraconsulting.activitytrack.domain.start.StartProposal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/* Data for a new entry; only the client is required, except for a TRAVEL start. */
data class StartRequest(
	val mode: SegmentKind,
	val clientId: Long?,
	val companyId: Long? = null,
	val roleId: Long? = null,
	val activity: String? = null,
	val locality: String? = null,
	val locationKind: LocationKind? = null,
)

/* Defaults proposed when a client is picked. */
data class ClientDefaults(val activity: String?, val roleId: Long?)

/* Descriptive fields of an entry, edited in the completion form. */
data class EntryDetails(
	val companyId: Long?,
	val clientId: Long?,
	val roleId: Long?,
	val activity: String?,
	val locality: String?,
	val locationKind: LocationKind?,
	val notes: String?,
)

/*
 * Single writer of time entries and segments. Every operation runs in one
 * Room transaction and verifies, before committing, the invariants of
 * DESIGN §4: state/segment consistency and non-overlap (EntryInvariants),
 * updated_utc_ms bump, and entry company == client company.
 */
class EntryRepository(
	private val db: ActivityTrackDatabase,
	private val clock: Clock,
) {
	private val dao = db.entryDao()
	private val master = db.masterDataDao()

	val openEntries = dao.observeOpenEntries()
	val openEntrySegments = dao.observeOpenEntrySegments()
	val clientLastUse = dao.observeClientLastUse()

	fun entry(id: Long) = dao.observeEntry(id)
	fun segments(entryId: Long) = dao.observeSegments(entryId)

	/*
	 * Pre-fill for a new entry (SPEC §2.1). [position] stays null until
	 * location tracking exists (M2): then today's latest company is proposed.
	 */
	suspend fun startProposal(sites: List<KnownSite>, position: GeoPoint? = null): StartProposal {
		val startOfToday = LocalDate.now(clock).atStartOfDay(clock.zone).toInstant().toEpochMilli()
		val previous = dao.lastWithCompanySince(startOfToday)?.companyId
		return StartDefaults.propose(position, sites, previous)
	}

	/* Activity and role of the last entry on [clientId] in the last 30 days. */
	suspend fun clientDefaults(clientId: Long): ClientDefaults? {
		val since = clock.millis() - DEFAULTS_WINDOW.toMillis()
		return dao.lastOnClient(clientId, since)?.let { ClientDefaults(it.activity, it.roleId) }
	}

	/* Suspended entry to propose for resumption after [stoppedId] was stopped. */
	suspend fun suspendedToResume(stoppedId: Long): TimeEntryEntity? = dao.mostRecentSuspended(stoppedId)

	/*
	 * Start a new entry and, in the same transaction, apply the commands chosen
	 * for the entries already ACTIVE (STOP or SUSPEND; absent = leave running).
	 */
	suspend fun start(req: StartRequest, others: Map<Long, EntryCommand> = emptyMap()): Long =
		db.withTransaction {
			others.forEach { (id, cmd) -> apply(id, cmd) }
			startEntry(req)
		}

	private suspend fun startEntry(req: StartRequest): Long {
		if (req.clientId == null && req.mode != SegmentKind.TRAVEL)
			throw ValidationException(Reason.CLIENT_REQUIRED)
		val companyId = companyFor(req.companyId, req.clientId)
		val t = EntryStateMachine.start(req.mode)
		val now = clock.millis()
		val zone = clock.zone.id
		val id = dao.startEntry(
			TimeEntryEntity(
				companyId = companyId,
				clientId = req.clientId,
				roleId = req.roleId,
				activity = req.activity?.trim()?.ifEmpty { null },
				status = t.next.status,
				mode = t.next.mode,
				locality = req.locality?.trim()?.ifEmpty { null },
				locationKind = req.locationKind,
				zoneId = zone,
				lastConfirmedUtcMs = now,
				createdUtcMs = now,
				updatedUtcMs = now,
			),
			SegmentEntity(entryId = 0, kind = req.mode, startUtcMs = now, zoneId = zone),
		)
		verify(id)
		return id
	}

	/* State-machine command; COMPLETE goes through [complete]. */
	suspend fun apply(entryId: Long, cmd: EntryCommand) {
		require(cmd != EntryCommand.COMPLETE) { "use complete()" }
		db.withTransaction {
			val e = load(entryId)
			val t = EntryStateMachine.apply(EntryState(e.status, e.mode), cmd)
			val at = writeInstant(entryId)
			if (t.closeOpenSegment)
				check(dao.closeOpenSegment(entryId, at) == 1) { "entry $entryId: no open segment to close" }
			t.openSegment?.let {
				dao.insertSegment(SegmentEntity(entryId = entryId, kind = it, startUtcMs = at, zoneId = clock.zone.id))
			}
			val resumed = t.openSegment != null
			dao.updateEntry(
				e.copy(
					status = t.next.status,
					mode = t.next.mode,
					lastConfirmedUtcMs = if (resumed) at else e.lastConfirmedUtcMs,
					unansweredReminders = if (resumed) 0 else e.unansweredReminders,
					updatedUtcMs = at,
				),
			)
			verify(entryId)
		}
	}

	/* "Still working?" answered yes. */
	suspend fun confirm(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		val now = clock.millis()
		dao.updateEntry(e.copy(lastConfirmedUtcMs = now, unansweredReminders = 0, updatedUtcMs = now))
	}

	/*
	 * STOP backdated to the last confirmation ("stop when I last said yes").
	 * The open segment ends at max(open start, last confirmation), never in
	 * the future; without a confirmation it behaves as a plain STOP.
	 */
	suspend fun stopAtLastConfirmation(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		val t = EntryStateMachine.apply(EntryState(e.status, e.mode), EntryCommand.STOP)
		val now = clock.millis()
		val at = minOf(e.lastConfirmedUtcMs ?: now, now)
		if (t.closeOpenSegment)
			check(dao.closeOpenSegment(entryId, at) == 1) { "entry $entryId: no open segment to close" }
		dao.updateEntry(e.copy(status = t.next.status, mode = t.next.mode, updatedUtcMs = now))
		verify(entryId)
	}

	/* A confirmation reminder about [entryId] was posted and not answered yet. */
	suspend fun markReminded(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		dao.updateEntry(e.copy(unansweredReminders = e.unansweredReminders + 1, updatedUtcMs = clock.millis()))
	}

	/*
	 * A pause reminder was posted: the next one counts from now (lunch-break
	 * postponement can then never repeat at the same instant).
	 */
	suspend fun markPauseReminded(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		val now = clock.millis()
		dao.updateEntry(
			e.copy(lastConfirmedUtcMs = now, unansweredReminders = e.unansweredReminders + 1, updatedUtcMs = now),
		)
	}

	suspend fun updateDetails(entryId: Long, d: EntryDetails) = db.withTransaction {
		val e = load(entryId)
		dao.updateEntry(
			e.copy(
				companyId = companyFor(d.companyId, d.clientId),
				clientId = d.clientId,
				roleId = d.roleId,
				activity = d.activity?.trim()?.ifEmpty { null },
				locality = d.locality?.trim()?.ifEmpty { null },
				locationKind = d.locationKind,
				notes = d.notes?.trim()?.ifEmpty { null },
				updatedUtcMs = clock.millis(),
			),
		)
		verify(entryId)
	}

	/*
	 * TO_COMPLETE -> CLOSED. Requires a client and at least an activity text
	 * (typed or dictated) or one attachment (voice note, photo, file).
	 */
	suspend fun complete(entryId: Long, d: EntryDetails) = db.withTransaction {
		updateDetails(entryId, d)
		val e = load(entryId)
		if (e.clientId == null)
			throw ValidationException(Reason.CLIENT_REQUIRED)
		if (e.activity == null && dao.attachmentCount(entryId) == 0)
			throw ValidationException(Reason.COMPLETION_INCOMPLETE)
		val t = EntryStateMachine.apply(EntryState(e.status, e.mode), EntryCommand.COMPLETE)
		dao.updateEntry(e.copy(status = t.next.status, mode = t.next.mode, updatedUtcMs = clock.millis()))
		verify(entryId)
	}

	/* Manual TRAVEL or PAUSE segment added afterwards; must lie in the past and not overlap. */
	suspend fun addManualSegment(
		entryId: Long,
		kind: SegmentKind,
		startUtcMs: Long,
		endUtcMs: Long,
		kmDeclared: Double? = null,
	): Long = db.withTransaction {
		if (kind == SegmentKind.WORK || startUtcMs >= endUtcMs || endUtcMs > clock.millis() ||
			(kmDeclared != null && (kmDeclared.isNaN() || kmDeclared < 0)) ||
			(kmDeclared != null && kind != SegmentKind.TRAVEL))
			throw ValidationException(Reason.INVALID_SEGMENT)
		val e = load(entryId)
		val id = dao.insertSegment(
			SegmentEntity(
				entryId = entryId,
				kind = kind,
				startUtcMs = startUtcMs,
				endUtcMs = endUtcMs,
				zoneId = clock.zone.id,
				source = SegmentSource.MANUAL,
				kmDeclared = kmDeclared,
			),
		)
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
		try {
			verify(entryId)
		} catch (x: InvariantViolationException) {
			throw ValidationException(Reason.OVERLAPPING_SEGMENTS, x.message)
		}
		id
	}

	private companion object {
		val DEFAULTS_WINDOW: Duration = Duration.ofDays(30)
	}

	private suspend fun load(entryId: Long): TimeEntryEntity =
		dao.entry(entryId) ?: throw ValidationException(Reason.NOT_FOUND, "entry $entryId")

	/* Company of an entry: the client's company; an explicit different company is rejected. */
	private suspend fun companyFor(companyId: Long?, clientId: Long?): Long? {
		if (clientId == null)
			return companyId
		val client = master.client(clientId) ?: throw ValidationException(Reason.NOT_FOUND, "client $clientId")
		if (companyId != null && companyId != client.companyId)
			throw ValidationException(Reason.CLIENT_COMPANY_MISMATCH)
		return client.companyId
	}

	/*
	 * Instant for segment transitions: now, but never before the latest
	 * recorded boundary, so a backwards clock change cannot create overlaps.
	 */
	private suspend fun writeInstant(entryId: Long): Long {
		val latest = dao.segments(entryId).maxOfOrNull { maxOf(it.startUtcMs, it.endUtcMs ?: it.startUtcMs) }
		return maxOf(clock.millis(), latest ?: Long.MIN_VALUE)
	}

	private suspend fun verify(entryId: Long) {
		val e = load(entryId)
		val segs = dao.segments(entryId).map {
			Segment(it.kind, Instant.ofEpochMilli(it.startUtcMs), it.endUtcMs?.let(Instant::ofEpochMilli))
		}
		EntryInvariants.require(entryId, e.status, e.mode, segs)
		if (e.clientId != null)
			check(master.client(e.clientId)?.companyId == e.companyId) { "entry $entryId: company != client company" }
	}
}
