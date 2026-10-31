/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * Newer releases on GitHub (ActivityTrack Web SYNC.md §12.4): the public
 * releases of the app's repository, a stable one or, for who subscribed to
 * the betas, a pre-release too. Drafts never count.
 */
data class Release(val version: String, val url: String, val beta: Boolean)

object UpdateCheck {
	const val RELEASES = "https://api.github.com/repos/zipclaude/activitytrack/releases?per_page=20"

	/* the newest release after [current] the user wants, or null */
	fun newer(releasesJson: String, current: String, betas: Boolean): Release? {
		val list = runCatching { Json.parseToJsonElement(releasesJson) as JsonArray }.getOrNull() ?: return null
		return list.mapNotNull { e ->
			val o = e as? JsonObject ?: return@mapNotNull null
			if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
			val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
			val beta = o["prerelease"]?.jsonPrimitive?.booleanOrNull == true
			if (beta && !betas) return@mapNotNull null
			Release(tag.removePrefix("v"), o["html_url"]?.jsonPrimitive?.contentOrNull ?: "", beta)
		}.filter { Versions.compare(it.version, current) > 0 }
			.maxWithOrNull { a, b -> Versions.compare(a.version, b.version) }
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
