/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.sync

/*
 * The app's side of the versions rules (ActivityTrack Web SYNC.md §12):
 * the wire schema this app speaks, named with the app's version in two
 * headers of every request. Raise SCHEMA together with the server when what
 * travels changes; the server keeps speaking the two schemas before.
 */
object WireVersion {
	const val SCHEMA = 1
	const val APP_HEADER = "X-ActivityTrack-App"
	const val SCHEMA_HEADER = "X-ActivityTrack-Schema"

	/* the 426 answer: what has to be updated */
	const val APP_TOO_OLD = "app_too_old"
	const val SERVER_TOO_OLD = "server_too_old"

	fun headers(appVersion: String): Map<String, String> = mapOf(APP_HEADER to appVersion, SCHEMA_HEADER to SCHEMA.toString())
}
