/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.geo

import it.infraconsulting.activitytrack.domain.settings.AppSettings

/*
 * How often a fix is asked while an entry is ACTIVE (issue #155): the working
 * interval, or the finer travel one while any entry is travelling, so the route
 * taken can be drawn. A travel also asks satellite quality; working stays
 * balanced to spare the battery.
 */
data class SamplingPlan(val intervalMs: Long, val highAccuracy: Boolean) {
	companion object {
		fun of(s: AppSettings, travelling: Boolean): SamplingPlan =
			if (travelling)
				SamplingPlan(s.travelSampleSec * 1_000L, highAccuracy = true)
			else
				SamplingPlan(s.workSampleMin * 60_000L, highAccuracy = false)
	}
}
