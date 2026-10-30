/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import it.infraconsulting.activitytrack.domain.policy.Field as PField
import it.infraconsulting.activitytrack.data.db.SegmentEntity
import java.time.ZoneId
import java.time.LocalDate
import java.time.Instant
import it.infraconsulting.activitytrack.domain.time.TimeGrid
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import androidx.lifecycle.viewModelScope
import it.infraconsulting.activitytrack.data.repo.EntryDetails
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.TodoRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.domain.model.EntryCommand
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.ui.ActionViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* One entry: details, segments, external references (M1 step 7). */
class EntryViewModel(
	val entryId: Long,
	private val entries: EntryRepository,
	master: MasterDataRepository,
	private val todos: TodoRepository,
	editableTimes: Flow<Boolean> = flowOf(false),
	/* tags typed freely besides the master data's (issue #133) */
	tagsFreeText: Flow<Boolean> = flowOf(true),
	/* the policy in force: the fields it makes required (issue #48) */
	private val policy: () -> it.infraconsulting.activitytrack.domain.policy.Policy = { it.infraconsulting.activitytrack.domain.policy.Policy.NONE },
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

	fun save(d: EntryDetails, done: () -> Unit) {
		policy().missingRequired(
			mapOf(
				PField.COMPANY to (d.companyId != null), PField.CLIENT to (d.clientId != null), PField.ROLE to (d.roleId != null),
				PField.LOCALITY to !d.locality.isNullOrBlank(), PField.PLACE to (d.locationKind != null), PField.NOTES to !d.notes.isNullOrBlank(),
				PField.CONTACT to (d.contactId != null),
			),
		)?.let { _ -> return fail(it.infraconsulting.activitytrack.data.repo.ValidationException.Reason.POLICY_REQUIRED) }
		run(done) { entries.updateDetails(entryId, d) }
	}
	/* Completion without activity text: asked first (empty / copy the last one / edit). */
	data class EmptyActivityPrompt(val details: EntryDetails, val lastActivity: String?)

	private val _emptyPrompt = MutableStateFlow<EmptyActivityPrompt?>(null)
	val emptyPrompt = _emptyPrompt.asStateFlow()

	fun complete(d: EntryDetails, done: () -> Unit) {
		viewModelScope.launch {
			try {
				entries.complete(entryId, d)
				clearError()
				finished(done)
			} catch (e: ValidationException) {
				if (e.reason == ValidationException.Reason.COMPLETION_INCOMPLETE)
					_emptyPrompt.value = EmptyActivityPrompt(d, d.clientId?.let { entries.lastActivity(it) })
				else
					fail(e.reason)
			}
		}
	}

	fun completeEmpty(done: () -> Unit) {
		val p = _emptyPrompt.value ?: return
		_emptyPrompt.value = null
		run { entries.complete(entryId, p.details, allowEmpty = true); finished(done) }
	}

	fun completeCopyingLast(done: () -> Unit) {
		val p = _emptyPrompt.value ?: return
		_emptyPrompt.value = null
		run { entries.complete(entryId, p.details.copy(activity = p.lastActivity)); finished(done) }
	}

	/* Completed an entry started from an open to-do: "mark the to-do as done?" (owner decision). */
	data class TodoDonePrompt(val todoId: Long, val title: String, val done: () -> Unit)

	private val _todoPrompt = MutableStateFlow<TodoDonePrompt?>(null)
	val todoPrompt = _todoPrompt.asStateFlow()

	private suspend fun finished(done: () -> Unit) {
		val t = entries.find(entryId)?.todoId?.let { todos.find(it) }
		if (t != null && !t.done) _todoPrompt.value = TodoDonePrompt(t.id, t.title, done) else done()
	}

	fun answerTodo(markDone: Boolean) {
		val p = _todoPrompt.value ?: return
		_todoPrompt.value = null
		viewModelScope.launch {
			if (markDone)
				todos.setDone(p.todoId, true)
			p.done()
		}
	}

	fun dismissEmptyPrompt() {
		_emptyPrompt.value = null
	}
	fun reopen() = run { entries.apply(entryId, EntryCommand.REOPEN) }

	/*
	 * A segment saved over others of this entry (issue #40): the overlapped
	 * ones are shown first; confirming shortens, splits or drops them.
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

	fun addSegment(kind: SegmentKind, startMs: Long, endMs: Long, km: Double?, done: () -> Unit) =
		saveOrAsk(null, startMs, endMs, done) { resolve -> entries.addManualSegment(entryId, kind, startMs, endMs, km, resolve) }

	/* [note] = the description of a pause (issue #28), null = unchanged */
	fun updateSegment(segmentId: Long, startMs: Long, endMs: Long, km: Double?, note: String? = null, done: () -> Unit) =
		saveOrAsk(segmentId, startMs, endMs, done) { resolve ->
			entries.updateSegment(segmentId, startMs, endMs, km, resolve)
			note?.let { entries.setSegmentNote(segmentId, it) }
		}

	/* The running segment: only its start moves (issue #57). */
	fun moveRunningStart(segmentId: Long, startMs: Long, done: () -> Unit) =
		saveOrAsk(segmentId, startMs, EntryRepository.RUNNING_END, done) { resolve -> entries.moveRunningStart(segmentId, startMs, resolve) }

	/* Deleting the whole entry (issue #40): what it involves, asked first. */
	private val _deletion = MutableStateFlow<EntryRepository.Deletion?>(null)
	val deletion = _deletion.asStateFlow()

	fun askDelete() = viewModelScope.launch { _deletion.value = entries.deletion(entryId) }

	fun cancelDelete() {
		_deletion.value = null
	}

	fun deleteEntry(done: () -> Unit) {
		_deletion.value = null
		run(done) { entries.deleteEntry(entryId) }
	}

	fun setSegmentNote(segmentId: Long, note: String, done: () -> Unit) = run(done) { entries.setSegmentNote(segmentId, note) }

	fun deleteSegment(segmentId: Long, done: () -> Unit = {}) = run(done) { entries.deleteSegment(segmentId) }

	fun setTags(tags: List<String>) = run { entries.setTags(entryId, tags) }

	/* a free tag of this entry kept in the master data, with its colour (issue #133) */
	fun saveTag(tag: it.infraconsulting.activitytrack.data.db.TagEntity, done: () -> Unit) = run(done) { masterRepo.saveTag(tag) }

	/* Minutes of [day] taken by other segments (all entries, [except] left out): hatched in the grid. */
	suspend fun busyOn(day: LocalDate, except: Long?, zone: ZoneId): List<IntRange> {
		val from = day.atStartOfDay(zone).toInstant()
		val to = day.plusDays(1).atStartOfDay(zone).toInstant()
		val segs = entries.segmentsTouching(from.toEpochMilli(), to.toEpochMilli()).filter { it.id != except }
		return TimeGrid.busyMinutes(
			segs.map { Instant.ofEpochMilli(it.startUtcMs) to it.endUtcMs?.let(Instant::ofEpochMilli) },
			day, zone, Instant.now(),
		)
	}

	fun addRef(value: String, done: () -> Unit) = run(done) { entries.addExternalRef(entryId, value) }
	fun removeRef(refId: Long) = run { entries.removeExternalRef(entryId, refId) }
}
