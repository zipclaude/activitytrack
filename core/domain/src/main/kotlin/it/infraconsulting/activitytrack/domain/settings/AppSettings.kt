/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.settings

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
	val movementThresholdM: Int = 100,
	val stationaryRadiusM: Int = 100,
	val stationaryMin: Int = 15,
	val carryOverMin: Int = 6 * 60,			/* day attribution carry-over, minutes after midnight */
	val kmFactor: Double = 1.25,			/* straight-line -> road distance fallback */
	val backupRetention: Int = 14,
	val rootUri: String? = null,			/* SAF tree of the user data directory */
	val nominatim: OnlineService = OnlineService(true, "https://nominatim.openstreetmap.org"),
	val osrm: OnlineService = OnlineService(true, "https://router.project-osrm.org"),
	val tiles: OnlineService = OnlineService(true, "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
	val rounding: ReportRounding = ReportRounding.DEFAULT,
) {
	fun validated(): AppSettings = copy(
		confirmIntervalMin = confirmIntervalMin.coerceIn(10, 240),
		maxConfirmations = maxConfirmations.coerceIn(0, 100),
		pauseIntervalMin = pauseIntervalMin.coerceIn(10, 120),
		dayStartDelayMin = dayStartDelayMin.coerceIn(0, 240),
		movementThresholdM = movementThresholdM.coerceIn(50, 5_000),
		stationaryRadiusM = stationaryRadiusM.coerceIn(25, 1_000),
		stationaryMin = stationaryMin.coerceIn(5, 120),
		carryOverMin = carryOverMin.coerceIn(0, 12 * 60),
		kmFactor = if (kmFactor.isNaN()) 1.25 else kmFactor.coerceIn(1.0, 3.0),
		backupRetention = backupRetention.coerceIn(1, 365),
		nominatim = nominatim.copy(endpoint = nominatim.endpoint.trim().trimEnd('/')),
		osrm = osrm.copy(endpoint = osrm.endpoint.trim().trimEnd('/')),
		tiles = tiles.copy(endpoint = tiles.endpoint.trim()),
		rounding = ReportRounding(rule(rounding.work), rule(rounding.travel), rule(rounding.pause)),
	)

	companion object {
		/* Quanta accepted by export profiles (SPEC §4.3). */
		val QUANTA_MIN = listOf(1L, 5L, 15L, 30L, 60L)

		private fun rule(r: RoundingRule): RoundingRule {
			val q = r.quantum.toMinutes().takeIf { it in QUANTA_MIN } ?: 15L
			return r.copy(quantum = Duration.ofMinutes(q), minimum = minOf(r.minimum, Duration.ofHours(1)))
		}
	}
}
