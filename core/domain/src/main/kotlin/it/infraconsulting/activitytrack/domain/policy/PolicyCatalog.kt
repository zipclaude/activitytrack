/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.policy

import it.infraconsulting.activitytrack.domain.rounding.RoundingMode
import it.infraconsulting.activitytrack.domain.rounding.RoundingRule
import it.infraconsulting.activitytrack.domain.settings.AGENDA_VIEWS
import it.infraconsulting.activitytrack.domain.settings.AppSettings
import it.infraconsulting.activitytrack.domain.settings.EntrySwipe
import it.infraconsulting.activitytrack.domain.settings.HOME_STATES
import it.infraconsulting.activitytrack.domain.settings.HOME_WINDOWS
import it.infraconsulting.activitytrack.domain.settings.ListOrder
import it.infraconsulting.activitytrack.domain.settings.OrderedList
import it.infraconsulting.activitytrack.domain.settings.PastSwipe
import it.infraconsulting.activitytrack.domain.settings.TodoSwipe
import it.infraconsulting.activitytrack.domain.settings.TODO_WINDOWS
import it.infraconsulting.activitytrack.domain.settings.WIDGET_WINDOWS
import java.time.Duration

/*
 * What a policy (issue #48) can switch, rename or fix. The JSON keys are
 * the [key]s; an essential item cannot be disabled or hidden.
 */
enum class Feature(val key: String, val essential: Boolean = false) {
	ENTRIES("entries", essential = true),		/* start and stop of activities */
	DAY_REPORT("dayReport", essential = true),
	AUTO_BACKUP("autoBackup", essential = true),
	TODOS("todos"),
	PLANNING("planning"),
	IGNORE_TODOS("ignoreTodos"),
	DELETE_TODOS("deleteTodos"),
	DELETE_ENTRIES("deleteEntries"),
	CONCURRENT_ENTRIES("concurrentEntries"),
	TRAVEL("travel"),
	EXPENSES("expenses"),
	MATERIALS("materials"),
	ATTACHMENTS("attachments"),
	VOICE_NOTES("voiceNotes"),
	DICTATION("dictation"),
	LOCATION("location"),
	CALLS("calls"),
	CALENDAR("calendar"),
	IMPORT("import"),
	EXPORT("export"),
	RESTORE("restore"),
	WIDGETS("widgets"),
	QUICK_TILE("quickTile"),
	GESTURES("gestures");

	companion object {
		val byKey by lazy { entries.associateBy { it.key } }
	}
}

/*
 * Fields of the entry and to-do forms. [essential] fields stay visible,
 * enabled and required; CLIENT is essential unless the policy disables the
 * clients, then COMPANY takes its place (see PolicyValidator).
 */
enum class Field(val key: String, val essential: Boolean = false) {
	COMPANY("company"),
	CLIENT("client", essential = true),
	ROLE("role"),
	QUICK_PICK("quickPick"),
	DESCRIPTION("description", essential = true),
	CONTACT("contact"),
	LOCALITY("locality"),
	PLACE("place"),
	NOTES("notes"),
	TAGS("tags"),
	REFS("refs"),
	KM("km"),
	DATE("date"),
	TIMES("times", essential = true),		/* start and end */
	TODO_PRIORITY("todoPriority"),
	TODO_DUE("todoDue"),
	TODO_PLAN("todoPlan");

	companion object {
		val byKey by lazy { entries.associateBy { it.key } }
	}
}

/* Where entries come from: anywhere, or only from to-dos (e.g. a management system's tickets). */
enum class EntrySource(val key: String) {
	FREE("free"),
	TODOS_ONLY("todosOnly");

	companion object {
		val byKey by lazy { entries.associateBy { it.key } }
	}
}

/* Who creates companies, clients and contacts: the user, or imports only. */
enum class MasterDataSource(val key: String) {
	USER("user"),
	IMPORT_ONLY("importOnly");

	companion object {
		val byKey by lazy { entries.associateBy { it.key } }
	}
}

/*
 * A user setting a policy may set, lock or hide. [check] returns an error
 * text for a bad value, [apply] writes a checked value into AppSettings.
 */
class SettingSpec(
	val key: String,
	val kind: Kind,
	val check: (Any) -> String?,
	val apply: (AppSettings, Any) -> AppSettings,
	/* what [check] accepts, for the web editor (issue #117): a range, a list, or free text */
	val min: Number? = null,
	val max: Number? = null,
	val allowed: List<Any>? = null,
	/* a comma list of [allowed] values */
	val multiple: Boolean = false,
	/* the first app version that knows the key: an older one refuses a policy with it (SYNC.md §12.3) */
	val since: String = SINCE_SYNC,
) {
	companion object {
		/* the keys as old as the sync with ActivityTrack Web */
		const val SINCE_SYNC = "0.9.0"
	}

	enum class Kind { BOOLEAN, INT, DOUBLE, STRING }
}

object SettingsCatalog {
	private fun bool(key: String, since: String = SettingSpec.SINCE_SYNC, apply: (AppSettings, Boolean) -> AppSettings) =
		SettingSpec(key, SettingSpec.Kind.BOOLEAN, { null }, { s, v -> apply(s, v as Boolean) }, since = since)

	private fun int(key: String, range: IntRange, apply: (AppSettings, Int) -> AppSettings) =
		SettingSpec(key, SettingSpec.Kind.INT, { v -> if ((v as Int) in range) null else "must be in ${range.first}..${range.last}" }, { s, v -> apply(s, v as Int) }, min = range.first, max = range.last)

	private fun oneOf(key: String, allowed: Collection<Int>, apply: (AppSettings, Int) -> AppSettings) =
		SettingSpec(key, SettingSpec.Kind.INT, { v -> if (v in allowed) null else "must be one of ${allowed.joinToString()}" }, { s, v -> apply(s, v as Int) }, allowed = allowed.toList())

	private fun string(key: String, allowed: Collection<String>, apply: (AppSettings, String) -> AppSettings) =
		SettingSpec(key, SettingSpec.Kind.STRING, { v -> if (v in allowed) null else "must be one of ${allowed.joinToString()}" }, { s, v -> apply(s, v as String) }, allowed = allowed.toList())

	private fun double(key: String, range: ClosedFloatingPointRange<Double>, apply: (AppSettings, Double) -> AppSettings) =
		SettingSpec(key, SettingSpec.Kind.DOUBLE, { v -> if ((v as Double) in range) null else "must be in ${range.start}..${range.endInclusive}" }, { s, v -> apply(s, v as Double) }, min = range.start, max = range.endInclusive)

	/* a free text the [check] accepts (an endpoint URL, a list of names) */
	private fun text(key: String, check: (String) -> String?, apply: (AppSettings, String) -> AppSettings) =
		SettingSpec(key, SettingSpec.Kind.STRING, { v -> check(v as String) }, { s, v -> apply(s, v as String) })

	private fun url(v: String, tiles: Boolean = false): String? = when {
		!v.trim().startsWith("https://") -> "must be an https:// URL"
		tiles && !listOf("{z}", "{x}", "{y}").all { it in v } -> "must contain {z}, {x} and {y}"
		else -> null
	}

	private inline fun <reified E : Enum<E>> names() = enumValues<E>().map { it.name }

	private fun rounding(s: AppSettings, kind: String, f: (RoundingRule) -> RoundingRule): AppSettings = s.copy(
		rounding = when (kind) {
			"work" -> s.rounding.copy(work = f(s.rounding.work))
			"travel" -> s.rounding.copy(travel = f(s.rounding.travel))
			else -> s.rounding.copy(pause = f(s.rounding.pause))
		},
	)

	private val quanta = AppSettings.QUANTA_MIN.map { it.toInt() }

	val all: List<SettingSpec> = listOf(
		bool("editableTimes") { s, v -> s.copy(editableTimes = v) },
		bool("tagsFreeText") { s, v -> s.copy(tagsFreeText = v) },
		bool("updateCheck", since = "0.9.3") { s, v -> s.copy(updateCheck = v) },
		bool("updateBetas", since = "0.9.3") { s, v -> s.copy(updateBetas = v) },
		int("confirmIntervalMin", 10..240) { s, v -> s.copy(confirmIntervalMin = v) },
		int("maxConfirmations", 0..100) { s, v -> s.copy(maxConfirmations = v) },
		int("pauseIntervalMin", 10..120) { s, v -> s.copy(pauseIntervalMin = v) },
		int("dayStartDelayMin", 0..240) { s, v -> s.copy(dayStartDelayMin = v) },
		int("planLeadMin", 0..240) { s, v -> s.copy(planLeadMin = v) },
		int("planSnoozeMin", 10..240) { s, v -> s.copy(planSnoozeMin = v) },
		int("carryOverMin", 0..12 * 60) { s, v -> s.copy(carryOverMin = v) },
		int("movementThresholdM", 50..5_000) { s, v -> s.copy(movementThresholdM = v) },
		int("backupRetention", 1..365) { s, v -> s.copy(backupRetention = v) },
		oneOf("backupEveryHours", it.infraconsulting.activitytrack.domain.backup.BackupSchedule.FREQUENCIES) { s, v -> s.copy(backupEveryHours = v) },
		int("backupHour", 0..23) { s, v -> s.copy(backupHour = v) },
		oneOf("todoWindowHours", TODO_WINDOWS) { s, v -> s.copy(todoWindowHours = v) },
		oneOf("homeTodoWindowHours", TODO_WINDOWS) { s, v -> s.copy(homeTodoWindowHours = v) },
		oneOf("widgetTodoWindowHours", WIDGET_WINDOWS) { s, v -> s.copy(widgetTodoWindowHours = v) },
		oneOf("roundingWorkMin", quanta) { s, v -> s.copy(rounding = s.rounding.copy(work = s.rounding.work.copy(quantum = Duration.ofMinutes(v.toLong())))) },
		oneOf("roundingTravelMin", quanta) { s, v -> s.copy(rounding = s.rounding.copy(travel = s.rounding.travel.copy(quantum = Duration.ofMinutes(v.toLong())))) },
		oneOf("roundingPauseMin", quanta) { s, v -> s.copy(rounding = s.rounding.copy(pause = s.rounding.pause.copy(quantum = Duration.ofMinutes(v.toLong())))) },
		string("dictationLanguage", AppSettings.DICTATION_LANGUAGES) { s, v -> s.copy(dictationLanguage = v) },
		bool("compactDisplay") { s, v -> s.copy(compactDisplay = v) },
		bool("callsIncoming") { s, v -> s.copy(callsIncoming = v) },
		bool("callsOutgoing") { s, v -> s.copy(callsOutgoing = v) },
		bool("callAutoPause") { s, v -> s.copy(callAutoPause = v) },
		bool("callProposeEntry") { s, v -> s.copy(callProposeEntry = v) },
		bool("callsAlways") { s, v -> s.copy(callsAlways = v) },
		bool("nominatimEnabled") { s, v -> s.copy(nominatim = s.nominatim.copy(enabled = v)) },
		bool("osrmEnabled") { s, v -> s.copy(osrm = s.osrm.copy(enabled = v)) },
		bool("tilesEnabled") { s, v -> s.copy(tiles = s.tiles.copy(enabled = v)) },
		/* settings the policy could not reach before (owner, 2026-10-28: every option settable, lockable, hideable) */
		int("stationaryRadiusM", 25..1_000) { s, v -> s.copy(stationaryRadiusM = v) },
		int("stationaryMin", 5..120) { s, v -> s.copy(stationaryMin = v) },
		double("kmFactor", 1.0..3.0) { s, v -> s.copy(kmFactor = v) },
		text("nominatimEndpoint", { url(it) }) { s, v -> s.copy(nominatim = s.nominatim.copy(endpoint = v.trim())) },
		text("osrmEndpoint", { url(it) }) { s, v -> s.copy(osrm = s.osrm.copy(endpoint = v.trim())) },
		text("tilesEndpoint", { url(it, tiles = true) }) { s, v -> s.copy(tiles = s.tiles.copy(endpoint = v.trim())) },
		string("roundingWorkMode", names<RoundingMode>()) { s, v -> rounding(s, "work") { it.copy(mode = RoundingMode.valueOf(v)) } },
		string("roundingTravelMode", names<RoundingMode>()) { s, v -> rounding(s, "travel") { it.copy(mode = RoundingMode.valueOf(v)) } },
		string("roundingPauseMode", names<RoundingMode>()) { s, v -> rounding(s, "pause") { it.copy(mode = RoundingMode.valueOf(v)) } },
		int("roundingWorkMinimumMin", 0..60) { s, v -> rounding(s, "work") { it.copy(minimum = Duration.ofMinutes(v.toLong())) } },
		int("roundingTravelMinimumMin", 0..60) { s, v -> rounding(s, "travel") { it.copy(minimum = Duration.ofMinutes(v.toLong())) } },
		int("roundingPauseMinimumMin", 0..60) { s, v -> rounding(s, "pause") { it.copy(minimum = Duration.ofMinutes(v.toLong())) } },
		/* the swipe gestures (Settings → Gestures) */
		string("gestureTodoToStart", names<TodoSwipe>()) { s, v -> s.copy(gestures = s.gestures.copy(todoToStart = TodoSwipe.valueOf(v))) },
		string("gestureTodoToEnd", names<TodoSwipe>()) { s, v -> s.copy(gestures = s.gestures.copy(todoToEnd = TodoSwipe.valueOf(v))) },
		string("gestureEntryToStart", names<EntrySwipe>()) { s, v -> s.copy(gestures = s.gestures.copy(entryToStart = EntrySwipe.valueOf(v))) },
		string("gestureEntryToEnd", names<EntrySwipe>()) { s, v -> s.copy(gestures = s.gestures.copy(entryToEnd = EntrySwipe.valueOf(v))) },
		string("gesturePastToStart", names<PastSwipe>()) { s, v -> s.copy(gestures = s.gestures.copy(pastToStart = PastSwipe.valueOf(v))) },
		string("gesturePastToEnd", names<PastSwipe>()) { s, v -> s.copy(gestures = s.gestures.copy(pastToEnd = PastSwipe.valueOf(v))) },
		bool("gestureFullSwipe") { s, v -> s.copy(gestures = s.gestures.copy(fullSwipe = v)) },
		/* the views the screens remember */
		oneOf("homeWindow", HOME_WINDOWS) { s, v -> s.copy(homeWindow = v) },
		SettingSpec(
			"homeShow", SettingSpec.Kind.STRING,
			{ v -> (v as String).split(',').map(String::trim).firstOrNull { it.isNotEmpty() && it !in HOME_STATES }?.let { "unknown state $it (allowed: ${HOME_STATES.joinToString()})" } },
			{ s, v -> s.copy(homeShow = (v as String).split(',').map(String::trim).filter(String::isNotEmpty).toSet()) },
			allowed = HOME_STATES.toList(), multiple = true,
		),
		bool("homeFilterEntries") { s, v -> s.copy(homeFilterEntries = v) },
		bool("todoShowIgnored") { s, v -> s.copy(todoShowIgnored = v) },
		string("agendaView", AGENDA_VIEWS) { s, v -> s.copy(agendaView = v) },
		string("orderCompanies", names<ListOrder>()) { s, v -> s.copy(listOrders = s.listOrders.with(OrderedList.COMPANIES, ListOrder.valueOf(v))) },
		string("orderClients", names<ListOrder>()) { s, v -> s.copy(listOrders = s.listOrders.with(OrderedList.CLIENTS, ListOrder.valueOf(v))) },
		string("orderRoles", names<ListOrder>()) { s, v -> s.copy(listOrders = s.listOrders.with(OrderedList.ROLES, ListOrder.valueOf(v))) },
		string("orderPresets", names<ListOrder>()) { s, v -> s.copy(listOrders = s.listOrders.with(OrderedList.PRESETS, ListOrder.valueOf(v))) },
	)

	val byKey by lazy { all.associateBy { it.key } }
}
