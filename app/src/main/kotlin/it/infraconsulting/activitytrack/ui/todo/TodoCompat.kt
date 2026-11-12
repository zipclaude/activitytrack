/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.todo

import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.data.repo.AbsenceRequest
import it.infraconsulting.activitytrack.data.repo.EntryRepository
import it.infraconsulting.activitytrack.data.repo.TodoRequest
import it.infraconsulting.activitytrack.data.repo.isOpenTodo
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/*
 * TODO(activity-1.0-ui): the 0.9 to-do screens (list, dialog, agenda,
 * widget, home tabs) read TODO activities through these names until the UI
 * wave rewrites them on EntryRepository (docs/ACTIVITY-MODEL.md 8). The
 * to-do table is gone: a "to-do" here is a time_entry row with status TODO
 * (or CLOSED once ticked, deleted once "ignored"), and an absence one of
 * kind ABSENCE. Nothing new should use this file.
 */
typealias TodoEntity = TimeEntryEntity

enum class TodoKind { TASK, ABSENCE }

val TimeEntryEntity.title: String get() = activity.orEmpty()
val TimeEntryEntity.done: Boolean get() = status == ActivityStatus.CLOSED
val TimeEntryEntity.doneUtcMs: Long? get() = closedUtcMs
val TimeEntryEntity.ignoredUtcMs: Long? get() = deletedUtcMs
/* the references are external_ref rows since 1.0 */
val TimeEntryEntity.refs: String get() = ""
/* the colour comes from the client or the company since 1.0 */
val TimeEntryEntity.color: Int? get() = null
/* a multi-day absence is one segment per day since 1.0 */
val TimeEntryEntity.planEndEpochDay: Long? get() = null
val TimeEntryEntity.todoKind: TodoKind get() = if (kind == ActivityKind.ABSENCE) TodoKind.ABSENCE else TodoKind.TASK
/* an activity is no longer started from a to-do: it is the to-do */
val TimeEntryEntity.todoId: Long? get() = null

fun TimeEntryEntity.isOpen(): Boolean = isOpenTodo()

/*
 * A TODO activity as the 0.9 to-do dialog edits it: [title] the
 * description, [priority] 0..5. An absence goes through createAbsence.
 */
fun todoDraft(
	id: Long = 0, companyId: Long?, clientId: Long?, title: String, notes: String?, dueEpochDay: Long?, dueTimeMin: Int?,
	priority: Int?, planEpochDay: Long?, planStartMin: Int?, durationMin: Int?, base: TimeEntryEntity? = null,
): TimeEntryEntity = (base ?: TimeEntryEntity(
	uid = null, companyId = null, clientId = null, status = ActivityStatus.TODO, zoneId = ZoneId.systemDefault().id,
	createdUtcMs = 0, updatedUtcMs = 0,
)).copy(
	id = id, companyId = companyId, clientId = clientId, activity = title, notes = notes, dueEpochDay = dueEpochDay, dueTimeMin = dueTimeMin,
	priority = priority ?: 0, planEpochDay = planEpochDay, planStartMin = planStartMin, durationMin = durationMin,
)

/* The 0.9 TodoRepository on EntryRepository. TODO(activity-1.0-ui) */
class TodoRepository(private val entries: EntryRepository) {
	val open: Flow<List<TimeEntryEntity>> = entries.todos
	val ignored: Flow<List<TimeEntryEntity>> = entries.deleted.map { l -> l.filter { it.status == ActivityStatus.TODO } }
	val all: Flow<List<TimeEntryEntity>> = combine(open, ignored) { a, b -> a + b }

	/* ticked to-dos are closed activities without segments: the Home "Chiuse" tab shows the closed activities */
	fun done(@Suppress("UNUSED_PARAMETER") limit: Int): Flow<List<TimeEntryEntity>> = flowOf(emptyList())

	fun observe(id: Long) = entries.entry(id)
	suspend fun find(id: Long): TimeEntryEntity? = entries.find(id)

	fun dueBy(todos: List<TimeEntryEntity>, day: LocalDate) = entries.dueBy(todos, day)

	suspend fun planned(): List<TimeEntryEntity> = entries.plannedTodos()

	suspend fun save(t: TimeEntryEntity): Long = entries.saveTodo(
		t.id.takeIf { it != 0L },
		TodoRequest(
			companyId = t.companyId, clientId = t.clientId, activity = t.activity, notes = t.notes, roleId = t.roleId,
			dueEpochDay = t.dueEpochDay, dueTimeMin = t.dueTimeMin, planEpochDay = t.planEpochDay, planStartMin = t.planStartMin,
			durationMin = t.durationMin, priority = t.priority, externalKey = t.externalKey, pluginFields = t.pluginFields,
		),
	)

	/* an absence of the 0.9 dialog: a whole day, or [startMin] for [durationMin] minutes */
	suspend fun saveAbsence(fromEpochDay: Long, toEpochDay: Long?, startMin: Int?, durationMin: Int?, notes: String?, kind: AbsenceKind = AbsenceKind.ABSENCE): Long =
		entries.createAbsence(
			AbsenceRequest(
				absenceKind = kind, notes = notes, fromEpochDay = fromEpochDay, toEpochDay = toEpochDay ?: fromEpochDay,
				startMin = startMin, endMin = startMin?.let { s -> minOf(1440, s + (durationMin ?: 60)) },
			),
		)

	suspend fun delete(id: Long) = entries.delete(id)

	suspend fun setDone(id: Long, done: Boolean) {
		if (done) entries.tick(id)
	}

	suspend fun setState(id: Long, done: Boolean, ignored: Boolean) {
		val e = entries.find(id) ?: return
		if (done && e.status == ActivityStatus.TODO) entries.tick(id)
		if (ignored && e.deletedUtcMs == null) entries.delete(id)
		if (!ignored && e.deletedUtcMs != null) entries.restore(id)
	}

	suspend fun restore(t: TimeEntryEntity) = entries.putBack(t)
	suspend fun clearPlan(id: Long) = entries.clearPlan(id)
	suspend fun setIgnored(id: Long, ignored: Boolean) = if (ignored) entries.delete(id) else entries.restore(id)
	suspend fun postpone(id: Long, minutes: Int) = entries.postpone(id, minutes)
	suspend fun unworked(day: LocalDate, zone: ZoneId) = entries.unworked(day, zone)
	suspend fun replan(ids: Collection<Long>, day: LocalDate) = entries.replan(ids, day)
	suspend fun markPlanReminded(id: Long, startMs: Long) = entries.markPlanReminded(id, startMs)

	/* the activities of a to-do: it is its own activity since 1.0 */
	suspend fun entriesOf(@Suppress("UNUSED_PARAMETER") id: Long): Int = 0

	companion object {
		const val MAX_ABSENCE_DAYS = EntryRepository.MAX_ABSENCE_DAYS
	}
}
