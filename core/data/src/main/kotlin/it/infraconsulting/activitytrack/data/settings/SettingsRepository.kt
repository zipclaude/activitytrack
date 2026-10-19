/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.settings

import it.infraconsulting.activitytrack.domain.settings.OrderedList
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.infraconsulting.activitytrack.domain.rounding.ReportRounding
import it.infraconsulting.activitytrack.domain.rounding.RoundingMode
import it.infraconsulting.activitytrack.domain.rounding.RoundingRule
import it.infraconsulting.activitytrack.domain.settings.AppSettings
import it.infraconsulting.activitytrack.domain.settings.GestureSettings
import it.infraconsulting.activitytrack.domain.settings.OnlineService
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/* One DataStore per process: the delegate must stay top-level. */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/*
 * Settings persisted in Preferences DataStore. Missing keys fall back to the
 * AppSettings defaults; every write is validated. Key names are persisted:
 * never rename them.
 */
class SettingsRepository(context: Context) {
	private val store = context.applicationContext.settingsStore

	val settings: Flow<AppSettings> = store.data
		.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
		.map(::read)

	suspend fun current(): AppSettings = settings.first()

	suspend fun update(transform: (AppSettings) -> AppSettings) {
		store.edit { p -> write(p, transform(read(p)).validated()) }
	}

	private object K {
		val confirmInterval = intPreferencesKey("confirm_interval_min")
		val maxConfirmations = intPreferencesKey("max_confirmations")
		val pauseInterval = intPreferencesKey("pause_interval_min")
		val dayStartDelay = intPreferencesKey("day_start_delay_min")
		val planLead = intPreferencesKey("plan_lead_min")
		val planSnooze = intPreferencesKey("plan_snooze_min")
		val movementThreshold = intPreferencesKey("movement_threshold_m")
		val stationaryRadius = intPreferencesKey("stationary_radius_m")
		val stationaryMin = intPreferencesKey("stationary_min")
		val carryOver = intPreferencesKey("carry_over_min")
		val kmFactor = doublePreferencesKey("km_factor")
		val backupRetention = intPreferencesKey("backup_retention")
		val rootUri = stringPreferencesKey("root_uri")
		val dictationLanguage = stringPreferencesKey("dictation_language")
		val calendarId = longPreferencesKey("calendar_id")
		/* "companyId:calendarId,..." */
		val companyCalendars = stringPreferencesKey("company_calendars")
		val todoToStart = stringPreferencesKey("swipe_todo_to_start")
		val todoToEnd = stringPreferencesKey("swipe_todo_to_end")
		val entryToStart = stringPreferencesKey("swipe_entry_to_start")
		val entryToEnd = stringPreferencesKey("swipe_entry_to_end")
		val fullSwipe = booleanPreferencesKey("swipe_full")
		val compact = booleanPreferencesKey("display_compact")
		val todoCompany = longPreferencesKey("todo_company_filter")
		/* persisted name kept from the former "today only" filter */
		/* until 0.5.0: a 24-hour switch; read once as 24 h or all */
		val todoNext24h = booleanPreferencesKey("todo_today_only")
		val todoWindow = intPreferencesKey("todo_window_hours")
		val todoShowIgnored = booleanPreferencesKey("todo_show_ignored")
		val callsIncoming = booleanPreferencesKey("calls_incoming")
		val callsOutgoing = booleanPreferencesKey("calls_outgoing")
		val callAutoPause = booleanPreferencesKey("call_auto_pause")
		val callProposeEntry = booleanPreferencesKey("call_propose_entry")
		fun order(list: OrderedList) = stringPreferencesKey("order_${list.name.lowercase()}")
		val editableTimes = booleanPreferencesKey("editable_times")

		fun enabled(name: String) = booleanPreferencesKey("${name}_enabled")
		fun endpoint(name: String) = stringPreferencesKey("${name}_endpoint")
		fun mode(name: String) = stringPreferencesKey("round_${name}_mode")
		fun quantum(name: String) = longPreferencesKey("round_${name}_quantum_min")
		fun minimum(name: String) = longPreferencesKey("round_${name}_minimum_min")
	}

	private fun read(p: Preferences): AppSettings {
		val d = AppSettings()
		return AppSettings(
			confirmIntervalMin = p[K.confirmInterval] ?: d.confirmIntervalMin,
			maxConfirmations = p[K.maxConfirmations] ?: d.maxConfirmations,
			pauseIntervalMin = p[K.pauseInterval] ?: d.pauseIntervalMin,
			dayStartDelayMin = p[K.dayStartDelay] ?: d.dayStartDelayMin,
			planLeadMin = p[K.planLead] ?: d.planLeadMin,
			planSnoozeMin = p[K.planSnooze] ?: d.planSnoozeMin,
			movementThresholdM = p[K.movementThreshold] ?: d.movementThresholdM,
			stationaryRadiusM = p[K.stationaryRadius] ?: d.stationaryRadiusM,
			stationaryMin = p[K.stationaryMin] ?: d.stationaryMin,
			carryOverMin = p[K.carryOver] ?: d.carryOverMin,
			kmFactor = p[K.kmFactor] ?: d.kmFactor,
			backupRetention = p[K.backupRetention] ?: d.backupRetention,
			dictationLanguage = p[K.dictationLanguage] ?: d.dictationLanguage,
			calendarId = p[K.calendarId],
			companyCalendars = p[K.companyCalendars].orEmpty().split(',').mapNotNull { pair ->
				pair.split(':').takeIf { it.size == 2 }?.let { (c, cal) -> c.toLongOrNull()?.let { k -> cal.toLongOrNull()?.let { k to it } } }
			}.toMap(),
			rootUri = p[K.rootUri],
			gestures = GestureSettings(
				todoToStart = enumOr(p[K.todoToStart], d.gestures.todoToStart),
				todoToEnd = enumOr(p[K.todoToEnd], d.gestures.todoToEnd),
				entryToStart = enumOr(p[K.entryToStart], d.gestures.entryToStart),
				entryToEnd = enumOr(p[K.entryToEnd], d.gestures.entryToEnd),
				fullSwipe = p[K.fullSwipe] ?: d.gestures.fullSwipe,
			),
			compactDisplay = p[K.compact] ?: d.compactDisplay,
			todoCompanyFilter = p[K.todoCompany],
			todoWindowHours = p[K.todoWindow] ?: if (p[K.todoNext24h] == true) 24 else d.todoWindowHours,
			todoShowIgnored = p[K.todoShowIgnored] ?: d.todoShowIgnored,
			listOrders = OrderedList.entries.fold(d.listOrders) { o, l -> o.with(l, enumOr(p[K.order(l)], d.listOrders.of(l))) },
			editableTimes = p[K.editableTimes] ?: d.editableTimes,
			callsIncoming = p[K.callsIncoming] ?: d.callsIncoming,
			callsOutgoing = p[K.callsOutgoing] ?: d.callsOutgoing,
			callAutoPause = p[K.callAutoPause] ?: d.callAutoPause,
			callProposeEntry = p[K.callProposeEntry] ?: d.callProposeEntry,
			nominatim = readService(p, "nominatim", d.nominatim),
			osrm = readService(p, "osrm", d.osrm),
			tiles = readService(p, "tiles", d.tiles),
			rounding = ReportRounding(
				readRule(p, "work", d.rounding.work),
				readRule(p, "travel", d.rounding.travel),
				readRule(p, "pause", d.rounding.pause),
			),
		).validated()
	}

	private fun write(p: MutablePreferences, s: AppSettings) {
		p[K.confirmInterval] = s.confirmIntervalMin
		p[K.maxConfirmations] = s.maxConfirmations
		p[K.pauseInterval] = s.pauseIntervalMin
		p[K.dayStartDelay] = s.dayStartDelayMin
		p[K.planLead] = s.planLeadMin
		p[K.planSnooze] = s.planSnoozeMin
		p[K.movementThreshold] = s.movementThresholdM
		p[K.stationaryRadius] = s.stationaryRadiusM
		p[K.stationaryMin] = s.stationaryMin
		p[K.carryOver] = s.carryOverMin
		p[K.kmFactor] = s.kmFactor
		p[K.backupRetention] = s.backupRetention
		p[K.dictationLanguage] = s.dictationLanguage
		s.calendarId?.let { p[K.calendarId] = it } ?: p.remove(K.calendarId)
		p[K.companyCalendars] = s.companyCalendars.entries.joinToString(",") { "${it.key}:${it.value}" }
		s.rootUri.let { if (it != null) p[K.rootUri] = it else p.remove(K.rootUri) }
		p[K.todoToStart] = s.gestures.todoToStart.name
		p[K.todoToEnd] = s.gestures.todoToEnd.name
		p[K.entryToStart] = s.gestures.entryToStart.name
		p[K.entryToEnd] = s.gestures.entryToEnd.name
		p[K.fullSwipe] = s.gestures.fullSwipe
		p[K.compact] = s.compactDisplay
		s.todoCompanyFilter?.let { p[K.todoCompany] = it } ?: p.remove(K.todoCompany)
		p[K.todoWindow] = s.todoWindowHours
		p.remove(K.todoNext24h)
		p[K.todoShowIgnored] = s.todoShowIgnored
		OrderedList.entries.forEach { p[K.order(it)] = s.listOrders.of(it).name }
		p[K.editableTimes] = s.editableTimes
		p[K.callsIncoming] = s.callsIncoming
		p[K.callsOutgoing] = s.callsOutgoing
		p[K.callAutoPause] = s.callAutoPause
		p[K.callProposeEntry] = s.callProposeEntry
		writeService(p, "nominatim", s.nominatim)
		writeService(p, "osrm", s.osrm)
		writeService(p, "tiles", s.tiles)
		writeRule(p, "work", s.rounding.work)
		writeRule(p, "travel", s.rounding.travel)
		writeRule(p, "pause", s.rounding.pause)
	}

	/* an unknown stored name (renamed constant, newer app) falls back to the default */
	private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
		name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

	private fun readService(p: Preferences, name: String, d: OnlineService) =
		OnlineService(p[K.enabled(name)] ?: d.enabled, p[K.endpoint(name)] ?: d.endpoint)

	private fun writeService(p: MutablePreferences, name: String, s: OnlineService) {
		p[K.enabled(name)] = s.enabled
		p[K.endpoint(name)] = s.endpoint
	}

	private fun readRule(p: Preferences, name: String, d: RoundingRule): RoundingRule {
		val mode = p[K.mode(name)]?.let { runCatching { RoundingMode.valueOf(it) }.getOrNull() } ?: d.mode
		val q = p[K.quantum(name)]?.takeIf { it > 0 }?.let(Duration::ofMinutes) ?: d.quantum
		val m = p[K.minimum(name)]?.takeIf { it >= 0 }?.let(Duration::ofMinutes) ?: d.minimum
		return RoundingRule(mode, q, m)
	}

	private fun writeRule(p: MutablePreferences, name: String, r: RoundingRule) {
		p[K.mode(name)] = r.mode.name
		p[K.quantum(name)] = r.quantum.toMinutes()
		p[K.minimum(name)] = r.minimum.toMinutes()
	}
}
