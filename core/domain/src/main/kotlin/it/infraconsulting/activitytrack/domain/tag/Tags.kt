/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.tag

/*
 * Entry tags (backlog 13, owner 2026-10-18): free words added to an entry,
 * also afterwards. Stored lower case, without the leading '#', blanks
 * turned into '-', only letters, digits, '-' and '_' (a calendar hashtag
 * stops at punctuation), at most [MAX] characters; shown as "#tag".
 */
object Tags {
	const val MAX = 30

	fun normalize(raw: String): String? =
		raw.trim().trimStart('#').trim().lowercase()
			.replace(Regex("\\s+"), "-")
			.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
			.trim('-')
			.take(MAX)
			.takeIf { it.isNotEmpty() }

	/* "#urgente, remoto  #urgente" -> [urgente, remoto] */
	fun parse(text: String): List<String> =
		text.split(',', ';', ' ', '\n').mapNotNull(::normalize).distinct()

	fun show(tags: List<String>): String = tags.joinToString(" ") { "#$it" }
}
