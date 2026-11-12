/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.home

import it.infraconsulting.activitytrack.data.db.SegmentEntity
import it.infraconsulting.activitytrack.data.db.TimeEntryEntity
import it.infraconsulting.activitytrack.domain.activity.AbsenceKind
import it.infraconsulting.activitytrack.domain.activity.ActivityKind
import it.infraconsulting.activitytrack.domain.activity.ActivityStatus
import it.infraconsulting.activitytrack.domain.activity.DisplayState
import it.infraconsulting.activitytrack.domain.model.SegmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/* The one Home list (owner, 2026-10-27; 1.0): groups, state filter, window; and the running buttons. */
class HomeListTest {
	private val zone = ZoneId.systemDefault()
	private val day = LocalDate.of(2026, 10, 27)
	private val now = day.atTime(12, 0).atZone(zone).toInstant()

	private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

	private fun row(id: Long, status: ActivityStatus, kind: ActivityKind = ActivityKind.ACTIVITY, mode: SegmentKind? = null, clientId: Long? = null) =
		TimeEntryEntity(
			id = id, uid = null, companyId = null, clientId = clientId, status = status, mode = mode, zoneId = zone.id,
			createdUtcMs = 0, updatedUtcMs = 0, kind = kind,
			absenceKind = AbsenceKind.ABSENCE.takeIf { kind == ActivityKind.ABSENCE },
		)

	private fun seg(entry: Long, kind: SegmentKind, from: Long, to: Long?) =
		SegmentEntity(entryId = entry, kind = kind, startUtcMs = from, endUtcMs = to, zoneId = zone.id)

	private fun todo(id: Long, planDay: LocalDate? = null, start: Int? = null, priority: Int = 0) =
		EntryCard(row(id, ActivityStatus.TODO).copy(planEpochDay = planDay?.toEpochDay(), planStartMin = start, priority = priority), null, null, emptyList())

	/* closed with a work segment ending at [end]; complete with a client, incomplete without */
	private fun closed(id: Long, end: Long, complete: Boolean) = EntryCard(
		row(id, ActivityStatus.CLOSED, clientId = 1L.takeIf { complete }), null, null,
		listOf(seg(id, SegmentKind.WORK, end - 3_600_000, end)), endUtcMs = end,
	)

	private fun absence(id: Long, from: LocalDate, to: LocalDate) = EntryCard(
		row(id, ActivityStatus.CLOSED, ActivityKind.ABSENCE), null, null,
		listOf(seg(id, SegmentKind.ABSENCE, at(from, 9), at(to, 18))),
	)

	private fun keys(sections: List<Pair<HomeSection, List<HomeItem>>>) = sections.associate { (s, l) -> s to l.map { it.key } }

	private fun sections(
		todos: List<EntryCard> = emptyList(), closed: List<EntryCard> = emptyList(), absences: List<EntryCard> = emptyList(),
		show: Set<HomeShow> = HomeShow.entries.toSet(), tag: String? = null, window: Int = 168,
	) = homeSections(todos, closed, absences, show, null, tag, window, 6 * 60, now, zone)

	@Test
	fun groupsInOrder() {
		val all = sections(
			todos = listOf(
				todo(1, day.minusDays(3), 9 * 60),	/* late */
				todo(2, day, 15 * 60),			/* today, later */
				todo(3, day.plusDays(1), 9 * 60),	/* tomorrow */
				todo(4),				/* no date */
			),
			closed = listOf(
				closed(10, at(day.minusDays(5), 10), complete = false),
				closed(11, at(day, 9), complete = true),
				closed(12, at(day.minusDays(1), 17), complete = true),
			),
			absences = listOf(absence(20, day, day)),
		)
		assertEquals(
			listOf(HomeSection.LATE, HomeSection.INCOMPLETE, HomeSection.TODAY, HomeSection.NEXT, HomeSection.EARLIER, HomeSection.NO_DATE),
			all.map { it.first },
		)
		/* today: the absence of the day, then the to-dos by time, then what was done, newest first */
		assertEquals(listOf("entry-20", "entry-2", "entry-11"), keys(all)[HomeSection.TODAY])
		assertEquals(listOf("entry-12"), keys(all)[HomeSection.EARLIER])
	}

	@Test
	fun rowsShowTheirDisplayedState() {
		val all = sections(
			todos = listOf(todo(1, day.minusDays(1), 9 * 60), todo(2, day, 15 * 60), todo(3)),
			closed = listOf(closed(10, at(day, 9), complete = false), closed(11, at(day, 8), complete = true)),
			absences = listOf(absence(20, day, day)),
		)
		val states = all.flatMap { it.second }.associate { it.card.entry.id to it.state }
		assertEquals(DisplayState.LATE, states[1])
		assertEquals(DisplayState.PLANNED, states[2])
		assertEquals(DisplayState.TODO, states[3])
		assertEquals(DisplayState.INCOMPLETE, states[10])
		assertEquals(DisplayState.DONE, states[11])
		assertEquals(DisplayState.ABSENCE, states[20])
	}

	@Test
	fun stateFilterLeavesTheOthers() {
		val c = listOf(closed(10, at(day, 9), complete = false), closed(11, at(day, 8), complete = true))
		val t = listOf(todo(2, day, 15 * 60))
		assertEquals(
			mapOf(HomeSection.INCOMPLETE to listOf("entry-10"), HomeSection.TODAY to listOf("entry-2")),
			keys(sections(t, c, show = setOf(HomeShow.TODO, HomeShow.MISSING), window = -1)),
		)
		assertEquals(mapOf(HomeSection.TODAY to listOf("entry-11")), keys(sections(t, c, show = setOf(HomeShow.DONE), window = -1)))
	}

	@Test
	fun windowCutsWhatIsAheadAndBehind() {
		val tomorrow = todo(3, day.plusDays(1), 9 * 60)
		assertEquals(emptyList(), sections(todos = listOf(tomorrow), window = -1))
		assertEquals(1, sections(todos = listOf(tomorrow), window = 24).size)
		/* a late one is always shown */
		assertEquals(1, sections(todos = listOf(todo(1, day.minusDays(30), 9 * 60)), window = -1).size)
		/* done yesterday: outside "today", inside the week */
		val done = closed(11, at(day.minusDays(1), 17), complete = true)
		assertEquals(emptyList(), sections(closed = listOf(done), window = -1))
		assertEquals(1, sections(closed = listOf(done), window = 168).size)
	}

	@Test
	fun aTagKeepsOnlyTheTaggedAndNoAbsences() {
		val tagged = closed(11, at(day, 9), complete = true).copy(tags = setOf("remoto"))
		val other = closed(12, at(day, 10), complete = true)
		val r = sections(todos = listOf(todo(1, day, 15 * 60)), closed = listOf(tagged, other), absences = listOf(absence(20, day, day)), tag = "remoto", window = -1)
		assertEquals(mapOf(HomeSection.TODAY to listOf("entry-11")), keys(r))
	}

	@Test
	fun noDateByPriority() {
		val r = sections(todos = listOf(todo(1, priority = 1), todo(2, priority = 5), todo(3, priority = 3)))
		assertEquals(listOf("entry-2", "entry-3", "entry-1"), keys(r)[HomeSection.NO_DATE])
	}

	/* the running buttons come from the state machine */
	private fun running(mode: SegmentKind?) = row(1, ActivityStatus.RUNNING, mode = mode)

	private val t0 = at(day, 9)

	@Test
	fun workOffersPauseTravelStop() {
		val e = running(SegmentKind.WORK)
		assertEquals(
			listOf(RunAction.Pause, RunAction.Go(SegmentKind.TRAVEL), RunAction.Stop),
			runActions(e, listOf(seg(1, SegmentKind.WORK, t0, null))),
		)
	}

	@Test
	fun travelOffersPauseWorkStop() {
		val e = running(SegmentKind.TRAVEL)
		assertEquals(
			listOf(RunAction.Pause, RunAction.Go(SegmentKind.WORK), RunAction.Stop),
			runActions(e, listOf(seg(1, SegmentKind.TRAVEL, t0, null))),
		)
	}

	@Test
	fun pauseOffersResumeInTheKindBeforeThenTheOtherThenStop() {
		val e = running(SegmentKind.PAUSE)
		val afterWork = listOf(seg(1, SegmentKind.WORK, t0, t0 + 3_600_000), seg(1, SegmentKind.PAUSE, t0 + 3_600_000, null))
		assertEquals(listOf(RunAction.Go(SegmentKind.WORK, resume = true), RunAction.Go(SegmentKind.TRAVEL), RunAction.Stop), runActions(e, afterWork))
		val afterTravel = listOf(seg(1, SegmentKind.TRAVEL, t0, t0 + 3_600_000), seg(1, SegmentKind.PAUSE, t0 + 3_600_000, null))
		assertEquals(listOf(RunAction.Go(SegmentKind.TRAVEL, resume = true), RunAction.Go(SegmentKind.WORK), RunAction.Stop), runActions(e, afterTravel))
	}

	@Test
	fun suspendedOffersResumeTravelStop() {
		val e = running(null)
		val s = listOf(seg(1, SegmentKind.WORK, t0, t0 + 3_600_000))
		assertEquals(listOf(RunAction.Go(SegmentKind.WORK, resume = true), RunAction.Go(SegmentKind.TRAVEL), RunAction.Stop), runActions(e, s))
	}

	@Test
	fun nothingForClosedOrDeleted() {
		assertEquals(emptyList(), runActions(row(1, ActivityStatus.CLOSED), emptyList()))
		assertEquals(emptyList(), runActions(running(SegmentKind.WORK).copy(deletedUtcMs = 1), emptyList()))
	}

	@Test
	fun anEndTypedAsATimeMayBeAnHourAhead() {
		fun h(h: Int, m: Int = 0) = h * 60 + m
		/* 12:30 is half an hour ahead: today */
		assertEquals(day.atTime(12, 30).atZone(zone).toInstant(), closeAt(h(12, 30), now, zone))
		/* 13:00 is exactly the limit: today */
		assertEquals(day.atTime(13, 0).atZone(zone).toInstant(), closeAt(h(13), now, zone))
		/* 13:01 is too far: yesterday's */
		assertEquals(day.minusDays(1).atTime(13, 1).atZone(zone).toInstant(), closeAt(h(13, 1), now, zone))
		assertEquals(day.atTime(9, 0).atZone(zone).toInstant(), closeAt(h(9), now, zone))
	}

	@Test
	fun closedBoundaryFollowsTheCarryOver() {
		val night = day.atTime(3, 0).atZone(zone).toInstant()
		assertEquals(day.minusDays(1).atTime(6, 0).atZone(zone).toInstant(), closedBoundary(0, 6 * 60, night, zone))
		assertEquals(Instant.ofEpochSecond(night.epochSecond - 24 * 3600), closedBoundary(24, 6 * 60, night, zone))
	}

	@Test
	fun plannedSplitKeepsTheWidgetContract() {
		val dated = row(1, ActivityStatus.TODO).copy(planEpochDay = day.toEpochDay(), planStartMin = 15 * 60)
		val undated = row(2, ActivityStatus.TODO).copy(priority = 3)
		val s = plannedSplit(listOf(undated, dated), null, 24, now, zone)
		assertEquals(listOf(1L), s.dated.map { it.id })
		assertEquals(listOf(2L), s.noDate.map { it.id })
	}
}
