/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

/* Rejected user input; the UI maps [reason] to a localized message. */
class ValidationException(val reason: Reason, detail: String? = null) :
	IllegalArgumentException(detail ?: reason.name) {
	enum class Reason {
		BLANK_NAME,
		DUPLICATE_NAME,
		INVALID_COORDINATES,
		INVALID_RADIUS,
		INVALID_TIME_RANGE,
		OVERLAPPING_INTERVALS,
		CLIENT_COMPANY_LOCKED,		/* client has entries: company cannot change */
		CLIENT_COMPANY_MISMATCH,	/* entry company differs from the client's company */
		CLIENT_REQUIRED,		/* WORK entries need a client */
		IN_USE,				/* referenced by other rows */
		COMPLETION_INCOMPLETE,		/* no activity text and no attachment */
		INVALID_SEGMENT,
		OVERLAPPING_SEGMENTS,
		NOT_FOUND,
		BLANK_VALUE,
		NOT_MANUAL,			/* only manual segments can be deleted */
		INVALID_DATE,
		INVALID_TIME,			/* a single time of day, not a range */
		STORAGE,			/* the user data folder cannot be read or written */
		NO_VIEWER,			/* no app can open the file */
		INVALID_AMOUNT,			/* expense amount missing or not positive */
		TOO_SHORT,			/* a segment or entry under one minute (backlog 10) */
		LAST_SEGMENT,			/* an entry keeps at least one segment; the running one stays (backlog 11) */
	}
}
