/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

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
) : ActionViewModel() {
	val entry = entries.entry(entryId).state(null)
	val segments = entries.segments(entryId).state(emptyList())
	val refs = entries.externalRefs(entryId).state(emptyList())
	val clients = master.clients.state(emptyList())
	val companies = master.companies.state(emptyList())
	val roles = master.roles.state(emptyList())
	val presets = master.presets.state(emptyList())

	fun save(d: EntryDetails, done: () -> Unit) = run(done) { entries.updateDetails(entryId, d) }
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

	fun addSegment(kind: SegmentKind, startMs: Long, endMs: Long, km: Double?, done: () -> Unit) =
		run(done) { entries.addManualSegment(entryId, kind, startMs, endMs, km) }

	fun deleteSegment(segmentId: Long) = run { entries.deleteManualSegment(segmentId) }

	fun addRef(value: String, done: () -> Unit) = run(done) { entries.addExternalRef(entryId, value) }
	fun removeRef(refId: Long) = run { entries.removeExternalRef(entryId, refId) }
}
