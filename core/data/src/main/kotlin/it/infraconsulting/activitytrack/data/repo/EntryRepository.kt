/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.AttachmentEntity
import it.infraconsulting.activitytrack.data.db.EntryTagEntity
import it.infraconsulting.activitytrack.data.db.ExpenseEntity
import it.infraconsulting.activitytrack.data.db.ExternalRefEntity
import it.infraconsulting.activitytrack.data.db.LocationSampleEntity
import it.infraconsulting.activitytrack.data.db.RemoteDeletedEntity
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import it.infraconsulting.activitytrack.domain.activity.AbsencePlanner
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityState
import it.infraconsulting.activitytrack.domain.activity.ActivityStateMachine
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.ActivityTransition
import it.infraconsulting.activitytrack.domain.activity.FutureClose
import it.infraconsulting.activitytrack.domain.activity.IllegalActivityCommandException
import it.infraconsulting.activitytrack.domain.activity.InstantRounding
import it.infraconsulting.activitytrack.domain.activity.Priority
import it.infraconsulting.activitytrack.domain.activity.ReturnChoice
import it.infraconsulting.activitytrack.domain.activity.ReturnTrip
import it.infraconsulting.activitytrack.domain.activity.TravelFromWork
import it.infraconsulting.activitytrack.domain.activity.TripSegment
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.model.QuickAction
import it.infraconsulting.activitytrack.domain.model.EntryInvariants
import it.infraconsulting.activitytrack.domain.model.ExternalRefClassifier
import it.infraconsulting.activitytrack.domain.model.InvariantViolationException
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.domain.model.Segment
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentOverlap
import it.infraconsulting.activitytrack.domain.model.SegmentSource
import it.infraconsulting.activitytrack.domain.model.Span
import it.infraconsulting.activitytrack.domain.model.SpanChange
import it.infraconsulting.activitytrack.domain.reminder.DayWindow
import it.infraconsulting.activitytrack.domain.start.KnownSite
import it.infraconsulting.activitytrack.domain.start.StartDefaults
import it.infraconsulting.activitytrack.domain.start.StartProposal
import it.infraconsulting.activitytrack.domain.sync.SyncIds
import it.infraconsulting.activitytrack.domain.tag.Tags
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

/*
 * Single writer of activities (time entries, docs/ACTIVITY-MODEL.md) and
 * their segments. Every operation runs in one Room transaction and verifies,
 * before committing, the invariants of DESIGN §4: state/segment consistency
 * and non-overlap (EntryInvariants), updated_utc_ms bump, and activity
 * company == client company. The rules live in the domain
 * (ActivityStateMachine, TravelFromWork, ReturnTrip, InstantRounding,
 * FutureClose, AbsencePlanner); this class applies them to the rows.
 *
 * Instants: a command without a time ([atUtcMs] null) happens now, rounded
 * when saving rounded times is on (4.2); a time the user chose is stored as
 * typed.
 */
class EntryRepository(
	private val db: ActivityTrackDatabase,
	private val clock: Clock,
	/* the choices a policy may change (issue #48): shortest segment */
	private val behaviours: () -> it.infraconsulting.activitytrack.domain.policy.Behaviours = { it.infraconsulting.activitytrack.domain.policy.Behaviours() },
	/* whether more activities may run at once (policy concurrentEntries, issue #132) */
	private val concurrent: () -> Boolean = { true },
	/* editable times, saving rounded times and the quanta (settings and policy) */
	private val times: suspend () -> TimeRules = { TimeRules() },
) {
	private fun minLengthMs(): Long = behaviours().minDurationSec * 1000L

	private val dao = db.entryDao()
	private val master = db.masterDataDao()

	val openEntries = dao.observeOpenEntries()
	val openEntrySegments = dao.observeOpenEntrySegments()
	val openEntryTags = dao.observeOpenEntryTags()
	val clientLastUse = dao.observeClientLastUse()

	/* TODO activities not deleted, by plan, due date and priority */
	val todos = dao.observeTodos()

	/* the bin: deleted activities, newest first */
	val deleted = dao.observeDeleted()

	/* absences not deleted, newest first */
	val absences = dao.observeAbsences()

	private val pulled = MutableSharedFlow<List<PulledBack>>(extraBufferCapacity = 8)

	/* earlier activities whose end moved back to another one's start (4.1): a notice for the user */
	val pulledBack: SharedFlow<List<PulledBack>> = pulled.asSharedFlow()

	/* the Home "Chiuse" tab (issue #59): activities ended after [fromUtcMs], newest end first */
	fun closedEntriesSince(fromUtcMs: Long, limit: Int) = dao.observeClosedSince(fromUtcMs, limit)
	fun closedSegmentsSince(fromUtcMs: Long, limit: Int) = dao.observeClosedSegmentsSince(fromUtcMs, limit)
	fun closedTagsSince(fromUtcMs: Long, limit: Int) = dao.observeClosedTagsSince(fromUtcMs, limit)
	/* the ticked tasks of the same tab (closed without a segment), newest closed first */
	fun tickedSince(fromUtcMs: Long, limit: Int) = dao.observeTickedSince(fromUtcMs, limit)
	fun tickedTagsSince(fromUtcMs: Long, limit: Int) = dao.observeTickedTagsSince(fromUtcMs, limit)

	fun entry(id: Long) = dao.observeEntry(id)
	suspend fun find(id: Long): TimeEntryEntity? = dao.entry(id)
	suspend fun findByUid(uid: String): TimeEntryEntity? = dao.byUid(uid)
	fun segments(entryId: Long) = dao.observeSegments(entryId)

	/* ---------------------------------------------------------------- creation (3, "Creation") */

	/*
	 * Pre-fill for a new activity (SPEC §2.1): the site containing
	 * [position], else today's latest company.
	 */
	suspend fun startProposal(sites: List<KnownSite>, position: GeoPoint? = null): StartProposal {
		val startOfToday = LocalDate.now(clock).atStartOfDay(clock.zone).toInstant().toEpochMilli()
		val previous = dao.lastWithCompanySince(startOfToday)?.companyId
		return StartDefaults.propose(position, sites, previous)
	}

	/* Description of the last activity on [clientId], however old (completion "copy last"). */
	suspend fun lastActivity(clientId: Long): String? = dao.lastOnClient(clientId, Long.MIN_VALUE)?.activity

	/* Description and role of the last activity on [clientId] in the last 30 days. */
	suspend fun clientDefaults(clientId: Long): ClientDefaults? {
		val since = clock.millis() - DEFAULTS_WINDOW.toMillis()
		return dao.lastOnClient(clientId, since)?.let { ClientDefaults(it.activity, it.roleId) }
	}

	/* Suspended activity to propose for resumption after [stoppedId] was stopped. */
	suspend fun suspendedToResume(stoppedId: Long): TimeEntryEntity? = dao.mostRecentSuspended(stoppedId)

	/*
	 * "Lavoro · Adesso / Già iniziata" and "Viaggio" (and the start of a TODO
	 * activity with [StartRequest.todoId]): RUNNING in WORK or TRAVEL; in the
	 * same transaction what was chosen for the activities already running
	 * ([others]; absent = left running as the policy says).
	 */
	suspend fun start(req: StartRequest, others: Map<Long, OnRunning> = emptyMap()): Long =
		db.withTransaction {
			others.forEach { (id, choice) -> if (choice == OnRunning.STOP) stop(id) else this.suspend(id) }
			val id = if (req.todoId != null) startTodoFrom(req) else startNew(req)
			req.endUtcMs?.let { end -> if (end <= clock.millis()) stop(id, end) }
			id
		}

	/* "Viaggio": an anonymous activity travelling from now (or [atUtcMs]). */
	suspend fun startTravel(atUtcMs: Long? = null): Long = start(StartRequest(SegmentKind.TRAVEL, null, startUtcMs = atUtcMs))

	private suspend fun startNew(req: StartRequest): Long {
		if (req.clientId == null && req.mode != SegmentKind.TRAVEL)
			throw ValidationException(Reason.CLIENT_REQUIRED)
		val companyId = companyFor(req.companyId, req.clientId)
		val now = clock.millis()
		if (req.startUtcMs != null && req.startUtcMs > now)
			throw ValidationException(Reason.FUTURE_TIME)
		val rules = times()
		val start = req.startUtcMs ?: if (rules.rounds) roundStart(req.mode, now, null, rules) else now
		if (req.endUtcMs != null && req.endUtcMs - start < minLengthMs())
			throw ValidationException(if (req.endUtcMs > start) Reason.TOO_SHORT else Reason.INVALID_TIME_RANGE)
		val t = ActivityStateMachine.created(req.mode, Instant.ofEpochMilli(start))
		val zone = clock.zone.id
		val id = dao.startEntry(
			TimeEntryEntity(
				uid = UUID.randomUUID().toString(),
				companyId = companyId,
				clientId = req.clientId,
				roleId = req.roleId,
				activity = req.activity?.trim()?.ifEmpty { null },
				status = t.next.status,
				mode = t.next.mode,
				locality = req.locality?.trim()?.ifEmpty { null },
				locationKind = req.locationKind,
				notes = req.notes?.trim()?.ifEmpty { null },
				pluginFields = req.pluginFields,
				zoneId = zone,
				lastConfirmedUtcMs = now,
				plannedEndUtcMs = req.endUtcMs?.takeIf { it > now },
				createdUtcMs = now,
				updatedUtcMs = now,
			),
			SegmentEntity(entryId = 0, kind = req.mode, startUtcMs = start, zoneId = zone),
		)
		addExtras(id, req, start)
		verifyOrOverlap(id)
		afterStart(id, req.mode, start)
		return id
	}

	/* what a creation dialog adds to the new activity: segments before the start, references */
	private suspend fun addExtras(id: Long, req: StartRequest, startMs: Long) {
		val zone = clock.zone.id
		val now = clock.millis()
		for (s in req.earlier) {
			checkSegment(s, now, minOf(startMs, now))
			if (s.kind == SegmentKind.ABSENCE)
				throw ValidationException(Reason.INVALID_SEGMENT, "absence segment in an activity")
			dao.insertSegment(
				SegmentEntity(
					entryId = id, kind = s.kind, startUtcMs = s.startUtcMs, endUtcMs = s.endUtcMs, zoneId = zone, source = SegmentSource.MANUAL,
					kmDeclared = s.kmDeclared?.takeIf { s.kind == SegmentKind.TRAVEL },
				),
			)
		}
		val have = dao.externalRefs(listOf(id)).map { it.value }.toSet()
		for (r in req.externalRefs.map { it.trim() }.filter { it.isNotEmpty() && it !in have })
			dao.insertExternalRef(ExternalRefEntity(entryId = id, kind = ExternalRefClassifier.classify(r), value = r))
	}

	/* a TODO activity started from the start dialog: its details as chosen there, then start(kind, at) */
	private suspend fun startTodoFrom(req: StartRequest): Long {
		val id = req.todoId!!
		val e = load(id)
		val clientId = req.clientId ?: e.clientId
		if (clientId == null && req.mode != SegmentKind.TRAVEL)
			throw ValidationException(Reason.CLIENT_REQUIRED)
		if (req.startUtcMs != null && req.startUtcMs > clock.millis())
			throw ValidationException(Reason.FUTURE_TIME)
		dao.updateEntry(
			e.copy(
				clientId = clientId,
				companyId = companyFor(req.companyId ?: e.companyId.takeIf { req.clientId == null }, clientId),
				roleId = req.roleId ?: e.roleId,
				activity = req.activity?.trim()?.ifEmpty { null } ?: e.activity,
				locality = req.locality?.trim()?.ifEmpty { null } ?: e.locality,
				locationKind = req.locationKind ?: e.locationKind,
				plannedEndUtcMs = req.endUtcMs?.takeIf { it > clock.millis() },
				notes = req.notes?.trim()?.ifEmpty { null } ?: e.notes,
				pluginFields = req.pluginFields ?: e.pluginFields,
			),
		)
		command(id, ActivityCommand.Start(req.mode, at(req.startUtcMs)), exact = req.startUtcMs != null)
		if (req.earlier.isNotEmpty() || req.externalRefs.isNotEmpty()) {
			val opened = dao.segments(id).firstOrNull { !it.planned && it.endUtcMs == null }?.startUtcMs ?: clock.millis()
			addExtras(id, req, opened)
			verifyOrOverlap(id)
		}
		return id
	}

	/*
	 * "Lavoro · Passata": a CLOSED activity with the segments typed (stored
	 * as typed, never rounded); the last end may lie up to 60 minutes ahead
	 * when times are editable (4.1), the starts are in the past.
	 */
	suspend fun createPast(req: PastRequest): Long = db.withTransaction {
		val d = req.details
		if (d.clientId == null)
			throw ValidationException(Reason.CLIENT_REQUIRED)
		if (req.segments.isEmpty())
			throw ValidationException(Reason.INVALID_SEGMENT, "no segment")
		val now = clock.millis()
		val rules = times()
		val limit = FutureClose.limit(Instant.ofEpochMilli(now), rules.editableTimes).toEpochMilli()
		for (s in req.segments) {
			checkSegment(s, now, limit)
			if (s.kind == SegmentKind.ABSENCE)
				throw ValidationException(Reason.INVALID_SEGMENT, "absence segment in an activity")
		}
		val zone = clock.zone.id
		val id = dao.insertEntry(
			TimeEntryEntity(
				uid = UUID.randomUUID().toString(),
				companyId = companyFor(d.companyId, d.clientId), clientId = d.clientId, roleId = d.roleId,
				activity = d.activity?.trim()?.ifEmpty { null }, status = ActivityStatus.CLOSED, closedUtcMs = now,
				locality = d.locality?.trim()?.ifEmpty { null }, locationKind = d.locationKind, notes = d.notes?.trim()?.ifEmpty { null },
				contactId = d.contactId?.takeIf { c -> db.callDao().contact(c)?.clientId == d.clientId },
				zoneId = zone, createdUtcMs = req.segments.minOf { it.startUtcMs }, updatedUtcMs = now,
			),
		)
		for (s in req.segments)
			dao.insertSegment(
				SegmentEntity(
					entryId = id, kind = s.kind, startUtcMs = s.startUtcMs, endUtcMs = s.endUtcMs, zoneId = zone, source = SegmentSource.MANUAL,
					kmDeclared = s.kmDeclared?.takeIf { s.kind == SegmentKind.TRAVEL },
				),
			)
		verifyOrOverlap(id)
		id
	}

	/*
	 * "Da fare": create ([id] null) or edit a TODO activity. A plan needs a
	 * day (the time is optional), a duration needs a start and stays within
	 * a day; planned segments only while TODO, not overlapping.
	 */
	suspend fun saveTodo(id: Long?, req: TodoRequest): Long = db.withTransaction {
		val now = clock.millis()
		if (req.planEpochDay == null && req.planStartMin != null)
			throw ValidationException(Reason.INVALID_DATE)
		if (req.durationMin != null && req.planStartMin == null)
			throw ValidationException(Reason.INVALID_TIME)
		if (req.planStartMin != null && req.planStartMin !in 0 until 1440)
			throw ValidationException(Reason.INVALID_TIME)
		if (req.durationMin != null && req.durationMin !in 1..TodoPlan.MAX_DURATION_MIN)
			throw ValidationException(Reason.INVALID_TIME_RANGE)
		if (req.dueTimeMin != null && (req.dueEpochDay == null || req.dueTimeMin !in 0 until 1440))
			throw ValidationException(Reason.INVALID_TIME)
		for (s in req.planned)
			if (s.kind == SegmentKind.ABSENCE || s.endUtcMs <= s.startUtcMs)
				throw ValidationException(Reason.INVALID_SEGMENT)
		val companyId = companyFor(req.companyId, req.clientId)
		val activity = req.activity?.trim()?.ifEmpty { null } ?: throw ValidationException(Reason.BLANK_VALUE)
		val existing = id?.let { load(it) }
		if (existing != null && existing.status != ActivityStatus.TODO)
			throw ValidationException(Reason.NOT_ALLOWED, "activity $id is ${existing.status}")
		val base = existing ?: TimeEntryEntity(
			uid = UUID.randomUUID().toString(), companyId = null, clientId = null, status = ActivityStatus.TODO,
			zoneId = clock.zone.id, createdUtcMs = now, updatedUtcMs = now,
		)
		val planMoved = existing != null && (existing.planEpochDay != req.planEpochDay || existing.planStartMin != req.planStartMin)
		val row = base.copy(
			companyId = companyId, clientId = req.clientId, roleId = req.roleId ?: base.roleId, activity = activity,
			notes = req.notes?.trim()?.ifEmpty { null }, dueEpochDay = req.dueEpochDay, dueTimeMin = req.dueTimeMin,
			planEpochDay = req.planEpochDay, planStartMin = req.planStartMin, durationMin = req.durationMin,
			planRemindedUtcMs = if (planMoved) null else base.planRemindedUtcMs,
			priority = Priority.of(req.priority).value, externalKey = req.externalKey ?: base.externalKey,
			pluginFields = req.pluginFields ?: base.pluginFields, updatedUtcMs = now,
		)
		val entryId = if (existing == null) dao.insertEntry(row) else row.id.also { dao.updateEntry(row) }
		dao.deletePlannedSegments(entryId)
		for (s in req.planned)
			dao.insertSegment(
				SegmentEntity(
					entryId = entryId, kind = s.kind, startUtcMs = s.startUtcMs, endUtcMs = s.endUtcMs, zoneId = row.zoneId,
					source = SegmentSource.MANUAL, planned = true,
				),
			)
		verifyOrOverlap(entryId)
		entryId
	}

	/*
	 * "Assenza" (3.1): an ABSENCE activity, CLOSED, with one ABSENCE segment
	 * per day from AbsencePlanner over the working hours, lunch breaks and
	 * days off (or with the times given); nothing to record = an error.
	 */
	suspend fun createAbsence(req: AbsenceRequest): Long = db.withTransaction {
		if (req.toEpochDay < req.fromEpochDay || req.toEpochDay - req.fromEpochDay >= MAX_ABSENCE_DAYS)
			throw ValidationException(Reason.INVALID_TIME_RANGE)
		if ((req.startMin == null) != (req.endMin == null) || (req.startMin != null && (req.startMin !in 0 until 1440 || req.endMin!! !in req.startMin + 1..1440)))
			throw ValidationException(Reason.INVALID_TIME_RANGE)
		val zone = clock.zone
		val from = LocalDate.ofEpochDay(req.fromEpochDay)
		val to = LocalDate.ofEpochDay(req.toEpochDay)
		val daysOff = master.daysOffBetween(req.fromEpochDay, req.toEpochDay).toSet()
		val intervals = AbsencePlanner.plan(from, to, req.startMin, req.endMin, schedule(), lunchBreaks(), { it.toEpochDay() in daysOff }, zone)
		if (intervals.isEmpty())
			throw ValidationException(Reason.INVALID_DATE, "no working day in the range")
		val now = clock.millis()
		val id = dao.insertEntry(
			TimeEntryEntity(
				uid = UUID.randomUUID().toString(), kind = ActivityKind.ABSENCE, status = ActivityStatus.CLOSED, absenceKind = req.absenceKind,
				companyId = req.companyId, clientId = null, notes = req.notes?.trim()?.ifEmpty { null },
				closedUtcMs = intervals.maxOf { it.end.toEpochMilli() }, zoneId = zone.id, createdUtcMs = now, updatedUtcMs = now,
			),
		)
		for (i in intervals)
			dao.insertSegment(
				SegmentEntity(
					entryId = id, kind = SegmentKind.ABSENCE, startUtcMs = i.start.toEpochMilli(), endUtcMs = i.end.toEpochMilli(),
					zoneId = zone.id, source = SegmentSource.MANUAL,
				),
			)
		verify(id)
		id
	}

	/*
	 * "Late entry at…" of the day-start prompt: a LEAVE absence ("permesso")
	 * from the day's first working hour to [untilMin]; at or before it nothing
	 * was missed and nothing is recorded.
	 */
	suspend fun recordLateEntry(day: LocalDate, untilMin: Int) {
		val first = schedule().filter { it.dayOfWeek == day.dayOfWeek.value }.minOfOrNull { it.startMin } ?: return
		if (untilMin <= first)
			return
		createAbsence(AbsenceRequest(absenceKind = it.infraconsulting.activitytrack.domain.activity.AbsenceKind.LEAVE, fromEpochDay = day.toEpochDay(), startMin = first, endMin = untilMin))
	}

	private suspend fun schedule(): List<DayWindow> =
		(1..7).flatMap { d -> master.scheduleOf(d).map { DayWindow(it.dayOfWeek, it.startMin, it.endMin) } }

	private suspend fun lunchBreaks(): List<DayWindow> =
		master.observeLunchBreaks().first().map { DayWindow(it.dayOfWeek, it.startMin, it.endMin) }

	/* ---------------------------------------------------------------- commands (3) */

	/* TODO -> RUNNING: the planned segments go, [kind] opens now or at [atUtcMs] */
	suspend fun startTodo(entryId: Long, kind: SegmentKind, atUtcMs: Long? = null) = db.withTransaction {
		if (atUtcMs != null && atUtcMs > clock.millis())
			throw ValidationException(Reason.FUTURE_TIME)
		command(entryId, ActivityCommand.Start(kind, at(atUtcMs)), exact = atUtcMs != null)
	}

	/* RUNNING with another kind open: close it and open [kind] at one instant */
	suspend fun switchTo(entryId: Long, kind: SegmentKind, atUtcMs: Long? = null) = db.withTransaction {
		checkChosen(entryId, atUtcMs, future = false)
		command(entryId, ActivityCommand.Switch(kind, at(atUtcMs)), exact = atUtcMs != null)
	}

	/* RUNNING, nothing open: open [kind] */
	suspend fun resume(entryId: Long, kind: SegmentKind, atUtcMs: Long? = null) = db.withTransaction {
		checkChosen(entryId, atUtcMs, future = false)
		command(entryId, ActivityCommand.Resume(kind, at(atUtcMs)), exact = atUtcMs != null)
	}

	/* RUNNING, one open: close it; the activity keeps running (internal: another one started) */
	suspend fun suspend(entryId: Long, atUtcMs: Long? = null) = db.withTransaction {
		checkChosen(entryId, atUtcMs, future = false)
		command(entryId, ActivityCommand.Suspend(Instant.ofEpochMilli(atUtcMs ?: clock.millis())), exact = true)
	}

	/*
	 * RUNNING -> CLOSED, the open segment closed now (rounded up when saving
	 * rounded times) or at [atUtcMs]: up to 60 minutes ahead when times are
	 * editable (4.1), never before the running segment started.
	 */
	suspend fun stop(entryId: Long, atUtcMs: Long? = null) = db.withTransaction {
		checkChosen(entryId, atUtcMs, future = true)
		command(entryId, ActivityCommand.Stop(at(atUtcMs)), exact = atUtcMs != null)
	}

	/* TODO -> CLOSED: a task ticked off, the planned segments go */
	suspend fun tick(entryId: Long) = db.withTransaction { command(entryId, ActivityCommand.Tick, exact = true) }

	/* CLOSED without real segments -> TODO: a task ticked off by mistake (3, "untick") */
	suspend fun untick(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		if (!canUntick(e, dao.segments(entryId)))
			throw ValidationException(Reason.NOT_ALLOWED, "activity $entryId cannot go back to TODO")
		dao.updateEntry(e.copy(status = ActivityStatus.TODO, mode = null, closedUtcMs = null, updatedUtcMs = clock.millis()))
		verify(entryId)
	}

	/*
	 * The fields of an absence edited in the detail dialog (3.1): company
	 * (null = every company), kind and notes; its days are its segments.
	 */
	suspend fun updateAbsence(entryId: Long, companyId: Long?, absenceKind: AbsenceKind, notes: String?) = db.withTransaction {
		val e = load(entryId)
		if (e.kind != ActivityKind.ABSENCE)
			throw ValidationException(Reason.NOT_ALLOWED, "activity $entryId is not an absence")
		dao.updateEntry(e.copy(companyId = companyId, absenceKind = absenceKind, notes = notes?.trim()?.ifEmpty { null }, updatedUtcMs = clock.millis()))
		verify(entryId)
	}

	/* CLOSED -> RUNNING in [kind]; the gap stays a gap (the UI asks first) */
	suspend fun reopen(entryId: Long, kind: SegmentKind = SegmentKind.WORK, atUtcMs: Long? = null) = db.withTransaction {
		checkChosen(entryId, atUtcMs, future = false)
		command(entryId, ActivityCommand.Reopen(kind, at(atUtcMs)), exact = atUtcMs != null)
	}

	/*
	 * Delete ("eliminata"): hidden everywhere but the bin, restorable. A
	 * running segment is closed first. What a plugin sent for it is deleted
	 * on the remote side at the next sync, and sent again if it is restored.
	 */
	suspend fun delete(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		if (e.deletedUtcMs != null)
			return@withTransaction
		if (e.status == ActivityStatus.RUNNING && e.mode != null)
			command(entryId, ActivityCommand.Suspend(Instant.ofEpochMilli(writeInstant(entryId, clock.millis()))), exact = true)
		keepRemoteIds(e)
		db.pluginDao().sentAll(entryId).forEach { db.pluginDao().forgetSent(entryId, it.pluginId, it.blockKey) }
		command(entryId, ActivityCommand.Delete, exact = true)
	}

	/* Out of the bin. */
	suspend fun restore(entryId: Long) = db.withTransaction { command(entryId, ActivityCommand.Restore, exact = true) }

	/*
	 * Delete for good, only from the bin, with its segments, expenses,
	 * references and tags (owner, issue #40). With attachments it is refused:
	 * an activity with attachments is hardly a mistake, they go by hand first.
	 */
	suspend fun purge(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		if (e.deletedUtcMs == null)
			throw ValidationException(Reason.NOT_DELETED)
		if (dao.attachmentCount(entryId) > 0)
			throw ValidationException(Reason.HAS_ATTACHMENTS)
		keepRemoteIds(e)
		dao.deleteEntry(entryId)
	}

	/*
	 * TRAVEL asked while WORK is open (3): [workDone] "Hai completato
	 * l'attività lavorativa?" yes stops this one and starts a new anonymous
	 * activity travelling at the same instant (its id returned); no switches
	 * this one to TRAVEL (this id returned).
	 */
	suspend fun travelFromWork(entryId: Long, workDone: Boolean, atUtcMs: Long? = null): Long = db.withTransaction {
		checkChosen(entryId, atUtcMs, future = false)
		val e = load(entryId)
		val now = clock.millis()
		val rules = times()
		val open = dao.segments(entryId).lastOrNull { it.endUtcMs == null && !it.planned }
		val at = atUtcMs ?: if (rules.rounds && open != null) roundSwitch(open, SegmentKind.TRAVEL, now, rules) else now
		val decision = try {
			TravelFromWork.decide(state(e), workDone, Instant.ofEpochMilli(at), Instant.ofEpochMilli(now))
		} catch (x: IllegalArgumentException) {
			throw ValidationException(Reason.NOT_ALLOWED, x.message)
		}
		when (decision) {
			is TravelFromWork.Switch -> {
				applyTransition(e, decision.switch, null)
				afterStart(entryId, SegmentKind.TRAVEL, at)
				entryId
			}
			is TravelFromWork.StopAndTravel -> {
				applyTransition(e, decision.stop, null)
				startNew(StartRequest(SegmentKind.TRAVEL, null, startUtcMs = at, companyId = e.companyId.takeIf { e.clientId == null }))
			}
		}
	}

	/* The stop question's choices (3.2); empty: a plain stop. */
	suspend fun returnChoices(entryId: Long): List<ReturnChoice> =
		ReturnTrip.choices(tripSegments(entryId), times().editableTimes)

	/* "Avvia viaggio di ritorno": travel now, the activity keeps running */
	suspend fun travelBack(entryId: Long, atUtcMs: Long? = null) = db.withTransaction {
		checkChosen(entryId, atUtcMs, future = false)
		val e = load(entryId)
		val now = clock.millis()
		val rules = times()
		val open = dao.segments(entryId).lastOrNull { it.endUtcMs == null && !it.planned }
		val at = atUtcMs ?: if (rules.rounds && open != null) roundSwitch(open, SegmentKind.TRAVEL, now, rules) else now
		val t = try {
			ReturnTrip.travelBack(state(e), Instant.ofEpochMilli(at), Instant.ofEpochMilli(now))
		} catch (x: IllegalActivityCommandException) {
			throw ValidationException(Reason.NOT_ALLOWED, x.message)
		}
		applyTransition(e, t, null)
		afterStart(entryId, SegmentKind.TRAVEL, at)
	}

	/*
	 * "Termina l'attività ed aggiungi il viaggio di ritorno" (3.2), in one
	 * transaction: the stop at [atUtcMs] (now rounded up when saving rounded
	 * times), a MANUAL TRAVEL segment as long as the outbound one with its
	 * km, and its two location samples (the outbound's last and first,
	 * swapped). Only with editable times: the return's end may lie ahead.
	 * Returns the return segment's id.
	 */
	suspend fun stopWithReturn(entryId: Long, atUtcMs: Long? = null): Long = db.withTransaction {
		val rules = times()
		if (!rules.editableTimes)
			throw ValidationException(Reason.NOT_ALLOWED, "return trip needs editable times")
		checkChosen(entryId, atUtcMs, future = true)
		val e = load(entryId)
		val now = clock.millis()
		val open = dao.segments(entryId).lastOrNull { it.endUtcMs == null && !it.planned }
		val at = atUtcMs ?: if (rules.rounds && open != null) roundStop(open, now, rules) else now
		val (stop, ret) = try {
			ReturnTrip.stopWithReturn(state(e), tripSegments(entryId), Instant.ofEpochMilli(at), Instant.ofEpochMilli(now))
		} catch (x: IllegalArgumentException) {
			throw ValidationException(Reason.NOT_ALLOWED, x.message)
		} catch (x: IllegalActivityCommandException) {
			throw ValidationException(Reason.NOT_ALLOWED, x.message)
		}
		applyTransition(e, stop, null)
		val segId = dao.insertSegment(
			SegmentEntity(
				entryId = entryId, kind = SegmentKind.TRAVEL, startUtcMs = ret.start.toEpochMilli(), endUtcMs = ret.end.toEpochMilli(),
				zoneId = clock.zone.id, source = SegmentSource.MANUAL, kmEstimated = ret.kmEstimated, kmDeclared = ret.kmDeclared,
			),
		)
		db.locationDao().insertSamples(
			ret.samples.map { s ->
				LocationSampleEntity(
					entryId = entryId, segmentId = segId, tsUtcMs = s.at.toEpochMilli(), lat = s.point.lat, lon = s.point.lon,
					accuracyM = s.point.accuracyM, provider = s.point.provider,
				)
			},
		)
		verifyOrOverlap(entryId)
		segId
	}

	/* the real segments as ReturnTrip sees them, with the first and last sample of each travel */
	private suspend fun tripSegments(entryId: Long): List<TripSegment<LocationSampleEntity>> =
		dao.segments(entryId).filter { !it.planned }.map { s ->
			val pts = if (s.kind == SegmentKind.TRAVEL) db.locationDao().samplesOfSegment(s.id) else emptyList()
			TripSegment(
				s.kind, Instant.ofEpochMilli(s.startUtcMs), s.endUtcMs?.let(Instant::ofEpochMilli), s.kmEstimated, s.kmDeclared,
				pts.firstOrNull(), pts.lastOrNull(),
			)
		}

	/*
	 * The buttons of notifications and widgets ([QuickAction]) on the commands
	 * of the activity model: work and travel start a TODO, reopen a closed
	 * activity, resume a suspended one or switch; pause switches; stop closes.
	 * [atUtcMs] backdates the transition (detected departure or arrival),
	 * clamped between the latest recorded boundary and now.
	 */
	suspend fun quick(entryId: Long, action: QuickAction, atUtcMs: Long? = null) {
		db.withTransaction {
			val e = load(entryId)
			val at = Instant.ofEpochMilli(if (atUtcMs == null) 0 else writeInstant(entryId, atUtcMs))
			command(entryId, quickCommand(e, action, at), exact = atUtcMs != null, retag = atUtcMs != null)
		}
	}

	private fun quickCommand(e: TimeEntryEntity, action: QuickAction, at: Instant): ActivityCommand = when (action) {
		QuickAction.WORK, QuickAction.TRAVEL -> {
			val kind = SegmentKind.valueOf(action.name)
			when {
				e.status == ActivityStatus.TODO -> ActivityCommand.Start(kind, at)
				e.status == ActivityStatus.CLOSED -> ActivityCommand.Reopen(kind, at)
				e.mode == null -> ActivityCommand.Resume(kind, at)
				else -> ActivityCommand.Switch(kind, at)
			}
		}
		QuickAction.PAUSE -> ActivityCommand.Switch(SegmentKind.PAUSE, at)
		QuickAction.STOP -> ActivityCommand.Stop(at)
	}

	/* a chosen time: not after now (a stop: the 4.1 limit), not before the latest boundary of the activity */
	private suspend fun checkChosen(entryId: Long, atUtcMs: Long?, future: Boolean) {
		atUtcMs ?: return
		val now = Instant.ofEpochMilli(clock.millis())
		val limit = if (future) FutureClose.limit(now, times().editableTimes) else now
		if (atUtcMs > limit.toEpochMilli())
			throw ValidationException(Reason.FUTURE_TIME)
		val latest = dao.segments(entryId).filter { !it.planned }.maxOfOrNull { maxOf(it.startUtcMs, it.endUtcMs ?: it.startUtcMs) }
		if (latest != null && atUtcMs < latest)
			throw ValidationException(Reason.BEFORE_SEGMENT)
	}

	private fun at(atUtcMs: Long?): Instant = Instant.ofEpochMilli(atUtcMs ?: 0)

	/*
	 * One command on one activity. With [exact] the instant of the command is
	 * stored as given; otherwise it is now, rounded when saving rounded times
	 * is on (4.2). Opening WORK or TRAVEL without concurrent activities
	 * suspends the others (issue #132; a pause too, coordinator 2026-11-11),
	 * and pulls back a future end of another activity (4.1).
	 */
	private suspend fun command(entryId: Long, cmd: ActivityCommand, exact: Boolean, retag: Boolean = false) {
		val e = load(entryId)
		val nowMs = clock.millis()
		val resolved = if (exact) cmd else nowCommand(e, cmd, nowMs)
		val t = try {
			ActivityStateMachine.apply(state(e), resolved, Instant.ofEpochMilli(nowMs))
		} catch (x: IllegalActivityCommandException) {
			throw ValidationException(Reason.NOT_ALLOWED, x.message)
		}
		applyTransition(e, t, if (retag) t.open?.at?.toEpochMilli() else null)
		t.open?.let { afterStart(entryId, it.kind, it.at.toEpochMilli()) }
	}

	/* the command at now, rounded per 4.2 when that is on */
	private suspend fun nowCommand(e: TimeEntryEntity, cmd: ActivityCommand, clockMs: Long): ActivityCommand {
		val rules = times()
		val segs = dao.segments(e.id).filter { !it.planned }
		/* a clock that went backwards must not put a boundary before the latest recorded one (overlap) */
		val latest = segs.maxOfOrNull { maxOf(it.startUtcMs, it.endUtcMs ?: it.startUtcMs) }
		val nowMs = if (latest != null && latest > clockMs) latest else clockMs
		val now = Instant.ofEpochMilli(nowMs)
		val open = segs.lastOrNull { it.endUtcMs == null }
		val lastEnd = segs.mapNotNull { it.endUtcMs }.maxOrNull()
		fun ms(v: Long) = Instant.ofEpochMilli(v)
		val r = rules.rounds
		return when (cmd) {
			is ActivityCommand.Start -> cmd.copy(at = if (r) ms(roundStart(cmd.kind, nowMs, lastEnd, rules)) else now)
			is ActivityCommand.Resume -> cmd.copy(at = if (r) ms(roundStart(cmd.kind, nowMs, lastEnd, rules)) else now)
			is ActivityCommand.Reopen -> cmd.copy(at = if (r) ms(roundStart(cmd.kind, nowMs, lastEnd, rules)) else now)
			is ActivityCommand.Switch -> cmd.copy(at = if (r && open != null) ms(roundSwitch(open, cmd.kind, nowMs, rules)) else now)
			is ActivityCommand.Stop -> cmd.copy(at = if (r && open != null) ms(roundStop(open, nowMs, rules)) else now)
			is ActivityCommand.Suspend -> cmd.copy(at = now)
			else -> cmd
		}
	}

	private fun roundStart(kind: SegmentKind, nowMs: Long, notBefore: Long?, rules: TimeRules): Long =
		InstantRounding.start(Instant.ofEpochMilli(nowMs), InstantRounding.quantum(kind, rules.rounding), clock.zone, notBefore?.let(Instant::ofEpochMilli)).toEpochMilli()

	private fun roundStop(open: SegmentEntity, nowMs: Long, rules: TimeRules): Long {
		val now = Instant.ofEpochMilli(nowMs)
		return maxOf(open.startUtcMs, InstantRounding.stop(now, InstantRounding.quantum(open.kind, rules.rounding), clock.zone, now).toEpochMilli())
	}

	private fun roundSwitch(open: SegmentEntity, to: SegmentKind, nowMs: Long, rules: TimeRules): Long =
		InstantRounding.switch(
			Instant.ofEpochMilli(nowMs), InstantRounding.quantum(open.kind, rules.rounding), InstantRounding.quantum(to, rules.rounding),
			clock.zone, Instant.ofEpochMilli(open.startUtcMs),
		).toEpochMilli()

	/* the transition's effects in the order of ActivityTransition, then the row; [retagFrom]: the samples since then join the opened segment */
	private suspend fun applyTransition(e: TimeEntryEntity, t: ActivityTransition, retagFrom: Long?) {
		val nowMs = clock.millis()
		if (t.deletePlanned)
			dao.deletePlannedSegments(e.id)
		t.closeAt?.let { check(dao.closeOpenSegment(e.id, it.toEpochMilli()) == 1) { "entry ${e.id}: no open segment to close" } }
		val opened = t.open
		if (opened != null) {
			val segId = dao.insertSegment(SegmentEntity(entryId = e.id, kind = opened.kind, startUtcMs = opened.at.toEpochMilli(), zoneId = clock.zone.id))
			/* e.g. "travel from 10:05" tapped at 10:20: the fixes since 10:05 are part of the travel */
			if (retagFrom != null)
				dao.retagSamples(e.id, segId, retagFrom)
		}
		val n = t.next
		dao.updateEntry(
			load(e.id).copy(
				status = n.status,
				mode = n.mode,
				closedUtcMs = n.closedAt?.toEpochMilli(),
				deletedUtcMs = n.deletedAt?.toEpochMilli(),
				lastConfirmedUtcMs = if (opened != null) minOf(nowMs, opened.at.toEpochMilli()) else e.lastConfirmedUtcMs,
				unansweredReminders = if (opened != null) 0 else e.unansweredReminders,
				updatedUtcMs = nowMs,
			),
		)
		verify(e.id)
	}

	/* an activity began working or travelling at [atMs]: the others make room (concurrency, 4.1) */
	private suspend fun afterStart(entryId: Long, kind: SegmentKind, atMs: Long) {
		if (kind != SegmentKind.PAUSE && !concurrent())
			for (other in dao.activeEntries().filter { it.id != entryId }) {
				val at = maxOf(atMs, dao.segments(other.id).filter { !it.planned }.maxOfOrNull { it.startUtcMs } ?: Long.MIN_VALUE)
				command(other.id, ActivityCommand.Suspend(Instant.ofEpochMilli(at)), exact = true)
			}
		pullBack(entryId, atMs)
	}

	/*
	 * 4.1: an earlier activity closed with its end after now and after the
	 * new start: that end moves back to the new start (not before its
	 * segment's start), with a notice.
	 */
	private suspend fun pullBack(entryId: Long, startMs: Long) {
		val nowMs = clock.millis()
		val moved = ArrayList<PulledBack>()
		for (s in dao.segmentsBetween(startMs, Long.MAX_VALUE)) {
			val end = s.endUtcMs ?: continue
			if (s.entryId == entryId || s.planned || end <= nowMs || end <= startMs)
				continue
			val other = dao.entry(s.entryId) ?: continue
			if (other.deletedUtcMs != null || other.kind != ActivityKind.ACTIVITY)
				continue
			val newEnd = FutureClose.pulledBack(Instant.ofEpochMilli(end), Instant.ofEpochMilli(s.startUtcMs), Instant.ofEpochMilli(startMs))?.toEpochMilli() ?: continue
			dao.updateSegment(s.copy(endUtcMs = newEnd))
			dao.updateEntry(other.copy(updatedUtcMs = nowMs))
			moved += PulledBack(other.id, end, newEnd)
		}
		if (moved.isNotEmpty())
			pulled.tryEmit(moved)
	}

	private fun state(e: TimeEntryEntity) = ActivityState(
		e.kind, e.status, e.mode, e.closedUtcMs?.let(Instant::ofEpochMilli), e.deletedUtcMs?.let(Instant::ofEpochMilli),
	)

	/* ---------------------------------------------------------------- TODO activities (was TodoRepository) */

	/* TODO activities due or planned by [day] (overdue included): the home list. */
	fun dueBy(todos: List<TimeEntryEntity>, day: LocalDate): List<TimeEntryEntity> =
		todos.filter { t -> t.isOpenTodo() && listOfNotNull(t.dueEpochDay, t.planEpochDay).any { it <= day.toEpochDay() } }

	/* TODO activities with a plan (reminders, calendars); deleted ones are not commitments. */
	suspend fun plannedTodos(): List<TimeEntryEntity> = dao.todos().filter { it.plan() != null }

	/* The plan removed: the activity stays TODO, with no day and no reminder. */
	suspend fun clearPlan(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		dao.updateEntry(e.copy(planEpochDay = null, planStartMin = null, durationMin = null, planRemindedUtcMs = null, updatedUtcMs = clock.millis()))
	}

	/* The plan moved [minutes] after its start, or after now once started ("postpone"); the duration stays. */
	suspend fun postpone(entryId: Long, minutes: Int) = db.withTransaction {
		val e = load(entryId)
		val p = e.plan()?.postponed(minutes, clock.instant(), clock.zone) ?: return@withTransaction
		dao.updateEntry(e.copy(planEpochDay = p.day.toEpochDay(), planStartMin = p.startMin, planRemindedUtcMs = null, updatedUtcMs = clock.millis()))
	}

	/* TODO activities planned on [day] that nobody worked on that day (issue #52: asked at the end of the working hours). */
	suspend fun unworked(day: LocalDate, zone: ZoneId): List<TimeEntryEntity> {
		val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
		val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
		return dao.todos().filter { it.planEpochDay == day.toEpochDay() && dao.workedBetween(it.id, from, to) == 0 }
	}

	/* The plans of [ids] moved to [day], their time kept; their reminders may come again. */
	suspend fun replan(ids: Collection<Long>, day: LocalDate) = db.withTransaction {
		for (id in ids) {
			val e = dao.entry(id) ?: continue
			if (e.planEpochDay == null || !e.isOpenTodo())
				continue
			dao.updateEntry(e.copy(planEpochDay = day.toEpochDay(), planRemindedUtcMs = null, updatedUtcMs = clock.millis()))
		}
	}

	/* The reminder of the plan starting at [startMs] was shown. */
	suspend fun markPlanReminded(entryId: Long, startMs: Long) = dao.markPlanReminded(entryId, startMs)

	/*
	 * An activity put back as it was before a change ("Annulla", issue #97):
	 * the row only, when its state did not move meanwhile; segments stay.
	 */
	suspend fun putBack(e: TimeEntryEntity) = db.withTransaction {
		val now = dao.entry(e.id) ?: return@withTransaction
		if (now.status == e.status && now.mode == e.mode)
			dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
	}

	/* ---------------------------------------------------------------- details and segments */

	/* Segments of every activity touching [fromUtcMs, toUtcMs): busy cells of the time grid (M18). */
	suspend fun segmentsTouching(fromUtcMs: Long, toUtcMs: Long): List<SegmentEntity> =
		dao.segmentsOfEntriesTouching(fromUtcMs, toUtcMs)

	/* The planned end was asked about (M22): it is not asked again. */
	suspend fun clearPlannedEnd(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		if (e.plannedEndUtcMs != null)
			dao.updateEntry(e.copy(plannedEndUtcMs = null))
	}

	/*
	 * A call during a running activity of another client (issue #28): the
	 * open work segment ends at [startUtcMs], a pause with [note] covers the
	 * call, work goes on from [endUtcMs]. Nothing if the activity changed in
	 * the meantime (not working, or a segment began after the call started).
	 */
	suspend fun insertCallPause(entryId: Long, startUtcMs: Long, endUtcMs: Long, note: String): Boolean = db.withTransaction {
		val e = load(entryId)
		val open = dao.segments(entryId).firstOrNull { it.endUtcMs == null && !it.planned }
		if (e.status != ActivityStatus.RUNNING || e.deletedUtcMs != null || open == null || open.kind != SegmentKind.WORK ||
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

	fun expenses(entryId: Long) = dao.observeExpenses(entryId)

	/*
	 * Expense in EUR (SPEC §4): positive amount, description required, an
	 * optional receipt attachment. Tied to the open segment when the
	 * activity has one (e.g. fuel during a travel).
	 */
	suspend fun addExpense(entryId: Long, amountCents: Long, description: String, receiptId: Long? = null): Long =
		db.withTransaction {
			if (amountCents <= 0)
				throw ValidationException(Reason.INVALID_AMOUNT)
			val text = description.trim().ifEmpty { throw ValidationException(Reason.BLANK_VALUE) }
			val e = load(entryId)
			val open = if (e.mode != null) dao.segments(entryId).lastOrNull { it.endUtcMs == null && !it.planned } else null
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
	 * Days in [from, to] that need no day-start prompt: an activity started
	 * or an absence on them; today too while an activity has a segment open
	 * (e.g. started before midnight).
	 */
	suspend fun handledDays(from: LocalDate, to: LocalDate, zone: ZoneId): Set<LocalDate> {
		val out = HashSet<LocalDate>()
		val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
		val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
		for (s in dao.absenceSegmentsBetween(fromMs, toMs)) {
			var d = Instant.ofEpochMilli(s.startUtcMs).atZone(zone).toLocalDate()
			val last = Instant.ofEpochMilli(maxOf(s.startUtcMs, (s.endUtcMs ?: s.startUtcMs) - 1)).atZone(zone).toLocalDate()
			while (!d.isAfter(last)) {
				if (d in from..to) out += d
				d = d.plusDays(1)
			}
		}
		dao.entryStartsBetween(fromMs, toMs).forEach { out += Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
		val today = LocalDate.now(clock.withZone(zone))
		if (today in from..to && dao.activeEntries().isNotEmpty())
			out += today
		return out
	}

	/*
	 * "Arrived?" at a client site during TRAVEL: the travelling activity takes
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
		quick(entryId, QuickAction.WORK, atUtcMs)
	}

	/* "Still working?" answered yes. */
	suspend fun confirm(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		val now = clock.millis()
		dao.updateEntry(e.copy(lastConfirmedUtcMs = now, unansweredReminders = 0, updatedUtcMs = now))
	}

	/*
	 * Stop backdated to the last confirmation ("stop when I last said yes").
	 * The open segment ends at max(open start, last confirmation), never in
	 * the future; without a confirmation it behaves as a plain stop.
	 */
	suspend fun stopAtLastConfirmation(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		val now = clock.millis()
		val at = minOf(e.lastConfirmedUtcMs ?: now, now)
		command(entryId, ActivityCommand.Stop(Instant.ofEpochMilli(writeInstant(entryId, at))), exact = true)
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

	/* The description typed in the stop sheet (issue #93); the rest of the details stays. */
	suspend fun setActivity(entryId: Long, activity: String) = db.withTransaction {
		val e = load(entryId)
		dao.updateEntry(e.copy(activity = activity.trim().ifEmpty { null }, updatedUtcMs = clock.millis()))
		verify(entryId)
	}

	/* A segment typed by hand: valid times, the kind's rules; starts not after [now], ends not after [limit] */
	private fun checkSegment(s: SegmentInput, now: Long, limit: Long) {
		if (s.startUtcMs < s.endUtcMs && s.endUtcMs - s.startUtcMs < minLengthMs())
			throw ValidationException(Reason.TOO_SHORT)
		if (s.startUtcMs >= s.endUtcMs || s.startUtcMs > now || s.endUtcMs > limit ||
			(s.kmDeclared != null && (s.kmDeclared.isNaN() || s.kmDeclared < 0 || s.kind != SegmentKind.TRAVEL)))
			throw ValidationException(Reason.INVALID_SEGMENT)
	}

	/*
	 * Manual segment added afterwards: TRAVEL or PAUSE in an activity (WORK
	 * too once it is closed), ABSENCE in an absence only; in the past and
	 * not overlapping.
	 */
	suspend fun addManualSegment(
		entryId: Long,
		kind: SegmentKind,
		startUtcMs: Long,
		endUtcMs: Long,
		kmDeclared: Double? = null,
		/* the other segments it overlaps make room (issue #40), after the user confirmed */
		resolve: Boolean = false,
	): Long = db.withTransaction {
		val e = load(entryId)
		val now = clock.millis()
		/* an absence may lie ahead (planned holidays, ACTIVITY-MODEL.md 3.1) */
		val limit = if (e.kind == ActivityKind.ABSENCE) Long.MAX_VALUE else now
		checkSegment(SegmentInput(kind, startUtcMs, endUtcMs, kmDeclared), limit, limit)
		val allowed = when (e.kind) {
			ActivityKind.ABSENCE -> kind == SegmentKind.ABSENCE
			ActivityKind.ACTIVITY -> kind != SegmentKind.ABSENCE && e.status != ActivityStatus.TODO && (kind != SegmentKind.WORK || e.status == ActivityStatus.CLOSED)
		}
		if (!allowed)
			throw ValidationException(Reason.INVALID_SEGMENT)
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
		verifyOrOverlap(entryId)
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

	/* the activity's reference values, in order */
	suspend fun externalRefValues(entryId: Long): List<String> = dao.externalRefs(listOf(entryId)).map { it.value }

	/* a reference added unless the activity has it already (imports) */
	suspend fun ensureExternalRef(entryId: Long, value: String) = db.withTransaction {
		val v = value.trim()
		if (v.isNotEmpty() && dao.externalRefs(listOf(entryId)).none { it.value == v })
			addExternalRef(entryId, v)
	}

	suspend fun removeExternalRef(entryId: Long, refId: Long) = db.withTransaction {
		val e = load(entryId)
		dao.deleteExternalRef(refId)
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
	}

	/*
	 * Segments are editable and deletable (backlog 11, revising A13): the
	 * values entered replace the recorded ones (owner, 2026-10-19). The
	 * running segment and an activity's last real segment cannot be removed;
	 * a planned one can.
	 */
	suspend fun deleteSegment(segmentId: Long) = db.withTransaction {
		val s = dao.segment(segmentId) ?: throw ValidationException(Reason.NOT_FOUND)
		if (!s.planned && (s.endUtcMs == null || dao.segments(s.entryId).count { !it.planned } <= 1))
			throw ValidationException(Reason.LAST_SEGMENT)
		val e = load(s.entryId)
		dao.deleteSegment(segmentId)
		dao.updateEntry(e.copy(updatedUtcMs = clock.millis()))
		verify(s.entryId)
	}

	/*
	 * A closed segment edited. Its end may lie up to 60 minutes ahead (4.1)
	 * when it is the activity's last segment and times are editable; every
	 * other end stays in the past. Planned and absence segments take any time.
	 */
	suspend fun updateSegment(segmentId: Long, startUtcMs: Long, endUtcMs: Long, kmDeclared: Double? = null, resolve: Boolean = false) = db.withTransaction {
		val s = dao.segment(segmentId) ?: throw ValidationException(Reason.NOT_FOUND)
		if (s.endUtcMs == null)
			throw ValidationException(Reason.INVALID_SEGMENT, "running segment")
		val now = clock.millis()
		val last = dao.segments(s.entryId).filter { it.planned == s.planned }.maxByOrNull { it.startUtcMs }?.id == s.id
		val ahead = s.planned || s.kind == SegmentKind.ABSENCE
		val limit = when {
			ahead -> Long.MAX_VALUE
			last -> FutureClose.limit(Instant.ofEpochMilli(now), times().editableTimes).toEpochMilli()
			else -> now
		}
		checkSegment(SegmentInput(s.kind, startUtcMs, endUtcMs, kmDeclared), if (ahead) Long.MAX_VALUE else now, limit)
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
		dao.updateEntry(e.copy(updatedUtcMs = now))
		verifyOrOverlap(s.entryId)
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
		verifyOrOverlap(s.entryId)
	}

	/* Tags (backlog 13): the activity's tags, all tags in use (suggestions), replace the activity's. */
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

	/* Time recorded so far (open segments up to now); planned ones never count. */
	suspend fun recordedMs(entryId: Long): Long {
		val now = clock.millis()
		return dao.segments(entryId).filter { !it.planned }.sumOf { maxOf(0L, (it.endUtcMs ?: now) - it.startUtcMs) }
	}

	/* Segments of the activity that [startUtcMs, endUtcMs) would overlap, [except] left out (issue #40). */
	suspend fun segmentConflicts(entryId: Long, except: Long?, startUtcMs: Long, endUtcMs: Long): List<SegmentEntity> {
		val segs = dao.segments(entryId).filter { it.id != except && !it.planned }
		val hit = SegmentOverlap.conflicts(segs.map { Span(it.id, it.startUtcMs, it.endUtcMs) }, startUtcMs, endUtcMs).map { it.id }.toSet()
		return segs.filter { it.id in hit }
	}

	/* The overlapped segments are shortened, split or dropped (SegmentOverlap). */
	private suspend fun makeRoom(entryId: Long, except: Long?, startUtcMs: Long, endUtcMs: Long) {
		val segs = dao.segments(entryId).filter { it.id != except && !it.planned }.associateBy { it.id }
		val changes = SegmentOverlap.resolve(segs.values.map { Span(it.id, it.startUtcMs, it.endUtcMs) }, startUtcMs, endUtcMs, MIN_LENGTH_MS)
		for (c in changes) {
			val o = segs.getValue(c.id)
			when (c) {
				is SpanChange.Delete -> dao.deleteSegment(o.id)
				is SpanChange.Resize -> dao.updateSegment(o.copy(startUtcMs = c.start, endUtcMs = c.end))
				is SpanChange.Split -> {
					dao.updateSegment(o.copy(endUtcMs = c.firstEnd))
					/* the second half is a new segment: a new sync identity too (unique since schema v16) */
					dao.insertSegment(o.copy(id = 0, uid = SyncIds.random(), startUtcMs = c.secondStart, kmDeclared = null, kmEstimated = null))
				}
			}
		}
	}

	/* What deleting an activity involves (issue #40): attachments block deleting it for good; an imported one; what a plugin sent goes there too. */
	data class Deletion(val attachments: Int, val ticket: String?, val sent: Boolean = false)

	suspend fun deletion(entryId: Long): Deletion {
		val e = load(entryId)
		return Deletion(dao.attachmentCount(entryId), e.activity.takeIf { e.externalKey != null }, db.pluginDao().sentAll(entryId).isNotEmpty())
	}

	/* what a plugin sent for the activity is deleted on the remote side at the next sync (issue #48) */
	private suspend fun keepRemoteIds(e: TimeEntryEntity) {
		val company = e.companyId ?: return
		for (r in db.pluginDao().sentAll(e.id))
			db.pluginDao().recordDeleted(RemoteDeletedEntity(company, r.pluginId, r.remoteId, clock.millis()))
	}

	/*
	 * An activity under one minute is a mistake (owner, 2026-10-18): stopping
	 * it from the app offers to discard it. Only without attachments or
	 * expenses, so nothing the user added is lost.
	 */
	suspend fun isDiscardable(entryId: Long): Boolean {
		val e = dao.entry(entryId) ?: return false
		return e.kind == ActivityKind.ACTIVITY && e.deletedUtcMs == null &&
			recordedMs(entryId) < minLengthMs() && dao.attachmentCount(entryId) == 0 && dao.expenseCount(entryId) == 0
	}

	/*
	 * Discarding undoes the mistaken start, not the work planned before it:
	 * a TODO started by mistake ([wasTodo]) goes back to TODO (its real
	 * segments go, what was sent for them is deleted remotely); an activity
	 * born running goes to the bin, restorable, never deleted for good.
	 */
	suspend fun discard(entryId: Long) = db.withTransaction {
		val e = load(entryId)
		if (!isDiscardable(entryId))
			throw ValidationException(Reason.TOO_SHORT, "entry $entryId is not discardable")
		val segments = dao.segments(entryId)
		if (!wasTodo(e, segments)) {
			delete(entryId)
			return@withTransaction
		}
		keepRemoteIds(e)
		db.pluginDao().sentAll(entryId).forEach { db.pluginDao().forgetSent(entryId, it.pluginId, it.blockKey) }
		segments.filter { !it.planned }.forEach { dao.deleteSegment(it.id) }
		dao.updateEntry(
			e.copy(
				status = ActivityStatus.TODO, mode = null, closedUtcMs = null, plannedEndUtcMs = null, lastConfirmedUtcMs = null,
				unansweredReminders = 0, updatedUtcMs = clock.millis(),
			),
		)
		verify(entryId)
	}

	companion object {
		private val DEFAULTS_WINDOW: Duration = Duration.ofDays(30)
		const val MIN_LENGTH_MS = 60_000L
		/* the end of a running segment when checking overlaps: it goes on */
		const val RUNNING_END = Long.MAX_VALUE
		/* the longest absence: a year (issue #158) */
		const val MAX_ABSENCE_DAYS = 366
	}

	private suspend fun load(entryId: Long): TimeEntryEntity =
		dao.entry(entryId) ?: throw ValidationException(Reason.NOT_FOUND, "entry $entryId")

	/* Company of an activity: the client's company; an explicit different company is rejected. */
	private suspend fun companyFor(companyId: Long?, clientId: Long?): Long? {
		if (clientId == null)
			return companyId
		val client = master.client(clientId) ?: throw ValidationException(Reason.NOT_FOUND, "client $clientId")
		if (companyId != null && companyId != client.companyId)
			throw ValidationException(Reason.CLIENT_COMPANY_MISMATCH)
		return client.companyId
	}

	/*
	 * Instant for backdated transitions: [requested], not later than now,
	 * but never before the latest recorded boundary, so neither a backwards
	 * clock change nor a backdated command can create overlaps.
	 */
	private suspend fun writeInstant(entryId: Long, requested: Long): Long {
		val latest = dao.segments(entryId).filter { !it.planned }.maxOfOrNull { maxOf(it.startUtcMs, it.endUtcMs ?: it.startUtcMs) }
		val now = clock.millis()
		return maxOf(minOf(requested, now), latest ?: Long.MIN_VALUE)
	}

	private suspend fun verifyOrOverlap(entryId: Long) {
		try {
			verify(entryId)
		} catch (x: InvariantViolationException) {
			throw ValidationException(Reason.OVERLAPPING_SEGMENTS, x.message)
		}
	}

	private suspend fun verify(entryId: Long) {
		val e = load(entryId)
		val segs = dao.segments(entryId).map {
			Segment(it.kind, Instant.ofEpochMilli(it.startUtcMs), it.endUtcMs?.let(Instant::ofEpochMilli), it.planned)
		}
		/* overlaps a sync brought (issue #119) do not stop the commands; once fixed the mark goes */
		EntryInvariants.require(entryId, e.status, e.mode, segs, overlaps = e.needsReview)
		if (e.needsReview && EntryInvariants.firstOverlap(segs.filter { !it.planned }) == null)
			dao.updateEntry(e.copy(needsReview = false))
		if (e.clientId != null)
			check(master.client(e.clientId)?.companyId == e.companyId) { "entry $entryId: company != client company" }
	}
}

/*
 * Whether [e] was a TODO before it started, for "discard" (there is no
 * column for it): the fields only a to-do has (a plan, a due date, a
 * priority, a key of the management system), or created before its first
 * real segment. An activity born running ("Lavoro", "Viaggio", an import,
 * "Passata") is created at or after its first start: a backdated or
 * rounded start lies before the creation, never after it.
 */
fun wasTodo(e: TimeEntryEntity, segments: List<SegmentEntity>): Boolean {
	if (e.planEpochDay != null || e.dueEpochDay != null || e.priority > 0 || e.externalKey != null)
		return true
	val first = segments.filter { !it.planned }.minOfOrNull { it.startUtcMs } ?: return true
	return e.createdUtcMs < first
}

/* "Untick" (3): an activity, closed, not deleted, without real segments goes back to TODO. */
fun canUntick(e: TimeEntryEntity, segments: List<SegmentEntity>): Boolean =
	e.kind == ActivityKind.ACTIVITY && e.status == ActivityStatus.CLOSED && e.deletedUtcMs == null && segments.none { !it.planned }
