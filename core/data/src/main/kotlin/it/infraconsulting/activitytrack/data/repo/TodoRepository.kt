/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/*
 * To-dos (M4): imported from management systems or created in the app. An
 * entry can start from a to-do; completing it asks whether the to-do is done.
 */
class TodoRepository(private val db: ActivityTrackDatabase, private val clock: Clock) {
	private val dao = db.todoDao()
	private val master = db.masterDataDao()

	val open = dao.observeOpen()
	val all = dao.observeAll()

	fun observe(id: Long) = dao.observe(id)
	suspend fun find(id: Long): TodoEntity? = dao.byId(id)

	/* Open to-dos due or planned by [day] (overdue included): the home list. */
	fun dueBy(todos: List<TodoEntity>, day: LocalDate): List<TodoEntity> =
		todos.filter { t -> !t.done && listOfNotNull(t.dueEpochDay, t.planEpochDay).any { it <= day.toEpochDay() } }

	/* Open planned to-dos (reminders, calendars). */
	suspend fun planned(): List<TodoEntity> = dao.all().filter { !it.done && it.plan() != null }

	/*
	 * Create ([id] 0) or edit; the client must belong to the company; a plan
	 * needs both day and time, and a duration within a day.
	 */
	suspend fun save(t: TodoEntity): Long = db.withTransaction {
		val title = t.title.trim().ifEmpty { throw ValidationException(Reason.BLANK_VALUE) }
		if ((t.planEpochDay == null) != (t.planStartMin == null))
			throw ValidationException(if (t.planEpochDay == null) Reason.INVALID_DATE else Reason.INVALID_TIME)
		if (t.planStartMin != null && t.planStartMin !in 0 until 1440)
			throw ValidationException(Reason.INVALID_TIME)
		if (t.durationMin != null && t.durationMin !in 1..TodoPlan.MAX_DURATION_MIN)
			throw ValidationException(Reason.INVALID_TIME_RANGE)
		t.clientId?.let { c ->
			if (master.client(c)?.companyId != t.companyId)
				throw ValidationException(Reason.CLIENT_COMPANY_MISMATCH)
		}
		val now = clock.millis()
		val clean = t.copy(title = title, notes = t.notes?.trim()?.ifEmpty { null }, refs = t.refs.lines().map(String::trim).filter(String::isNotEmpty).joinToString("\n"), updatedUtcMs = now)
		if (t.id == 0L)
			dao.insert(clean.copy(uid = UUID.randomUUID().toString(), createdUtcMs = now))
		else {
			dao.update(clean)
			t.id
		}
	}

	suspend fun setDone(id: Long, done: Boolean) = db.withTransaction {
		val t = dao.byId(id) ?: throw ValidationException(Reason.NOT_FOUND, "todo $id")
		dao.update(t.copy(done = done, updatedUtcMs = clock.millis()))
	}

	/* The plan moved [minutes] later ("postpone" on the reminder); the duration stays. */
	suspend fun postpone(id: Long, minutes: Int) = db.withTransaction {
		val t = dao.byId(id) ?: throw ValidationException(Reason.NOT_FOUND, "todo $id")
		val p = t.plan()?.postponed(minutes) ?: return@withTransaction
		dao.update(t.copy(planEpochDay = p.day.toEpochDay(), planStartMin = p.startMin, updatedUtcMs = clock.millis()))
	}

	/* The reminder of the plan starting at [startMs] was shown. */
	suspend fun markPlanReminded(id: Long, startMs: Long) = dao.markPlanReminded(id, startMs)

	suspend fun entriesOf(id: Long): Int = dao.entriesOf(id)
}

/* The planned slot of a to-do, if any. */
fun TodoEntity.plan(): TodoPlan? = TodoPlan.of(planEpochDay, planStartMin, durationMin)
