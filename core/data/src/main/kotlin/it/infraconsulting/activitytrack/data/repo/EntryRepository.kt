/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.domain.model.SpanChange
import it.infraconsulting.activitytrack.domain.model.Span
import it.infraconsulting.activitytrack.domain.model.SegmentOverlap
import androidx.room.withTransaction
import it.infraconsulting.activitytrack.domain.tag.Tags
import it.infraconsulting.activitytrack.data.db.EntryTagEntity
import it.infraconsulting.activitytrack.data.db.RemoteDeletedEntity
import it.infraconsulting.activitytrack.data.db.AbsenceEntity
import it.infraconsulting.activitytrack.data.db.AbsenceKind
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.AttachmentEntity
import it.infraconsulting.activitytrack.data.db.ExpenseEntity
import it.infraconsulting.activitytrack.data.db.ExternalRefEntity
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.EntryInvariants
import it.infraconsulting.activitytrack.domain.model.EntryState
import it.infraconsulting.activitytrack.domain.model.EntryStateMachine
import it.infraconsulting.activitytrack.domain.model.EntryStatus
import it.infraconsulting.activitytrack.domain.model.ExternalRefClassifier
import it.infraconsulting.activitytrack.domain.model.InvariantViolationException
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentSource
import it.infraconsulting.activitytrack.domain.start.KnownSite
import it.infraconsulting.activitytrack.domain.start.StartDefaults
import it.infraconsulting.activitytrack.domain.start.StartProposal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first

/* Data for a new entry; only the client is required, except for a TRAVEL start. */
data class StartRequest(
	val mode: SegmentKind,
	val clientId: Long?,
	val companyId: Long? = null,
	val roleId: Long? = null,
	/* chosen start (M17 editable times); null = now */
	val startUtcMs: Long? = null,
	/*
	 * Chosen end (M22): in the past the entry starts and stops at once, as
	 * with Stop; in the future it is the planned end, asked about then.
	 */
	val endUtcMs: Long? = null,
	val activity: String? = null,
	val locality: String? = null,
	val locationKind: LocationKind? = null,
	/* started from a to-do (M4) */
	val todoId: Long? = null,
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
	/* a contact of the entry's client (issue #44) */
	val contactId: Long? = null,
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
	/* the choices a policy may change (issue #48): shortest segment, closing on Stop */
	private val behaviours: () -> it.infraconsulting.activitytrack.domain.policy.Behaviours = { it.infraconsulting.activitytrack.domain.policy.Behaviours() },
) {
	private fun minLengthMs(): Long = behaviours().minDurationSec * 1000L

	private val dao = db.entryDao()
	private val master = db.masterDataDao()

	val openEntries = dao.observeOpenEntries()
	val openEntrySegments = dao.observeOpenEntrySegments()
	val recentClosed = dao.observeRecentClosed(RECENT_CLOSED)
	val recentClosedSegments = dao.observeRecentClosedSegments(RECENT_CLOSED)
	val clientLastUse = dao.observeClientLastUse()

	/* the Home "Chiuse" tab (issue #59): entries ended after [fromUtcMs], newest end first */
	fun closedEntriesSince(fromUtcMs: Long, limit: Int) = dao.observeClosedSince(fromUtcMs, limit)
	fun closedSegmentsSince(fromUtcMs: Long, limit: Int) = dao.observeClosedSegmentsSince(fromUtcMs, limit)
	fun closedTagsSince(fromUtcMs: Long, limit: Int) = dao.observeClosedTagsSince(fromUtcMs, limit)

	fun entry(id: Long) = dao.observeEntry(id)
	suspend fun find(id: Long): TimeEntryEntity? = dao.entry(id)
	fun segments(entryId: Long) = dao.observeSegments(entryId)

	/*
	 * Pre-fill for a new entry (SPEC §2.1): the site containing [position],
	 * else today's latest company.
	 */
	suspend fun startProposal(sites: List<KnownSite>, position: GeoPoint? = null): StartProposal {
		val startOfToday = LocalDate.now(clock).atStartOfDay(clock.zone).toInstant().toEpochMilli()
		val previous = dao.lastWithCompanySince(startOfToday)?.companyId
		return StartDefaults.propose(position, sites, previous)
	}

	/* Activity text of the last entry on [clientId], however old (completion "copy last"). */
	suspend fun lastActivity(clientId: Long): String? = dao.lastOnClient(clientId, Long.MIN_VALUE)?.activity

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
			val id = startEntry(req)
			req.endUtcMs?.let { end -> if (end <= clock.millis()) applyAt(id, EntryCommand.STOP, end) }
			id
		}

	private suspend fun startEntry(req: StartRequest): Long {
		if (req.clientId == null && req.mode != SegmentKind.TRAVEL)
			throw ValidationException(Reason.CLIENT_REQUIRED)
		val companyId = companyFor(req.companyId, req.clientId)
		val t = EntryStateMachine.start(req.mode)
		val now = clock.millis()
		if (req.startUtcMs != null && req.startUtcMs > now)
			throw ValidationException(Reason.FUTURE_TIME)
		val start = req.startUtcMs ?: now
		if (req.endUtcMs != null && req.endUtcMs - start < minLengthMs())
			throw ValidationException(if (req.endUtcMs > start) Reason.TOO_SHORT else Reason.INVALID_TIME_RANGE)
		val zone = clock.zone.id
		val id = dao.startEntry(
			TimeEntryEntity(
				uid = UUID.randomUUID().toString(),
				todoId = req.todoId,
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
				plannedEndUtcMs = req.endUtcMs?.takeIf { it > now },
				createdUtcMs = now,
				updatedUtcMs = now,
			),
			SegmentEntity(entryId = 0, kind = req.mode, startUtcMs = start, zoneId = zone),
		)
		/* the to-do's tickets become the entry's external references */
		req.todoId?.let { db.todoDao().byId(it) }?.refs?.lines()?.filter(String::isNotBlank)?.forEach {
			dao.insertExternalRef(ExternalRefEntity(entryId = id, kind = ExternalRefClassifier.classify(it), value = it.trim()))
		}
		verify(id)
		return id
	}

	/*
	 * State-machine command; COMPLETE goes through [complete]. [atUtcMs]
	 * backdates the transition (detected departure or arrival), clamped
	 * between the latest recorded boundary and now.
	 */
	suspend fun apply(entryId: Long, cmd: EntryCommand, atUtcMs: Long? = null) {
		require(cmd != EntryCommand.COMPLETE) { "use complete()" }
		db.withTransaction {
			val e = load(entryId)
			val t = EntryStateMachine.apply(EntryState(e.status, e.mode), cmd)
			val at = writeInstant(entryId, atUtcMs)
			if (t.closeOpenSegment)
				check(dao.closeOpenSegment(entryId, at) == 1) { "entry $entryId: no open segment to close" }
			t.openSegment?.let {
				val segId = dao.insertSegment(SegmentEntity(entryId = entryId, kind = it, startUtcMs = at, zoneId = clock.zone.id))
				/* e.g. "travel from 10:05" tapped at 10:20: the fixes since 10:05 are part of the travel */
				if (atUtcMs != null)
					dao.retagSamples(entryId, segId, at)
			}
			val resumed = t.openSegment != null
			/*
			 * Stopped with client and activity already there: closed at once, no
			 * completion step (owner, 2026-10-19). From a to-do it still stops
			 * to complete, which asks whether the to-do is done.
			 */
			val next = if (behaviours().closeCompleteOnStop && t.next.status == EntryStatus.TO_COMPLETE && e.clientId != null && !e.activity.isNullOrBlank() && e.todoId == null)
				EntryStateMachine.apply(t.next, EntryCommand.COMPLETE).next
			else
				t.next
			dao.updateEntry(
				e.copy(
					status = next.status,
					mode = next.mode,
					lastConfirmedUtcMs = if (resumed) at else e.lastConfirmedUtcMs,
					unansweredReminders = if (resumed) 0 else e.unansweredReminders,
					updatedUtcMs = at,
				),
			)
			verify(entryId)
		}
	}

	/*
	 * A command at a time the user chose (M17 editable times, e.g. "stopped
	 * at 17:30"): not in the future and not before the running segment
	 * started. Unlike [apply], a wrong time is an error, never clamped.
	 */
	suspend fun applyAt(entryId: Long, cmd: EntryCommand, atUtcMs: Long) = db.withTransaction {
		if (atUtcMs > clock.millis())
			throw ValidationException(Reason.FUTURE_TIME)
		val latest = dao.segments(entryId).maxOfOrNull { maxOf(it.startUtcMs, it.endUtcMs ?: it.startUtcMs) }
		if (latest != null && atUtcMs < latest)
			throw ValidationException(Reason.BEFORE_SEGMENT)
		apply(entryId, cmd, atUtcMs)
	}

	/* Segments of every entry touching [fromUtcMs, toUtcMs): busy cells of the time grid (M18). */
	suspend fun segmentsTouching(fromUtcMs: Long, toUtcMs: Long): List<SegmentEntity> =
		dao.segmentsOfEntriesTouching(fromUtcMs, toUtcMs)

	/* The planned end was asked about (M22): it is not asked again. */
	suspend fun clearPlannedEnd(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		if (e.plannedEndUtcMs != null)
			dao.updateEntry(e.copy(plannedEndUtcMs = null))
	}

	/*
	 * A call during a running entry of another client (issue #28): the open
	 * work segment ends at [startUtcMs], a pause with [note] covers the call,
	 * work goes on from [endUtcMs]. Nothing if the entry changed in the
	 * meantime (not working, or a segment began after the call started).
	 */
	suspend fun insertCallPause(entryId: Long, startUtcMs: Long, endUtcMs: Long, note: String): Boolean = db.withTransaction {
		val e = load(entryId)
		val open = dao.segments(entryId).firstOrNull { it.endUtcMs == null }
		if (e.status != EntryStatus.ACTIVE || open == null || open.kind != SegmentKind.WORK ||
			open.startUtcMs >= startUtcMs || endUtcMs <= startUtcMs || endUtcMs > clock.millis())
			return@withTransaction false
		dao.updateSegment(open.copy(endUtcMs = startUtcMs))
		dao.insertSegment(SegmentEntity(entryId = entryId, kind = SegmentKind.PAUSE, startUtcMs = startUtcMs, endUtcMs = endUtcMs, zoneId = open.zoneId, note = note))
		dao.insertSegment(SegmentEntity(entryId = entryId, kind = SegmentKind.WORK, startUtcMs = endUtcMs, zoneId = open.zoneId))
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
		verify(entryId)
		true
	}

	/* The description of a pause (issue #28), editable whatever the editable-times setting. */
	suspend fun setSegmentNote(segmentId: Long, note: String?) = db.withTransaction {
		val s = dao.segment(segmentId) ?: throw ValidationException(Reason.NOT_FOUND)
		dao.updateSegment(s.copy(note = note?.trim()?.ifEmpty { null }))
		dao.updateEntry(load(s.entryId).let { it.copy(updatedUtcMs = clock.millis()) })
	}

	val absences = dao.observeAbsences()

	fun expenses(entryId: Long) = dao.observeExpenses(entryId)

	/*
	 * Expense in EUR (SPEC §4): positive amount, description required, an
	 * optional receipt attachment. Tied to the open segment when the entry
	 * is ACTIVE (e.g. fuel during a travel).
	 */
	suspend fun addExpense(entryId: Long, amountCents: Long, description: String, receiptId: Long? = null): Long =
		db.withTransaction {
			if (amountCents <= 0)
				throw ValidationException(Reason.INVALID_AMOUNT)
			val text = description.trim().ifEmpty { throw ValidationException(Reason.BLANK_VALUE) }
			val e = load(entryId)
			val open = if (e.status == EntryStatus.ACTIVE) dao.segments(entryId).lastOrNull { it.endUtcMs == null } else null
			val now = clock.millis()
			val id = dao.insertExpense(
				ExpenseEntity(
					entryId = entryId,
					segmentId = open?.id,
					amountCents = amountCents,
					description = text,
					attachmentId = receiptId,
					createdUtcMs = now,
				),
			)
			dao.updateEntry(e.copy(updatedUtcMs = now))
			id
		}

	suspend fun deleteExpense(expenseId: Long) = dao.deleteExpense(expenseId)

	suspend fun attachment(id: Long): AttachmentEntity? = dao.attachment(id)

	/*
	 * Absence of [date]: FULL_DAY without time, LATE_ENTRY / EARLY_EXIT with
	 * a local time; recording a kind again replaces it.
	 */
	suspend fun recordAbsence(date: LocalDate, kind: AbsenceKind, timeMin: Int?) = db.withTransaction {
		val valid = when (kind) {
			AbsenceKind.FULL_DAY -> timeMin == null
			AbsenceKind.LATE_ENTRY, AbsenceKind.EARLY_EXIT -> timeMin != null && timeMin in 0 until 24 * 60
		}
		if (!valid)
			throw ValidationException(Reason.INVALID_TIME)
		dao.deleteAbsence(date.toEpochDay(), kind)
		dao.insertAbsence(AbsenceEntity(dateEpochDay = date.toEpochDay(), kind = kind, timeMin = timeMin))
	}

	/*
	 * Days in [from, to] that need no day-start prompt: an entry started or
	 * an absence recorded on them; today too while an entry is still ACTIVE
	 * (e.g. started before midnight).
	 */
	suspend fun handledDays(from: LocalDate, to: LocalDate, zone: ZoneId): Set<LocalDate> {
		val out = HashSet<LocalDate>()
		dao.absencesBetween(from.toEpochDay(), to.toEpochDay()).forEach { out += LocalDate.ofEpochDay(it.dateEpochDay) }
		val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
		val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
		dao.entryStartsBetween(fromMs, toMs).forEach { out += Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
		val today = LocalDate.now(clock.withZone(zone))
		if (today in from..to && dao.observeOpenEntries().first().any { it.status == EntryStatus.ACTIVE })
			out += today
		return out
	}

	/*
	 * "Arrived?" at a client site during TRAVEL: the travelling entry takes
	 * that client (and its company) when it has none, then switches to WORK
	 * at the arrival time (owner decision 2026-10-17).
	 */
	suspend fun arriveAtClient(entryId: Long, clientId: Long, atUtcMs: Long) = db.withTransaction {
		val e = load(entryId)
		if (e.clientId == clientId)
			dao.updateEntry(e.copy(locationKind = LocationKind.CLIENT, updatedUtcMs = clock.millis()))
		if (e.clientId == null) {
			val client = master.client(clientId) ?: throw ValidationException(Reason.NOT_FOUND, "client $clientId")
			dao.updateEntry(
				e.copy(
					clientId = clientId,
					companyId = client.companyId,
					locationKind = LocationKind.CLIENT,
					updatedUtcMs = clock.millis(),
				),
			)
		}
		apply(entryId, EntryCommand.WORK, atUtcMs)
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
				/* only a contact of that client stays */
				contactId = d.contactId?.takeIf { id -> db.callDao().contact(id)?.clientId == d.clientId },
				updatedUtcMs = clock.millis(),
			),
		)
		verify(entryId)
	}

	/*
	 * TO_COMPLETE -> CLOSED. Requires a client; without activity text and
	 * attachments only when the user confirmed it ([allowEmpty]: the work
	 * is obvious from the client, owner decision 2026-10-17).
	 */
	suspend fun complete(entryId: Long, d: EntryDetails, allowEmpty: Boolean = false) = db.withTransaction {
		updateDetails(entryId, d)
		val e = load(entryId)
		if (e.clientId == null)
			throw ValidationException(Reason.CLIENT_REQUIRED)
		if (!allowEmpty && e.activity == null && dao.attachmentCount(entryId) == 0)
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
		/* the other segments it overlaps make room (issue #40), after the user confirmed */
		resolve: Boolean = false,
	): Long = db.withTransaction {
		/* under a minute is an error (owner, 2026-10-18) */
		if (startUtcMs < endUtcMs && endUtcMs - startUtcMs < minLengthMs())
			throw ValidationException(Reason.TOO_SHORT)
		if (kind == SegmentKind.WORK || startUtcMs >= endUtcMs || endUtcMs > clock.millis() ||
			(kmDeclared != null && (kmDeclared.isNaN() || kmDeclared < 0)) ||
			(kmDeclared != null && kind != SegmentKind.TRAVEL))
			throw ValidationException(Reason.INVALID_SEGMENT)
		val e = load(entryId)
		if (resolve)
			makeRoom(entryId, null, startUtcMs, endUtcMs)
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

	fun externalRefs(entryId: Long) = dao.observeExternalRefs(entryId)

	/* External reference; kind classified from the value (URL / UUID / TICKET / OTHER). */
	suspend fun addExternalRef(entryId: Long, value: String): Long = db.withTransaction {
		val v = value.trim().ifEmpty { throw ValidationException(Reason.BLANK_VALUE) }
		val e = load(entryId)
		val id = dao.insertExternalRef(ExternalRefEntity(entryId = entryId, kind = ExternalRefClassifier.classify(v), value = v))
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
		id
	}

	suspend fun removeExternalRef(entryId: Long, refId: Long) = db.withTransaction {
		val e = load(entryId)
		dao.deleteExternalRef(refId)
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
	}

	/*
	 * Segments are editable and deletable (backlog 11, revising A13): the
	 * values entered replace the recorded ones, nothing of the old times is
	 * kept (owner, 2026-10-19). The running segment and an entry's last
	 * segment cannot be removed; the running one cannot be edited.
	 */
	suspend fun deleteSegment(segmentId: Long) = db.withTransaction {
		val s = dao.segment(segmentId) ?: throw ValidationException(Reason.NOT_FOUND)
		if (s.endUtcMs == null || dao.segments(s.entryId).size <= 1)
			throw ValidationException(Reason.LAST_SEGMENT)
		val e = load(s.entryId)
		dao.deleteSegment(segmentId)
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
		verify(s.entryId)
	}

	suspend fun updateSegment(segmentId: Long, startUtcMs: Long, endUtcMs: Long, kmDeclared: Double? = null, resolve: Boolean = false) = db.withTransaction {
		val s = dao.segment(segmentId) ?: throw ValidationException(Reason.NOT_FOUND)
		if (s.endUtcMs == null)
			throw ValidationException(Reason.INVALID_SEGMENT, "running segment")
		if (startUtcMs < endUtcMs && endUtcMs - startUtcMs < minLengthMs())
			throw ValidationException(Reason.TOO_SHORT)
		if (startUtcMs >= endUtcMs || endUtcMs > clock.millis() ||
			(kmDeclared != null && (kmDeclared.isNaN() || kmDeclared < 0 || s.kind != SegmentKind.TRAVEL)))
			throw ValidationException(Reason.INVALID_SEGMENT)
		val e = load(s.entryId)
		if (resolve)
			makeRoom(s.entryId, s.id, startUtcMs, endUtcMs)
		dao.updateSegment(
			s.copy(
				startUtcMs = startUtcMs,
				endUtcMs = endUtcMs,
				kmDeclared = if (s.kind == SegmentKind.TRAVEL) kmDeclared else s.kmDeclared,
			),
		)
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
		try {
			verify(s.entryId)
		} catch (x: InvariantViolationException) {
			throw ValidationException(Reason.OVERLAPPING_SEGMENTS, x.message)
		}
	}

	/*
	 * Move the start of the running segment (issue #57), e.g. a restart
	 * recorded late; it must stay in the past. With [resolve] the segments
	 * it now covers are shortened or dropped (issue #40).
	 */
	suspend fun moveRunningStart(segmentId: Long, startUtcMs: Long, resolve: Boolean = false) = db.withTransaction {
		val s = dao.segment(segmentId) ?: throw ValidationException(Reason.NOT_FOUND)
		if (s.endUtcMs != null || startUtcMs >= clock.millis())
			throw ValidationException(Reason.INVALID_SEGMENT)
		val e = load(s.entryId)
		if (resolve)
			makeRoom(s.entryId, s.id, startUtcMs, RUNNING_END)
		dao.updateSegment(s.copy(startUtcMs = startUtcMs))
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
		try {
			verify(s.entryId)
		} catch (x: InvariantViolationException) {
			throw ValidationException(Reason.OVERLAPPING_SEGMENTS, x.message)
		}
	}

	/* Tags (backlog 13): the entry's tags, all tags in use (suggestions), replace the entry's. */
	fun tags(entryId: Long) = dao.observeTags(entryId)
	val allTags = dao.observeAllTags()

	suspend fun setTags(entryId: Long, tags: List<String>) = db.withTransaction {
		val e = load(entryId)
		dao.clearTags(entryId)
		dao.insertTags(tags.mapNotNull(Tags::normalize).distinct().map { EntryTagEntity(entryId, it) })
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
	}

	suspend fun tagsOf(entryIds: List<Long>): Map<Long, List<String>> =
		if (entryIds.isEmpty()) emptyMap()
		else dao.tagsOf(entryIds).groupBy({ it.entryId }, { it.tag }).mapValues { it.value.sorted() }

	/* Time recorded so far (open segments up to now). */
	suspend fun recordedMs(entryId: Long): Long {
		val now = clock.millis()
		return dao.segments(entryId).sumOf { maxOf(0L, (it.endUtcMs ?: now) - it.startUtcMs) }
	}

	/* Segments of the entry that [startUtcMs, endUtcMs) would overlap, [except] left out (issue #40). */
	suspend fun segmentConflicts(entryId: Long, except: Long?, startUtcMs: Long, endUtcMs: Long): List<SegmentEntity> {
		val segs = dao.segments(entryId).filter { it.id != except }
		val hit = SegmentOverlap.conflicts(segs.map { Span(it.id, it.startUtcMs, it.endUtcMs) }, startUtcMs, endUtcMs).map { it.id }.toSet()
		return segs.filter { it.id in hit }
	}

	/* The overlapped segments are shortened, split or dropped (SegmentOverlap). */
	private suspend fun makeRoom(entryId: Long, except: Long?, startUtcMs: Long, endUtcMs: Long) {
		val segs = dao.segments(entryId).filter { it.id != except }.associateBy { it.id }
		val changes = SegmentOverlap.resolve(segs.values.map { Span(it.id, it.startUtcMs, it.endUtcMs) }, startUtcMs, endUtcMs, MIN_LENGTH_MS)
		for (c in changes) {
			val o = segs.getValue(c.id)
			when (c) {
				is SpanChange.Delete -> dao.deleteSegment(o.id)
				is SpanChange.Resize -> dao.updateSegment(o.copy(startUtcMs = c.start, endUtcMs = c.end))
				is SpanChange.Split -> {
					dao.updateSegment(o.copy(endUtcMs = c.firstEnd))
					dao.insertSegment(o.copy(id = 0, startUtcMs = c.secondStart, kmDeclared = null, kmEstimated = null))
				}
			}
		}
	}

	/* What deleting an entry involves (issue #40): attachments block it; a ticket's to-do goes back to open; what a plugin sent is deleted there too. */
	data class Deletion(val attachments: Int, val ticket: String?, val sent: Boolean = false)

	suspend fun deletion(entryId: Long): Deletion {
		val e = load(entryId)
		val todo = e.todoId?.let { db.todoDao().byId(it) }?.takeIf { it.externalKey != null }
		return Deletion(dao.attachmentCount(entryId), todo?.title, db.pluginDao().sentAll(entryId).isNotEmpty())
	}

	/*
	 * Delete an entry with its segments, expenses, references and tags
	 * (owner, issue #40). With attachments it is refused: an entry with
	 * attachments is hardly a mistake, they are deleted by hand first. An
	 * entry from a ticket gives back its to-do, open again.
	 */
	suspend fun deleteEntry(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		if (dao.attachmentCount(entryId) > 0)
			throw ValidationException(Reason.HAS_ATTACHMENTS)
		e.todoId?.let { db.todoDao().byId(it) }?.takeIf { it.externalKey != null }?.let {
			db.todoDao().update(it.markedDone(false, clock.millis()))
		}
		keepRemoteIds(e)
		dao.deleteEntry(entryId)
	}

	/* what a plugin sent for the entry is deleted on the remote side at the next sync (issue #48) */
	private suspend fun keepRemoteIds(e: TimeEntryEntity) {
		val company = e.companyId ?: return
		for (r in db.pluginDao().sentAll(e.id))
			db.pluginDao().recordDeleted(RemoteDeletedEntity(company, r.pluginId, r.remoteId, clock.millis()))
	}

	/*
	 * An entry under one minute is a mistake (owner, 2026-10-18): stopping it
	 * from the app offers to discard it. Only without attachments or expenses,
	 * so nothing the user added is lost.
	 */
	suspend fun isDiscardable(entryId: Long): Boolean =
		recordedMs(entryId) < minLengthMs() && dao.attachmentCount(entryId) == 0 && dao.expenseCount(entryId) == 0

	suspend fun discard(entryId: Long) = db.withTransaction {
		load(entryId)
		if (!isDiscardable(entryId))
			throw ValidationException(Reason.TOO_SHORT, "entry $entryId is not discardable")
		keepRemoteIds(load(entryId))
		dao.deleteEntry(entryId)
	}

	companion object {
		private val DEFAULTS_WINDOW: Duration = Duration.ofDays(30)
		const val MIN_LENGTH_MS = 60_000L
		/* the end of a running segment when checking overlaps: it goes on */
		const val RUNNING_END = Long.MAX_VALUE
		/* the most Home shows (compact display) */
		const val RECENT_CLOSED = 3
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
	 * Instant for segment transitions: now (or [requested], not later than
	 * now), but never before the latest recorded boundary, so neither a
	 * backwards clock change nor a backdated command can create overlaps.
	 */
	private suspend fun writeInstant(entryId: Long, requested: Long? = null): Long {
		val latest = dao.segments(entryId).maxOfOrNull { maxOf(it.startUtcMs, it.endUtcMs ?: it.startUtcMs) }
		val now = clock.millis()
		return maxOf(requested?.let { minOf(it, now) } ?: now, latest ?: Long.MIN_VALUE)
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
