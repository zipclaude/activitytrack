/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

import it.infraconsulting.activitytrack.domain.backup.BackupSchedule
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import it.infraconsulting.activitytrack.domain.rounding.RoundingRule
import java.time.Duration

/* An optional online service with a configurable endpoint (decision A10). */
data class OnlineService(
	val enabled: Boolean,
	val endpoint: String,
)

/*
 * User settings (DESIGN §8.3). Lunch breaks and working hours are master
 * data (tables), not settings. [validated] clamps every value to a safe
 * range; reminder intervals stay >= 10 min because allow-while-idle alarms
 * are rate-limited in Doze.
 */
data class AppSettings(
	val confirmIntervalMin: Int = 30,
	val maxConfirmations: Int = 0,			/* 0 = unlimited */
	val pauseIntervalMin: Int = 15,
	val dayStartDelayMin: Int = 15,
	/* planned to-dos (M9, advanced): reminder lead time and "postpone" step */
	val planLeadMin: Int = 15,
	val planSnoozeMin: Int = 30,
	val movementThresholdM: Int = 100,
	val stationaryRadiusM: Int = 100,
	val stationaryMin: Int = 15,
	val carryOverMin: Int = 6 * 60,			/* day attribution carry-over, minutes after midnight */
	val kmFactor: Double = 1.25,			/* straight-line -> road distance fallback */
	val backupRetention: Int = 14,
	/* automatic backup every N hours (6, 12, 24, 168) at a preferred hour (issue #44) */
	val backupEveryHours: Int = 24,
	val backupHour: Int = 2,
	/* the Home company filter also hides the entries of other companies (issue #44) */
	val homeFilterEntries: Boolean = false,
	val rootUri: String? = null,			/* SAF tree of the user data directory */
	val nominatim: OnlineService = OnlineService(true, "https://nominatim.openstreetmap.org"),
	val osrm: OnlineService = OnlineService(true, "https://router.project-osrm.org"),
	val tiles: OnlineService = OnlineService(true, "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
	val rounding: ReportRounding = ReportRounding.DEFAULT,
	/* speech recognition language, independent of the phone language (owner decision 2026-10-17) */
	val dictationLanguage: String = "it-IT",
	/*
	 * External calendar sync (M5): device calendar ids, valid on this phone
	 * only (never exported). A company's calendar replaces the generic one.
	 */
	val calendarId: Long? = null,
	val companyCalendars: Map<Long, Long> = emptyMap(),
	/* swipe gestures on entry and to-do cards (M10) */
	val gestures: GestureSettings = GestureSettings(),
	/* compact display (M11): smaller text, tighter cards */
	val compactDisplay: Boolean = false,
	/* Home and To-dos show the to-dos of this company only (M12); kept until removed */
	val todoCompanyFilter: Long? = null,
	/*
	 * To-dos screen window (owner, 2026-10-19: 24 h / 7 d / 30 d / all):
	 * hours ahead of now, overdue always included; 0 = all.
	 */
	val todoWindowHours: Int = 0,
	/* the Home's own window, chosen on its header (issue #37); 24 h by default */
	val homeTodoWindowHours: Int = 24,
	/* the agenda reopens on the last view picked (issue #44): LIST, DAY, WEEK, MONTH */
	val agendaView: String = "LIST",
	/*
	 * Home bottom tabs (issue #59): PLANNED (to-dos ahead) or CLOSED
	 * (entries and done to-dos behind); the last choice is remembered.
	 */
	val homeTab: String = "PLANNED",
	/*
	 * The "Chiuse" tab window (issue #59): today (0, from the day-attribution
	 * carry-over boundary), or the last 24 h / 7 d / 30 d.
	 */
	val homeClosedWindow: Int = 0,
	/* ignored to-dos listed (struck through) or hidden */
	val todoShowIgnored: Boolean = true,
	/* order of the master-data lists and drop-downs (M16) */
	val listOrders: ListOrders = ListOrders(),
	/*
	 * Times freely editable (M17, owner 2026-10-19: the app is for
	 * freelancers, not employees): start and end times chosen when starting
	 * and stopping, segments editable and deletable. Off = times as
	 * recorded; adding forgotten travels and pauses stays allowed. To be
	 * settable by policy later.
	 */
	val editableTimes: Boolean = true,
	/* call recognition (issue #28): incoming and outgoing each, off until enabled; pause and proposal options */
	val callsIncoming: Boolean = false,
	val callsOutgoing: Boolean = false,
	val callAutoPause: Boolean = true,
	val callProposeEntry: Boolean = true,
	/* also outside working hours, holidays and days off (on call); default working hours only */
	val callsAlways: Boolean = false,
) {
	/* Calendar for the entries of [companyId]; null = not synced. */
	fun calendarFor(companyId: Long?): Long? = companyId?.let { companyCalendars[it] } ?: calendarId

	fun validated(): AppSettings = copy(
		confirmIntervalMin = confirmIntervalMin.coerceIn(10, 240),
		maxConfirmations = maxConfirmations.coerceIn(0, 100),
		pauseIntervalMin = pauseIntervalMin.coerceIn(10, 120),
		dayStartDelayMin = dayStartDelayMin.coerceIn(0, 240),
		planLeadMin = planLeadMin.coerceIn(0, 240),
		planSnoozeMin = planSnoozeMin.coerceIn(10, 240),
		todoWindowHours = todoWindowHours.takeIf { it in TODO_WINDOWS } ?: 0,
		homeTodoWindowHours = homeTodoWindowHours.takeIf { it in TODO_WINDOWS } ?: 24,
		homeTab = homeTab.takeIf { it in HOME_TABS } ?: "PLANNED",
		homeClosedWindow = homeClosedWindow.takeIf { it in CLOSED_WINDOWS } ?: 0,
		movementThresholdM = movementThresholdM.coerceIn(50, 5_000),
		stationaryRadiusM = stationaryRadiusM.coerceIn(25, 1_000),
		stationaryMin = stationaryMin.coerceIn(5, 120),
		carryOverMin = carryOverMin.coerceIn(0, 12 * 60),
		kmFactor = if (kmFactor.isNaN()) 1.25 else kmFactor.coerceIn(1.0, 3.0),
		backupRetention = backupRetention.coerceIn(1, 365),
		backupEveryHours = backupEveryHours.takeIf { it in BackupSchedule.FREQUENCIES } ?: 24,
		backupHour = backupHour.coerceIn(0, 23),
		nominatim = nominatim.copy(endpoint = nominatim.endpoint.trim().trimEnd('/')),
		osrm = osrm.copy(endpoint = osrm.endpoint.trim().trimEnd('/')),
		tiles = tiles.copy(endpoint = tiles.endpoint.trim()),
		rounding = ReportRounding(rule(rounding.work), rule(rounding.travel), rule(rounding.pause)),
		dictationLanguage = dictationLanguage.takeIf { it in DICTATION_LANGUAGES } ?: "it-IT",
	)

	companion object {
		/* Quanta accepted by export profiles (SPEC §4.3). */
		val QUANTA_MIN = listOf(1L, 5L, 15L, 30L, 60L)

		/* BCP 47 tags the recognizers know; a phone locale like en-IT is not one of them */
		val DICTATION_LANGUAGES = listOf("it-IT", "en-US", "de-DE", "es-ES")

		private fun rule(r: RoundingRule): RoundingRule {
			val q = r.quantum.toMinutes().takeIf { it in QUANTA_MIN } ?: 15L
			return r.copy(quantum = Duration.ofMinutes(q), minimum = minOf(r.minimum, Duration.ofHours(1)))
		}
	}
}

/* the to-do windows offered (hours; 0 = all) */
val TODO_WINDOWS = setOf(0, 24, 72, 168, 720)

/* the Home tabs (issue #59), as persisted in [AppSettings.homeTab] */
val HOME_TABS = setOf("PLANNED", "CLOSED")

/* the "Chiuse" windows offered (issue #59; hours; 0 = today) */
val CLOSED_WINDOWS = setOf(0, 24, 168, 720)
