/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.EntryDetails
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.SegmentInput
import it.infraconsulting.activitytrack.data.repo.TodoRequest
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.FutureClose
import it.infraconsulting.activitytrack.domain.activity.ReturnChoice
import it.infraconsulting.activitytrack.domain.activity.TravelFromWork
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.policy.Field as PField
import it.infraconsulting.activitytrack.domain.policy.Policy
import it.infraconsulting.activitytrack.domain.time.TimeGrid
import it.infraconsulting.activitytrack.domain.time.atMinute
import it.infraconsulting.activitytrack.ui.ActionViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/* The to-do fields of a TODO activity as the detail dialog edits them (ACTIVITY-MODEL.md 1.1). */
data class TodoFields(
	val planEpochDay: Long?,
	val planStartMin: Int?,
	val durationMin: Int?,
	val dueEpochDay: Long?,
	val dueTimeMin: Int?,
	val priority: Int,
)

/*
 * The detail dialog of one activity, every state (ACTIVITY-MODEL.md 8):
 * the commands its state allows, its fields, segments, references, tags.
 */
class EntryViewModel(
	val entryId: Long,
	private val entries: EntryRepository,
	master: MasterDataRepository,
	editableTimes: Flow<Boolean> = flowOf(false),
	/* tags typed freely besides the master data's (issue #133) */
	tagsFreeText: Flow<Boolean> = flowOf(true),
	/* the policy in force: the fields it makes required (issue #48) */
	private val policy: () -> Policy = { Policy.NONE },
	/* the locality proposed for an activity without one; null: geo services off */
	private val prefill: LocalityPrefill? = null,
) : ActionViewModel() {
	/* segments editable and deletable only with the option on (M17) */
	val editableTimes = editableTimes.state(false)
	val entry = entries.entry(entryId).state(null)
	val segments = entries.segments(entryId).state(emptyList())
	val refs = entries.externalRefs(entryId).state(emptyList())
	val tags = entries.tags(entryId).state(emptyList())
	/* suggestions: every tag in use, most used first */
	val allTags = entries.allTags.state(emptyList())
	/* the tags of the master data (issue #133) */
	val masterTags = master.tags.state(emptyList())
	val tagsFreeText = tagsFreeText.state(true)
	private val masterRepo = master
	val clients = master.clients.state(emptyList())
	val companies = master.companies.state(emptyList())
	val roles = master.roles.state(emptyList())
	val presets = master.presets.state(emptyList())

	/* the locality proposed once (site name, else municipality); the form takes it while its field is empty */
	private val _proposedLocality = MutableStateFlow<String?>(null)
	val proposedLocality = _proposedLocality.asStateFlow()

	init {
		if (prefill != null)
			viewModelScope.launch {
				val e = entries.find(entryId) ?: return@launch
				if (e.kind == ActivityKind.ACTIVITY && e.locality.isNullOrBlank() && e.deletedUtcMs == null)
					_proposedLocality.value = prefill.propose(e)
			}
	}

	/* ------------------------------------------------------------ Salva */

	/* a value of the form the screen could not read */
	fun invalid(reason: Reason) = fail(reason)

	private fun missingRequired(d: EntryDetails): Boolean = policy().missingRequired(
		mapOf(
			PField.COMPANY to (d.companyId != null), PField.CLIENT to (d.clientId != null), PField.ROLE to (d.roleId != null),
			PField.LOCALITY to !d.locality.isNullOrBlank(), PField.PLACE to (d.locationKind != null), PField.NOTES to !d.notes.isNullOrBlank(),
			PField.CONTACT to (d.contactId != null),
		),
	) != null

	/* an activity not TODO: its descriptive fields */
	fun save(d: EntryDetails, done: () -> Unit) {
		if (missingRequired(d))
			return fail(Reason.POLICY_REQUIRED)
		run(done) { entries.updateDetails(entryId, d) }
	}

	/* a TODO activity: the to-do fields and its planned segments as they are, then the rest of the details */
	fun saveTodo(d: EntryDetails, t: TodoFields, done: () -> Unit) {
		if (missingRequired(d))
			return fail(Reason.POLICY_REQUIRED)
		run(done) {
			val e = entries.find(entryId) ?: throw ValidationException(Reason.NOT_FOUND)
			entries.saveTodo(entryId, todoRequest(e, plannedOf(entries.segments(entryId).first())).copy(
				companyId = d.companyId, clientId = d.clientId, activity = d.activity, notes = d.notes, roleId = d.roleId,
				planEpochDay = t.planEpochDay, planStartMin = t.planStartMin, durationMin = t.durationMin,
				dueEpochDay = t.dueEpochDay, dueTimeMin = t.dueTimeMin, priority = t.priority,
			))
			entries.updateDetails(entryId, d)
		}
	}

	/* an absence: company (null = every company), kind, notes; its days are its segments */
	fun saveAbsence(companyId: Long?, kind: AbsenceKind, notes: String, done: () -> Unit) =
		run(done) { entries.updateAbsence(entryId, companyId, kind, notes) }

	private fun plannedOf(segs: List<SegmentEntity>) =
		segs.filter { it.planned && it.endUtcMs != null }.map { SegmentInput(it.kind, it.startUtcMs, it.endUtcMs!!, it.kmDeclared) }

	private fun todoRequest(e: TimeEntryEntity, planned: List<SegmentInput>) = TodoRequest(
		companyId = e.companyId, clientId = e.clientId, activity = e.activity, notes = e.notes, roleId = e.roleId,
		dueEpochDay = e.dueEpochDay, dueTimeMin = e.dueTimeMin, planEpochDay = e.planEpochDay, planStartMin = e.planStartMin,
		durationMin = e.durationMin, priority = e.priority, planned = planned, externalKey = e.externalKey, pluginFields = e.pluginFields,
	)

	/* ------------------------------------------------------------ commands (3) */

	fun startTodo(kind: SegmentKind) = run { entries.startTodo(entryId, kind) }

	fun resume(kind: SegmentKind) = run { entries.resume(entryId, kind) }

	/* TRAVEL while working asks first "Hai completato l'attività lavorativa?" (3) */
	private val _travelAsk = MutableStateFlow(false)
	val travelAsk = _travelAsk.asStateFlow()

	fun switchTo(kind: SegmentKind) {
		val e = entry.value
		if (kind == SegmentKind.TRAVEL && e != null && asksTravelQuestion(e))
			_travelAsk.value = true
		else
			run { entries.switchTo(entryId, kind) }
	}

	fun answerTravel(workDone: Boolean) {
		_travelAsk.value = false
		run { entries.travelFromWork(entryId, workDone) }
	}

	fun cancelTravel() {
		_travelAsk.value = false
	}

	/* Stop: the end (now, or a chosen time ahead with editable times, 4.1: see [endOn]) and the return trip's choices (3.2). */
	data class StopPrompt(val choices: List<ReturnChoice>)

	private val _stopPrompt = MutableStateFlow<StopPrompt?>(null)
	val stopPrompt = _stopPrompt.asStateFlow()

	fun askStop() {
		clearError()
		viewModelScope.launch {
			val choices = entries.returnChoices(entryId)
			if (choices.isEmpty() && !editableTimes.value)
				run { entries.stop(entryId) }
			else
				_stopPrompt.value = StopPrompt(choices)
		}
	}

	fun cancelStop() {
		clearError()
		_stopPrompt.value = null
	}

	/*
	 * [hhmm] the end typed ("" = now, rounded when saving rounded times):
	 * the instant at that time of day nearest to now ([endOn]), refused
	 * beyond the limit of 4.1.
	 */
	fun stop(choice: ReturnChoice, hhmm: String, zone: ZoneId) {
		val at = if (hhmm.isBlank()) null else chosenEnd(hhmm, zone, choice == ReturnChoice.TRAVEL_BACK) ?: return
		run({ _stopPrompt.value = null }) {
			when (choice) {
				ReturnChoice.TRAVEL_BACK -> entries.travelBack(entryId, at)
				ReturnChoice.STOP -> entries.stop(entryId, at)
				ReturnChoice.STOP_WITH_RETURN -> entries.stopWithReturn(entryId, at)
			}
		}
	}

	private fun chosenEnd(hhmm: String, zone: ZoneId, notFuture: Boolean): Long? {
		val min = it.infraconsulting.activitytrack.ui.common.parseHhMm(hhmm)?.takeIf { it < 1440 }
		if (min == null) {
			fail(Reason.INVALID_TIME)
			return null
		}
		val now = Instant.now()
		val today = LocalDate.now(zone)
		val at = endOn(min, today, zone, now, if (notFuture) false else editableTimes.value)
		if (at == null)
			fail(Reason.FUTURE_TIME)
		return at
	}

	/* ------------------------------------------------------------ the other commands */

	fun tick() = run { entries.tick(entryId) }

	fun untick() = run { entries.untick(entryId) }

	/* Reopening asks first (3): a new open segment, the gap stays a gap. */
	private val _reopenAsk = MutableStateFlow<SegmentKind?>(null)
	val reopenAsk = _reopenAsk.asStateFlow()

	fun askReopen(kind: SegmentKind) {
		_reopenAsk.value = kind
	}

	fun cancelReopen() {
		_reopenAsk.value = null
	}

	fun reopen() {
		val kind = _reopenAsk.value ?: return
		_reopenAsk.value = null
		run { entries.reopen(entryId, kind) }
	}

	/* Deleting ("eliminata", restorable from the bin): what it involves, asked first. */
	private val _deletion = MutableStateFlow<EntryRepository.Deletion?>(null)
	val deletion = _deletion.asStateFlow()

	fun askDelete() = viewModelScope.launch { _deletion.value = entries.deletion(entryId) }

	fun cancelDelete() {
		_deletion.value = null
	}

	fun deleteEntry(done: () -> Unit) {
		_deletion.value = null
		run(done) { entries.delete(entryId) }
	}

	fun restore() = run { entries.restore(entryId) }

	/* ------------------------------------------------------------ segments */

	/*
	 * A segment saved over others of this activity (issue #40): the
	 * overlapped ones are shown first; confirming shortens, splits or drops them.
	 */
	class OverlapPrompt(val conflicts: List<SegmentEntity>, val apply: suspend () -> Unit, val done: () -> Unit)

	private val _overlap = MutableStateFlow<OverlapPrompt?>(null)
	val overlap = _overlap.asStateFlow()

	private fun saveOrAsk(except: Long?, startMs: Long, endMs: Long, done: () -> Unit, save: suspend (Boolean) -> Unit) = run {
		val conflicts = entries.segmentConflicts(entryId, except, startMs, endMs)
		if (conflicts.isEmpty()) {
			save(false)
			done()
		} else
			_overlap.value = OverlapPrompt(conflicts, { save(true) }, done)
	}

	fun confirmOverlap() {
		val p = _overlap.value ?: return
		_overlap.value = null
		run(p.done) { p.apply() }
	}

	fun cancelOverlap() {
		_overlap.value = null
	}

	/* a real segment typed by hand; for a TODO activity a planned one (1.2) */
	fun addSegment(kind: SegmentKind, startMs: Long, endMs: Long, km: Double?, done: () -> Unit) {
		val e = entry.value
		if (e != null && e.status == ActivityStatus.TODO && e.kind == ActivityKind.ACTIVITY)
			run(done) {
				val current = entries.find(entryId) ?: throw ValidationException(Reason.NOT_FOUND)
				val planned = plannedOf(entries.segments(entryId).first()) + SegmentInput(kind, startMs, endMs, km)
				entries.saveTodo(entryId, todoRequest(current, planned))
			}
		else
			saveOrAsk(null, startMs, endMs, done) { resolve -> entries.addManualSegment(entryId, kind, startMs, endMs, km, resolve) }
	}

	/* [note] = the description of a pause (issue #28), null = unchanged */
	fun updateSegment(segmentId: Long, startMs: Long, endMs: Long, km: Double?, note: String? = null, done: () -> Unit) =
		saveOrAsk(segmentId, startMs, endMs, done) { resolve ->
			entries.updateSegment(segmentId, startMs, endMs, km, resolve)
			note?.let { entries.setSegmentNote(segmentId, it) }
		}

	/* The running segment: only its start moves (issue #57). */
	fun moveRunningStart(segmentId: Long, startMs: Long, done: () -> Unit) =
		saveOrAsk(segmentId, startMs, EntryRepository.RUNNING_END, done) { resolve -> entries.moveRunningStart(segmentId, startMs, resolve) }

	fun setSegmentNote(segmentId: Long, note: String, done: () -> Unit) = run(done) { entries.setSegmentNote(segmentId, note) }

	fun deleteSegment(segmentId: Long, done: () -> Unit = {}) = run(done) { entries.deleteSegment(segmentId) }

	fun setTags(tags: List<String>) = run { entries.setTags(entryId, tags) }

	/* a free tag of this activity kept in the master data, with its colour (issue #133) */
	fun saveTag(tag: it.infraconsulting.activitytrack.data.db.TagEntity, done: () -> Unit) = run(done) { masterRepo.saveTag(tag) }

	/* Minutes of [day] taken by other segments (all activities, [except] left out): hatched in the grid. */
	suspend fun busyOn(day: LocalDate, except: Long?, zone: ZoneId): List<IntRange> {
		val from = day.atStartOfDay(zone).toInstant()
		val to = day.plusDays(1).atStartOfDay(zone).toInstant()
		val segs = entries.segmentsTouching(from.toEpochMilli(), to.toEpochMilli()).filter { it.id != except && !it.planned }
		return TimeGrid.busyMinutes(
			segs.map { Instant.ofEpochMilli(it.startUtcMs) to it.endUtcMs?.let(Instant::ofEpochMilli) },
			day, zone, Instant.now(),
		)
	}

	fun addRef(value: String, done: () -> Unit) = run(done) { entries.addExternalRef(entryId, value) }
	fun removeRef(refId: Long) = run { entries.removeExternalRef(entryId, refId) }

	companion object {
		/*
		 * An end typed as a time of day, without a date: the instant at [min]
		 * of yesterday, today or tomorrow nearest to now, a tie going to the
		 * earlier (at 23:50 "00:20" is tomorrow, at 00:10 "23:50" is
		 * yesterday, at 10:00 "09:00" is an hour ago, not 23 hours ahead);
		 * null when that one lies beyond the 4.1 limit. With the limit at 24
		 * hours (issue #179) a time alone reaches 12 hours ahead at most; a
		 * later end is typed with its date, editing the last segment.
		 */
		fun endOn(min: Int, today: LocalDate, zone: ZoneId, now: Instant, editableTimes: Boolean): Long? =
			listOf(today.minusDays(1), today, today.plusDays(1))
				.map { it.atMinute(min, zone) }
				.minWithOrNull(compareBy<Instant> { java.time.Duration.between(now, it).abs() }.thenBy { it })
				?.takeIf { FutureClose.accepts(it, now, editableTimes) }
				?.toEpochMilli()

		/* "Hai completato l'attività lavorativa?" applies to a switch to TRAVEL from WORK (3) */
		fun asksTravelQuestion(e: TimeEntryEntity): Boolean =
			TravelFromWork.needsQuestion(
				it.infraconsulting.activitytrack.domain.activity.ActivityState(
					e.kind, e.status, e.mode, e.closedUtcMs?.let(Instant::ofEpochMilli), e.deletedUtcMs?.let(Instant::ofEpochMilli),
				),
			)
	}
}
