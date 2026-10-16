/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

/* Kind of an external reference attached to an entry. */
enum class ExternalRefKind {
	TICKET,
	URL,
	UUID,
	OTHER,
}

object ExternalRefClassifier {
	private val URL_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$")
	private val UUID_RE =
		Regex("^\\{?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}?$")
	/* Ticket-like codes: optional alphabetic prefix, separator, digits (INC0012345, JIRA-42, #1234). */
	private val TICKET_RE = Regex("^#?([A-Za-z][A-Za-z0-9_]*[-_ ]?)?[0-9]+$")

	fun classify(value: String): ExternalRefKind {
		val v = value.trim()
		return when {
			URL_RE.matches(v) -> ExternalRefKind.URL
			UUID_RE.matches(v) -> ExternalRefKind.UUID
			TICKET_RE.matches(v) -> ExternalRefKind.TICKET
			else -> ExternalRefKind.OTHER
		}
	}
}
