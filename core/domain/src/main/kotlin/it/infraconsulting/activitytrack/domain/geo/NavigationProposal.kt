/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.domain.geo

import java.net.URLEncoder
import java.util.Locale

/* A place with coordinates the navigator can be sent to: a site of the client or the company, or home. */
data class NavTarget(val label: String, val point: GeoPoint)

/*
 * Issue #193: a to-do started as a travel whose place names a site of its
 * client, of its company or home (in this order) with coordinates, farther
 * than [MIN_DISTANCE_M] in a straight line from where the phone is, gets
 * the question "Avviare il navigatore verso «…»?".
 */
object NavigationProposal {
	const val MIN_DISTANCE_M = 20_000.0

	/*
	 * The site to navigate to, or null: no place, no site named so (trimmed,
	 * case ignored), no position, or not over the threshold. [groups] in order
	 * of preference: client sites, company sites, home.
	 */
	fun target(locality: String?, groups: List<List<NavTarget>>, position: GeoPoint?): NavTarget? {
		val name = locality?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		position ?: return null
		val site = groups.firstNotNullOfOrNull { g -> g.firstOrNull { it.label.trim().equals(name, ignoreCase = true) } } ?: return null
		return site.takeIf { Geo.distanceM(position, it.point) > MIN_DISTANCE_M }
	}

	/* Turn-by-turn navigation (Google Maps and the apps that take its scheme); coordinates with a dot, whatever the locale */
	fun navigationUri(t: NavTarget): String = "google.navigation:q=${coordinates(t.point)}"

	/* Any map or navigation app: geo:lat,lng?q=lat,lng(label) */
	fun geoUri(t: NavTarget): String {
		val c = coordinates(t.point)
		val label = URLEncoder.encode(t.label.trim(), Charsets.UTF_8).replace("+", "%20")
		return "geo:$c?q=$c($label)"
	}

	private fun coordinates(p: GeoPoint) = String.format(Locale.ROOT, "%.6f,%.6f", p.lat, p.lon)
}
