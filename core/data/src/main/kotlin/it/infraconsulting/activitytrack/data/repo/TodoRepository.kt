/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
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

	/* Open to-dos due by [day] (overdue included): the home list. */
	fun dueBy(todos: List<TodoEntity>, day: LocalDate): List<TodoEntity> =
		todos.filter { !it.done && it.dueEpochDay != null && it.dueEpochDay <= day.toEpochDay() }

	/* Create ([id] 0) or edit; the client must belong to the company. */
	suspend fun save(t: TodoEntity): Long = db.withTransaction {
		val title = t.title.trim().ifEmpty { throw ValidationException(Reason.BLANK_VALUE) }
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

	suspend fun entriesOf(id: Long): Int = dao.entriesOf(id)
}
