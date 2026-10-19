/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import it.infraconsulting.activitytrack.AppContainer
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.calendar.CalendarEvents
import it.infraconsulting.activitytrack.data.repo.plan
import it.infraconsulting.activitytrack.domain.calendar.EventLabels
import it.infraconsulting.activitytrack.domain.calendar.EventTags
import it.infraconsulting.activitytrack.domain.calendar.PlannedEvent
import it.infraconsulting.activitytrack.domain.calendar.TodoEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/* A calendar of the phone the app may write to. */
data class DeviceCalendar(val id: Long, val name: String, val account: String, val color: Int)

/*
 * One-way sync of the report lines to external calendars (M5, owner
 * decisions 2026-10-17) through the Android calendar provider: Google
 * accounts and CalDAV (DAVx5) sync the events on their own. Our events carry
 * CUSTOM_APP_PACKAGE / CUSTOM_APP_URI, so they are found again without a
 * local table; events of others are never touched.
 */
class CalendarSync(private val context: Context, private val container: AppContainer) {
	private val resolver: ContentResolver = context.contentResolver
	private val mutex = Mutex()

	fun hasPermission(): Boolean =
		listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
			context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
		}

	/* Visible calendars with at least contributor access. */
	suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
		if (!hasPermission())
			return@withContext emptyList()
		val out = ArrayList<DeviceCalendar>()
		resolver.query(
			Calendars.CONTENT_URI,
			arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR),
			"${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1",
			arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
			"${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
		)?.use { c ->
			while (c.moveToNext())
				out += DeviceCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3))
		}
		out
	}

	/* A calendar on this phone only (local account), when none of the synced ones is wanted. */
	suspend fun createLocalCalendar(): Long = withContext(Dispatchers.IO) {
		val name = context.getString(R.string.app_name)
		/* chosen again later: reuse ours instead of creating another one */
		resolver.query(
			Calendars.CONTENT_URI, arrayOf(Calendars._ID),
			"${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.ACCOUNT_NAME} = ?",
			arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, name), null,
		)?.use { c -> if (c.moveToFirst()) return@withContext c.getLong(0) }
		val uri = Calendars.CONTENT_URI.buildUpon()
			.appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
			.appendQueryParameter(Calendars.ACCOUNT_NAME, name)
			.appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
			.build()
		val values = ContentValues().apply {
			put(Calendars.ACCOUNT_NAME, name)
			put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
			put(Calendars.NAME, name)
			put(Calendars.CALENDAR_DISPLAY_NAME, name)
			put(Calendars.CALENDAR_COLOR, 0xFF2E7D32.toInt())
			put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
			put(Calendars.OWNER_ACCOUNT, name)
			put(Calendars.VISIBLE, 1)
			put(Calendars.SYNC_EVENTS, 1)
			put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
		}
		ContentUris.parseId(resolver.insert(uri, values) ?: error("calendar not created"))
	}

	/*
	 * Every change of the entries or to-dos re-syncs the last two weeks
	 * (running entries included) and the planned to-dos; a planned block
	 * without duration is synced again each time it grows by an hour.
	 */
	fun start(scope: CoroutineScope) {
		val tick = MutableStateFlow(0)
		var growth: Job? = null
		scope.launch {
			combine(container.reports.changes, container.settings.settings, container.todos.all, tick) { _, _, _, _ -> }.conflate().collect {
				/* lets bursts of changes (stop + complete) settle */
				delay(SETTLE_MS)
				runCatching { sync(DEFAULT_DAYS) }.onFailure { Log.w(TAG, "sync failed", it) }
				growth?.cancel()
				nextGrowth()?.let { at ->
					growth = scope.launch {
						delay(at.toEpochMilli() - System.currentTimeMillis() + 1_000)
						tick.value++
					}
				}
			}
		}
	}

	private suspend fun nextGrowth(): Instant? {
		val zone = ZoneId.systemDefault()
		val now = Instant.now()
		return container.todos.planned().mapNotNull { it.plan()?.nextGrowth(zone, true, now) }.minOrNull()
	}

	/* Brings our events of the last [days] days in line with the reports; returns the changes made. */
	suspend fun sync(days: Long): Int = mutex.withLock {
		if (!hasPermission())
			return@withLock 0
		val s = container.settings.current()
		if (s.calendarId == null && s.companyCalendars.isEmpty())
			return@withLock 0
		val zone = ZoneId.systemDefault()
		val to = LocalDate.now(zone)
		val from = to.minusDays(days)
		val labels = EventLabels(
			context.getString(R.string.cmd_work), context.getString(R.string.cmd_travel),
			context.getString(R.string.cmd_pause), context.getString(R.string.external_refs), "km",
			EventTags.ITALIAN,
		)
		val report = container.reports.report(from, to, zone, s.rounding, s.carryOverMin)
		val entries = container.reports.entriesAround(from, to, zone).associateBy { it.entry.id }
		val planned = CalendarEvents.plan(
			report.days,
			report.meta,
			entries.mapNotNull { (id, e) -> e.entry.uid?.let { id to it } }.toMap(),
			entries.mapValues { it.value.entry.companyId },
			labels,
		) + CalendarEvents.planTodos(plannedTodos(zone, from), labels)
		withContext(Dispatchers.IO) {
			val windowStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
			/* planned to-dos reach into the future */
			val windowEnd = to.plusDays(FUTURE_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli()
			apply(planned, existing(windowStart, windowEnd)) { s.calendarFor(it) }
		}
	}

	/* Open planned to-dos from [from] on; done ones leave the calendar (their entries stay). */
	private suspend fun plannedTodos(zone: ZoneId, from: LocalDate): List<TodoEvent> {
		val now = Instant.now()
		val clients = container.masterData.clients.first().associateBy { it.id }
		val companies = container.masterData.companies.first().associateBy { it.id }
		return container.todos.planned().mapNotNull { t ->
			val p = t.plan() ?: return@mapNotNull null
			if (p.day < from)
				return@mapNotNull null
			TodoEvent(
				uid = t.uid, companyId = t.companyId, start = p.start(zone), end = p.end(zone, true, now), title = t.title,
				client = t.clientId?.let { clients[it]?.name } ?: companies[t.companyId]?.name.orEmpty(),
				notes = t.notes.orEmpty(), refs = t.refs.lines().filter(String::isNotBlank),
			)
		}
	}

	private data class Existing(
		val id: Long, val calendarId: Long, val key: String, val start: Long, val end: Long,
		val title: String?, val description: String?, val location: String?, val accessLevel: Int,
	)

	private fun existing(fromMs: Long, toMs: Long): Map<String, Existing> {
		val out = HashMap<String, Existing>()
		resolver.query(
			Events.CONTENT_URI,
			arrayOf(Events._ID, Events.CALENDAR_ID, Events.CUSTOM_APP_URI, Events.DTSTART, Events.DTEND, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.ACCESS_LEVEL),
			"${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.DELETED} = 0 AND ${Events.DTSTART} >= ? AND ${Events.DTSTART} < ?",
			arrayOf(context.packageName, fromMs.toString(), toMs.toString()),
			null,
		)?.use { c ->
			while (c.moveToNext()) {
				val uri = c.getString(2) ?: continue
				val key = when {
					uri.startsWith(TODO_URI_PREFIX) -> CalendarEvents.TODO_KEY + uri.removePrefix(TODO_URI_PREFIX)
					uri.startsWith(URI_PREFIX) -> uri.removePrefix(URI_PREFIX)
					else -> continue
				}
				out[key] = Existing(c.getLong(0), c.getLong(1), key, c.getLong(3), c.getLong(4), c.getString(5), c.getString(6), c.getString(7), c.getInt(8))
			}
		}
		return out
	}

	private fun apply(planned: List<PlannedEvent>, existing: Map<String, Existing>, calendarOf: (Long?) -> Long?): Int {
		var changes = 0
		val keep = HashSet<String>()
		for (p in planned) {
			val calendarId = calendarOf(p.companyId) ?: continue
			keep += p.key
			val e = existing[p.key]
			val values = values(p)
			when {
				e == null -> {
					resolver.insert(Events.CONTENT_URI, values.apply { put(Events.CALENDAR_ID, calendarId) })
					changes++
				}
				/* the company's calendar changed: events cannot move between calendars */
				e.calendarId != calendarId -> {
					resolver.delete(eventUri(e.id), null, null)
					resolver.insert(Events.CONTENT_URI, values.apply { put(Events.CALENDAR_ID, calendarId) })
					changes++
				}
				e.start != p.start.toEpochMilli() || e.end != p.end.toEpochMilli() ||
					e.title != p.title || e.description.orEmpty() != p.description || e.location != p.location ||
					/* events written before they were private get fixed on the next sync */
					e.accessLevel != Events.ACCESS_PRIVATE -> {
					resolver.update(eventUri(e.id), values, null, null)
					changes++
				}
			}
		}
		/* entries gone from the window (moved day, removed): their events go too */
		for ((key, e) in existing)
			if (key !in keep && planned.none { it.key == key }) {
				resolver.delete(eventUri(e.id), null, null)
				changes++
			}
		return changes
	}

	private fun values(p: PlannedEvent) = ContentValues().apply {
		put(Events.DTSTART, p.start.toEpochMilli())
		put(Events.DTEND, p.end.toEpochMilli())
		put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
		put(Events.TITLE, p.title)
		put(Events.DESCRIPTION, p.description)
		put(Events.EVENT_LOCATION, p.location)
		put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
		/* clients, activities and notes are confidential: others sharing the calendar see only "busy" */
		put(Events.ACCESS_LEVEL, Events.ACCESS_PRIVATE)
		put(Events.STATUS, if (p.tentative) Events.STATUS_TENTATIVE else Events.STATUS_CONFIRMED)
		put(Events.HAS_ALARM, 0)
		put(Events.CUSTOM_APP_PACKAGE, context.packageName)
		put(
			Events.CUSTOM_APP_URI,
			if (p.key.startsWith(CalendarEvents.TODO_KEY)) TODO_URI_PREFIX + p.key.removePrefix(CalendarEvents.TODO_KEY) else URI_PREFIX + p.key,
		)
	}

	private fun eventUri(id: Long): Uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)

	companion object {
		private const val TAG = "CalendarSync"
		private const val URI_PREFIX = "activitytrack://entry/"
		private const val TODO_URI_PREFIX = "activitytrack://todo/"
		private const val SETTLE_MS = 3_000L
		const val DEFAULT_DAYS = 14L
		const val FULL_DAYS = 90L
		/* planned to-dos synced up to a year ahead */
		const val FUTURE_DAYS = 365L
	}
}
