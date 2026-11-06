/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import androidx.room.withTransaction
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.TodoEntity
import it.infraconsulting.activitytrack.data.db.TodoKind
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.todo.TodoPlan
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/*
 * To-dos (M4): imported from management systems or created in the app. An
 * entry can start from a to-do; completing it asks whether the to-do is done.
 */
class TodoRepository(private val db: ActivityTrackDatabase, private val clock: Clock) {
	private val dao = db.todoDao()
	private val master = db.masterDataDao()

	val open = dao.observeOpen()
	val ignored = dao.observeIgnored()
	val all = dao.observeAll()

	/* the done ones, most recently marked first (Home "Chiuse", issue #59) */
	fun done(limit: Int) = dao.observeDone(limit)

	fun observe(id: Long) = dao.observe(id)
	suspend fun find(id: Long): TodoEntity? = dao.byId(id)

	/* Open to-dos due or planned by [day] (overdue included): the home list. */
	fun dueBy(todos: List<TodoEntity>, day: LocalDate): List<TodoEntity> =
		todos.filter { t -> t.isOpen() && listOfNotNull(t.dueEpochDay, t.planEpochDay).any { it <= day.toEpochDay() } }

	/* Open planned to-dos (reminders, calendars); ignored ones are not commitments. */
	suspend fun planned(): List<TodoEntity> = dao.all().filter { it.isOpen() && it.plan() != null }

	/*
	 * Create ([id] 0) or edit; the client must belong to the company; a plan
	 * needs a day (issue #52: the time is optional), a duration needs a start
	 * and stays within a day. An absence (issue #158) is always planned, has
	 * no company, client, due date, priority or references, may have no title,
	 * and may last several days (a whole-day plan with a last day).
	 */
	suspend fun save(t: TodoEntity): Long = db.withTransaction {
		val absence = t.kind == TodoKind.ABSENCE
		val title = t.title.trim().let { if (absence) it else it.ifEmpty { throw ValidationException(Reason.BLANK_VALUE) } }
		if (absence && t.planEpochDay == null)
			throw ValidationException(Reason.INVALID_DATE)
		if (t.planEndEpochDay != null && (t.planEpochDay == null || t.planStartMin != null || t.planEndEpochDay < t.planEpochDay ||
				t.planEndEpochDay - t.planEpochDay >= MAX_ABSENCE_DAYS)
		)
			throw ValidationException(Reason.INVALID_TIME_RANGE)
		if (t.planEpochDay == null && t.planStartMin != null)
			throw ValidationException(Reason.INVALID_DATE)
		if (t.durationMin != null && t.planStartMin == null)
			throw ValidationException(Reason.INVALID_TIME)
		if (t.planStartMin != null && t.planStartMin !in 0 until 1440)
			throw ValidationException(Reason.INVALID_TIME)
		if (t.durationMin != null && t.durationMin !in 1..TodoPlan.MAX_DURATION_MIN)
			throw ValidationException(Reason.INVALID_TIME_RANGE)
		t.clientId?.takeIf { !absence }?.let { c ->
			if (master.client(c)?.companyId != t.companyId)
				throw ValidationException(Reason.CLIENT_COMPANY_MISMATCH)
		}
		val now = clock.millis()
		val clean = t.copy(title = title, notes = t.notes?.trim()?.ifEmpty { null }, refs = t.refs.lines().map(String::trim).filter(String::isNotEmpty).joinToString("\n"), updatedUtcMs = now)
			.let { if (absence) it.copy(companyId = null, clientId = null, dueEpochDay = null, dueTimeMin = null, priority = null, refs = "", externalKey = null) else it.copy(planEndEpochDay = null) }
		if (t.id == 0L)
			dao.insert(clean.copy(uid = UUID.randomUUID().toString(), createdUtcMs = now))
		else {
			dao.update(clean)
			t.id
		}
	}

	/*
	 * A to-do created by hand can be deleted (owner, issue #40); one from a
	 * management system is only ignored, or the next import brings it back.
	 * Its entries stay, without the link.
	 */
	suspend fun delete(id: Long) = db.withTransaction {
		val t = dao.byId(id) ?: return@withTransaction
		if (t.externalKey != null)
			throw ValidationException(ValidationException.Reason.IN_USE, "imported to-do $id")
		dao.delete(id)
	}

	suspend fun setDone(id: Long, done: Boolean) = db.withTransaction {
		val t = dao.byId(id) ?: throw ValidationException(Reason.NOT_FOUND, "todo $id")
		dao.update(t.markedDone(done, clock.millis()))
	}

	/*
	 * The state chosen by hand in the to-do dialog (issue #89): open, done or
	 * ignored, whatever it was before. Planned and overdue follow the dates.
	 */
	suspend fun setState(id: Long, done: Boolean, ignored: Boolean) = db.withTransaction {
		val t = dao.byId(id) ?: throw ValidationException(Reason.NOT_FOUND, "todo $id")
		val now = clock.millis()
		dao.update(t.markedDone(done, now).copy(ignoredUtcMs = if (ignored) t.ignoredUtcMs ?: now else null))
	}

	/* A to-do put back as it was before a change ("Annulla", issue #97). */
	suspend fun restore(t: TodoEntity) = db.withTransaction {
		if (dao.byId(t.id) != null)
			dao.update(t)
	}

	/* The plan removed (issue #89): the to-do stays, with no day and no reminder. */
	suspend fun clearPlan(id: Long) = db.withTransaction {
		val t = dao.byId(id) ?: throw ValidationException(Reason.NOT_FOUND, "todo $id")
		/* an absence without a plan would be nothing: it is deleted instead */
		if (t.kind == TodoKind.ABSENCE)
			throw ValidationException(Reason.INVALID_DATE)
		dao.update(
			t.copy(planEpochDay = null, planStartMin = null, durationMin = null, planEndEpochDay = null, planRemindedUtcMs = null, updatedUtcMs = clock.millis()),
		)
	}

	/* Ignore (struck through, at the bottom, no reminders or calendar event) or restore. */
	suspend fun setIgnored(id: Long, ignored: Boolean) = db.withTransaction {
		val t = dao.byId(id) ?: throw ValidationException(Reason.NOT_FOUND, "todo $id")
		val now = clock.millis()
		dao.update(t.copy(ignoredUtcMs = if (ignored) t.ignoredUtcMs ?: now else null, updatedUtcMs = now))
	}

	/* The plan moved [minutes] after its start, or after now once started ("postpone"); the duration stays. */
	suspend fun postpone(id: Long, minutes: Int) = db.withTransaction {
		val t = dao.byId(id) ?: throw ValidationException(Reason.NOT_FOUND, "todo $id")
		val p = t.plan()?.postponed(minutes, clock.instant(), ZoneId.systemDefault()) ?: return@withTransaction
		dao.update(t.copy(planEpochDay = p.day.toEpochDay(), planStartMin = p.startMin, planEndEpochDay = p.endDay?.toEpochDay(), updatedUtcMs = clock.millis()))
	}

	/* Open to-dos planned on [day] that nobody worked on that day (issue #52: asked at the end of the working hours). */
	suspend fun unworked(day: java.time.LocalDate, zone: ZoneId): List<TodoEntity> {
		val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
		val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
		/* an absence is not worked on: nothing to re-plan */
		return dao.all().filter { it.kind == TodoKind.TASK && it.isOpen() && it.planEpochDay == day.toEpochDay() && dao.workedBetween(it.id, from, to) == 0 }
	}

	/* The plans of [ids] moved to [day], their time kept; their reminders may come again. */
	suspend fun replan(ids: Collection<Long>, day: java.time.LocalDate) = db.withTransaction {
		for (id in ids) {
			val t = dao.byId(id) ?: continue
			if (t.planEpochDay == null || !t.isOpen())
				continue
			dao.update(t.copy(planEpochDay = day.toEpochDay(), planRemindedUtcMs = null, updatedUtcMs = clock.millis()))
		}
	}

	/* The reminder of the plan starting at [startMs] was shown. */
	suspend fun markPlanReminded(id: Long, startMs: Long) = dao.markPlanReminded(id, startMs)

	suspend fun entriesOf(id: Long): Int = dao.entriesOf(id)

	companion object {
		/* the longest absence: a year (issue #158) */
		const val MAX_ABSENCE_DAYS = 366
	}
}

/* Done or open again at [now]: an already done one keeps its time (issue #89). */
fun TodoEntity.markedDone(done: Boolean, now: Long): TodoEntity =
	copy(done = done, doneUtcMs = if (done) doneUtcMs ?: now else null, updatedUtcMs = now)

/* Neither done nor ignored. */
fun TodoEntity.isOpen(): Boolean = !done && ignoredUtcMs == null

/* The planned slot of a to-do, if any. */
fun TodoEntity.plan(): TodoPlan? = TodoPlan.of(planEpochDay, planStartMin, durationMin, planEndEpochDay)

/* An absence (issue #158): planned like a to-do, never worked time. */
fun TodoEntity.isAbsence(): Boolean = kind == TodoKind.ABSENCE
