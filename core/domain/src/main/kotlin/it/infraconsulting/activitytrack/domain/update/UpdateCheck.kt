/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.update

import java.time.Instant

/*
 * Newer releases from a signed manifest (ActivityTrack Web SYNC.md §12.4, issue #1): the manifest
 * lists releases by channel (prod, beta, test, dev); the user follows some of them. A release comes
 * with its package for this app (component "android"): address and SHA-256. The address is https, or,
 * for the manifest the paired server relays, a path on that server.
 */
data class Release(
	val version: String,
	val channel: String,
	/* the release notes' page, or the releases page */
	val url: String,
	val packageUrl: String,
	val sha256: String,
	val size: Long,
) {
	val beta: Boolean get() = channel != "prod"
}

/* where the manifest comes from (Settings → Updates) */
enum class UpdateSource { UPSTREAM, SERVER, CUSTOM }

object UpdateCheck {
	/* the manifest of the project, the default source */
	const val UPSTREAM_MANIFEST = "https://gitlab.com/infraconsulting/activitytrack/-/raw/main/update-manifest.json"

	/* the releases page, for a release without its own address */
	const val RELEASES_PAGE = "https://gitlab.com/infraconsulting/activitytrack/-/releases"

	/*
	 * Whether a check runs: "Check now" whenever the build can check (a store's app too, where it only tells); the daily one
	 * only when the user wants it and the app is not left to a store.
	 */
	fun runs(manual: Boolean, updateCheck: Boolean, applies: Boolean, canCheck: Boolean): Boolean =
		canCheck && (manual || (updateCheck && applies))

	/* "prod,beta" as the set the user follows; prod is always among them */
	fun channelsOf(text: String): Set<String> =
		(text.split(',').map { it.trim().lowercase() }.filter { it in UpdateManifest.CHANNELS } + "prod").toSet()

	/*
	 * The newest release after [current] in one of [channels] with a package of this app, or null. A
	 * manifest issued before [lastIssued] (the one accepted last from this source) is an older file
	 * shown again: refused. [relative]: server paths are allowed as addresses.
	 */
	fun newer(m: UpdateManifest.Verified, current: String, channels: Set<String>, lastIssued: Instant?, relative: Boolean): Release? {
		if (lastIssued != null && m.issued.isBefore(lastIssued)) throw UpdateManifest.Invalid("older than the last manifest")
		return m.entries.mapNotNull { e ->
			if (e.channel !in channels || Versions.compare(e.version, current) <= 0) return@mapNotNull null
			val p = e.packages.firstOrNull { it.component == UpdateManifest.COMPONENT && (relative || it.url.startsWith("https://")) } ?: return@mapNotNull null
			Release(e.version, e.channel, e.notesUrl.takeIf { it.startsWith("https://") } ?: RELEASES_PAGE, p.url, p.sha256, p.size)
		}.maxWithOrNull { a, b -> Versions.compare(a.version, b.version) }
	}
}

/* "1.2.3", "1.2", "1.2.3-beta.1" (a pre-release before its release); anything else sorts first */
object Versions {
	fun compare(a: String, b: String): Int {
		val x = key(a)
		val y = key(b)
		for (i in 0 until 3) if (x.numbers[i] != y.numbers[i]) return x.numbers[i].compareTo(y.numbers[i])
		return when {
			x.pre == y.pre -> 0
			x.pre == null -> 1
			y.pre == null -> -1
			else -> comparePre(x.pre, y.pre)
		}
	}

	private class Key(val numbers: List<Int>, val pre: String?)

	private fun key(text: String): Key {
		val main = text.trim().removePrefix("v").substringBefore("-")
		val pre = text.trim().substringAfter("-", "").takeIf { it.isNotEmpty() }
		val numbers = main.split(".").map { it.toIntOrNull() ?: return Key(listOf(-1, -1, -1), null) }
		return Key((numbers + listOf(0, 0, 0)).take(3), pre)
	}

	/* beta.2 before beta.10: numeric parts by number */
	private fun comparePre(a: String, b: String): Int {
		val x = a.split(".")
		val y = b.split(".")
		for (i in 0 until minOf(x.size, y.size)) {
			val n = x[i].toIntOrNull()
			val m = y[i].toIntOrNull()
			val c = if (n != null && m != null) n.compareTo(m) else x[i].compareTo(y[i])
			if (c != 0) return c
		}
		return x.size.compareTo(y.size)
	}
}
