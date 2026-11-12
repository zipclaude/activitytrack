/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.sync

/*
 * The app's side of the versions rules (ActivityTrack Web SYNC.md §12):
 * the wire schema this app speaks, named with the app's version in two
 * headers of every request. Raise SCHEMA together with the server when what
 * travels changes; the server keeps speaking the two schemas before.
 */
object WireVersion {
	/* 3: activity model 1.0 (ACTIVITY-MODEL.md 6.2); the server reset its window, older apps get UPGRADE_REQUIRED */
	const val SCHEMA = 3
	const val APP_HEADER = "X-ActivityTrack-App"
	const val SCHEMA_HEADER = "X-ActivityTrack-Schema"

	/* the 426 answer: what has to be updated */
	const val APP_TOO_OLD = "app_too_old"
	/* schema 3 servers: the app speaks a schema the server no longer does ("Aggiorna l'app per sincronizzare") */
	const val UPGRADE_REQUIRED = "upgrade_required"
	const val SERVER_TOO_OLD = "server_too_old"

	fun headers(appVersion: String): Map<String, String> = mapOf(APP_HEADER to appVersion, SCHEMA_HEADER to SCHEMA.toString())
}
