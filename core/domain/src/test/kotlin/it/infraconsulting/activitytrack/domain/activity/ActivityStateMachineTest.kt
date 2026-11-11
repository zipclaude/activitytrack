/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

import it.infraconsulting.activitytrack.domain.activity.ActivityCommand.Delete
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand.Reopen
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand.Restore
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand.Resume
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand.Start
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand.Stop
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand.Suspend
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand.Switch
import it.infraconsulting.activitytrack.domain.activity.ActivityCommand.Tick
import it.infraconsulting.activitytrack.domain.activity.ActivityKind.ABSENCE
import it.infraconsulting.activitytrack.domain.activity.ActivityKind.ACTIVITY
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus.CLOSED
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus.RUNNING
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus.TODO
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import it.infraconsulting.activitytrack.domain.model.SegmentKind.PAUSE
import it.infraconsulting.activitytrack.domain.model.SegmentKind.TRAVEL
import it.infraconsulting.activitytrack.domain.model.SegmentKind.WORK
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActivityStateMachineTest {
	private val at = Instant.parse("2026-11-11T08:00:00Z")
	private val now = Instant.parse("2026-11-11T08:00:05Z")
	private val todo = ActivityState(ACTIVITY, TODO)
	private fun running(mode: SegmentKind?) = ActivityState(ACTIVITY, RUNNING, mode)
	private val closed = ActivityState(ACTIVITY, CLOSED, closedAt = Instant.parse("2026-11-10T17:00:00Z"))

	private fun go(s: ActivityState, c: ActivityCommand) = ActivityStateMachine.apply(s, c, now)
	private fun refused(s: ActivityState, c: ActivityCommand) {
		assertFailsWith<IllegalActivityCommandException>("$c from $s") { go(s, c) }
	}

	@Test
	fun startDeletesThePlanAndOpens() {
		val t = go(todo, Start(TRAVEL, at))
		assertEquals(ActivityTransition(running(TRAVEL), deletePlanned = true, open = OpenSegment(TRAVEL, at)), t)
		refused(todo, Start(PAUSE, at))
		refused(running(WORK), Start(WORK, at))
		refused(closed, Start(WORK, at))
	}

	@Test
	fun switchClosesAndOpensAtOneInstant() {
		assertEquals(ActivityTransition(running(PAUSE), closeAt = at, open = OpenSegment(PAUSE, at)), go(running(WORK), Switch(PAUSE, at)))
		assertEquals(running(TRAVEL), go(running(PAUSE), Switch(TRAVEL, at)).next)
		assertEquals(running(WORK), go(running(TRAVEL), Switch(WORK, at)).next)
		assertEquals(running(PAUSE), go(running(TRAVEL), Switch(PAUSE, at)).next)
		refused(running(WORK), Switch(WORK, at))
		refused(running(PAUSE), Switch(PAUSE, at))
		refused(running(WORK), Switch(SegmentKind.ABSENCE, at))
		refused(running(null), Switch(WORK, at))
		refused(todo, Switch(WORK, at))
	}

	@Test
	fun resumeOnlyWithNothingOpen() {
		assertEquals(ActivityTransition(running(WORK), open = OpenSegment(WORK, at)), go(running(null), Resume(WORK, at)))
		refused(running(null), Resume(PAUSE, at))
		refused(running(WORK), Resume(TRAVEL, at))
		refused(closed, Resume(WORK, at))
	}

	@Test
	fun suspendClosesTheOpenSegment() {
		assertEquals(ActivityTransition(running(null), closeAt = at), go(running(PAUSE), Suspend(at)))
		refused(running(null), Suspend(at))
		refused(todo, Suspend(at))
	}

	@Test
	fun stopClosesAtTheInstantAndRecordsNow() {
		val t = go(running(WORK), Stop(at))
		assertEquals(ActivityTransition(ActivityState(ACTIVITY, CLOSED, closedAt = now), closeAt = at), t)
		assertEquals(null, go(running(null), Stop(at)).closeAt)
		refused(todo, Stop(at))
		refused(closed, Stop(at))
	}

	@Test
	fun tickClosesATodo() {
		assertEquals(ActivityTransition(ActivityState(ACTIVITY, CLOSED, closedAt = now), deletePlanned = true), go(todo, Tick))
		refused(running(WORK), Tick)
	}

	@Test
	fun reopenOpensAndForgetsTheClosing() {
		assertEquals(ActivityTransition(running(WORK), open = OpenSegment(WORK, at)), go(closed, Reopen(WORK, at)))
		refused(closed, Reopen(PAUSE, at))
		refused(running(null), Reopen(WORK, at))
		refused(todo, Reopen(WORK, at))
	}

	@Test
	fun deleteAndRestoreKeepEverythingElse() {
		val del = go(running(WORK), Delete)
		assertEquals(ActivityTransition(running(WORK).copy(deletedAt = now)), del)
		refused(del.next, Delete)
		refused(del.next, Stop(at))
		refused(del.next, Switch(PAUSE, at))
		assertEquals(running(WORK), go(del.next, Restore).next)
		refused(todo, Restore)
	}

	@Test
	fun absenceIsOnlyDeletedOrRestored() {
		val a = ActivityState(ABSENCE, CLOSED, closedAt = at)
		assertEquals(listOf<ActivityCommand>(Delete), ActivityStateMachine.allowed(a))
		refused(a, Reopen(WORK, at))
		assertFailsWith<IllegalArgumentException> { ActivityState(ABSENCE, TODO) }
		assertFailsWith<IllegalArgumentException> { ActivityState(ACTIVITY, CLOSED, WORK) }
	}

	@Test
	fun allowedCommands() {
		fun names(s: ActivityState) = ActivityStateMachine.allowed(s).map { c ->
			c::class.simpleName + when (c) {
				is Start -> "(${c.kind})"
				is Switch -> "(${c.kind})"
				is Resume -> "(${c.kind})"
				is Reopen -> "(${c.kind})"
				else -> ""
			}
		}
		assertEquals(listOf("Start(WORK)", "Start(TRAVEL)", "Tick", "Delete"), names(todo))
		assertEquals(listOf("Switch(TRAVEL)", "Switch(PAUSE)", "Suspend", "Stop", "Delete"), names(running(WORK)))
		assertEquals(listOf("Switch(WORK)", "Switch(TRAVEL)", "Suspend", "Stop", "Delete"), names(running(PAUSE)))
		assertEquals(listOf("Resume(WORK)", "Resume(TRAVEL)", "Stop", "Delete"), names(running(null)))
		assertEquals(listOf("Reopen(WORK)", "Reopen(TRAVEL)", "Delete"), names(closed))
	}

	@Test
	fun resumeAfterPauseTakesTheKindBefore() {
		val p = Instant.parse("2026-11-11T10:00:00Z")
		val segs = listOf(
			ActivitySegment(WORK, Instant.parse("2026-11-11T08:00:00Z"), Instant.parse("2026-11-11T09:00:00Z")),
			ActivitySegment(TRAVEL, Instant.parse("2026-11-11T09:00:00Z"), p),
			ActivitySegment(PAUSE, p, null),
		)
		assertEquals(TRAVEL, ActivityStateMachine.resumeKind(segs, p))
		assertEquals(WORK, ActivityStateMachine.resumeKind(listOf(ActivitySegment(PAUSE, p, null)), p))
	}

	@Test
	fun travelFromWork() {
		val s = running(WORK)
		assertTrue(TravelFromWork.needsQuestion(s))
		assertFalse(TravelFromWork.needsQuestion(running(PAUSE)))
		val yes = assertIs<TravelFromWork.StopAndTravel>(TravelFromWork.decide(s, workDone = true, at, now))
		assertEquals(ActivityState(ACTIVITY, CLOSED, closedAt = now), yes.stop.next)
		assertEquals(at, yes.stop.closeAt)
		assertEquals(ActivityTransition(running(TRAVEL), open = OpenSegment(TRAVEL, at)), yes.travel)
		val no = assertIs<TravelFromWork.Switch>(TravelFromWork.decide(s, workDone = false, at, now))
		assertEquals(ActivityTransition(running(TRAVEL), closeAt = at, open = OpenSegment(TRAVEL, at)), no.switch)
		assertFailsWith<IllegalArgumentException> { TravelFromWork.decide(running(TRAVEL), true, at, now) }
	}
}
