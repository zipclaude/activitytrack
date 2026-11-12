/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import kotlinx.serialization.Serializable

/*
 * Interchange model (M4, docs/INTERCHANGE.md): what connectors read from and
 * write to management systems, and what the generic XML/JSON plugin stores.
 * Every section is optional, so a file may carry only clients or only to-dos.
 * Version 2 (activity model 1.0, docs/ACTIVITY-MODEL.md 6.2): the to-do is an
 * entry with status TODO, an absence an entry of kind ABSENCE; [todos] and
 * [absences] are read from version 1 files only, which InterchangeUpgrade
 * turns into version 2 through the migration function.
 *
 * Records are identified by [key]: the management system's code, or for data
 * born in the app its uid (entries) or name (master data). References between
 * records use keys. Times are ISO-8601 UTC instants with the zone apart, dates
 * ISO local dates, amounts decimal strings. Location samples never travel.
 */
@Serializable
data class Interchange(
	val format: String = FORMAT,
	val version: Int = VERSION,
	val generator: String? = null,
	val createdUtc: String? = null,
	val companies: List<XCompany> = emptyList(),
	val clients: List<XClient> = emptyList(),
	val sites: List<XSite> = emptyList(),
	val roles: List<XRole> = emptyList(),
	val presets: List<XPreset> = emptyList(),
	/* version 1 only */
	val todos: List<XTodo> = emptyList(),
	val entries: List<XEntry> = emptyList(),
	/* version 1 only */
	val absences: List<XAbsence> = emptyList(),
	val daysOff: List<XDayOff> = emptyList(),
) {
	companion object {
		const val FORMAT = "activitytrack-interchange"
		const val VERSION = 2
	}
}

@Serializable
data class XCompany(val key: String, val name: String, val archived: Boolean = false, val color: String? = null)

@Serializable
/* [companyKey] null: a client of no company (issue #48, policy without companies) */
data class XClient(val key: String, val companyKey: String? = null, val name: String, val archived: Boolean = false, val color: String? = null)

/* A site of a company or of a client ([ownerKind] "company" / "client"). */
@Serializable
data class XSite(
	val ownerKind: String,
	val ownerKey: String,
	val label: String,
	val address: String? = null,
	/* site coordinates are master data (an address), not tracking; optional on export */
	val lat: Double? = null,
	val lon: Double? = null,
	val radiusM: Int? = null,
	/* for client sites: the client's company, when client keys repeat across companies */
	val companyKey: String? = null,
)

/* "#RRGGBB" <-> 0xRRGGBB (M12 colours); anything else is no colour */
object XColor {
	fun parse(s: String?): Int? = s?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)
	fun format(c: Int?): String? = c?.let { "#%06X".format(it and 0xFFFFFF) }
}

@Serializable
data class XRole(val name: String, val archived: Boolean = false)

@Serializable
data class XPreset(val text: String, val companyKey: String? = null, val roleName: String? = null)

/* Version 1: a planned activity from a management system (owner decision 2026-10-17); an entry with status TODO since version 2. */
@Serializable
data class XTodo(
	val key: String,
	/* null: a to-do of no company (issue #48) */
	val companyKey: String? = null,
	val clientKey: String? = null,
	/* an absence may have no title (issue #158) */
	val title: String = "",
	val notes: String? = null,
	val refs: List<String> = emptyList(),
	val dueDate: String? = null,
	val dueTime: String? = null,
	val priority: Int? = null,
	val done: Boolean = false,
	/* planned slot (M9), apart from the due date: local date and time, optional duration */
	val plannedDate: String? = null,
	val plannedTime: String? = null,
	val durationMin: Int? = null,
	/* set aside in the app (M10); an import never re-creates or restores an ignored to-do */
	val ignored: Boolean = false,
	/* TASK, or ABSENCE: a planned absence, with no company, client or due date (issue #158) */
	val kind: String = "TASK",
	/* "#RRGGBB" of the agenda block and the card */
	val color: String? = null,
	/* last day of a whole-day plan lasting several days */
	val plannedEndDate: String? = null,
)

/*
 * An activity. Version 2: [status] TODO, RUNNING or CLOSED, [kind] ACTIVITY
 * or ABSENCE, and the planned fields; [todoKey] is version 1 only.
 */
@Serializable
data class XEntry(
	val key: String,
	val companyKey: String? = null,
	val clientKey: String? = null,
	/* version 1 only */
	val todoKey: String? = null,
	val role: String? = null,
	val activity: String? = null,
	val status: String,
	val kind: String = "ACTIVITY",
	/* ISO instants: deleted (restorable), closed */
	val deletedUtc: String? = null,
	val closedUtc: String? = null,
	/* key in the management system: an imported TODO activity is matched by it */
	val externalKey: String? = null,
	val dueDate: String? = null,
	val dueTime: String? = null,
	val plannedDate: String? = null,
	val plannedTime: String? = null,
	val durationMin: Int? = null,
	/* 0 none ... 5 emergency (ACTIVITY-MODEL.md 5) */
	val priority: Int = 0,
	/* ABSENCE, HOLIDAY, LEAVE, SICK: set for kind ABSENCE only */
	val absenceKind: String? = null,
	val locality: String? = null,
	val locationKind: String? = null,
	val notes: String? = null,
	val zone: String,
	val segments: List<XSegment> = emptyList(),
	val expenses: List<XExpense> = emptyList(),
	val refs: List<String> = emptyList(),
	/* M15: free tags, without '#' */
	val tags: List<String> = emptyList(),
	/* issue #41: consumables left */
	val materials: List<XMaterial> = emptyList(),
)

/*
 * Running on the device that wrote it (a segment still open): an import
 * skips it, or it would run twice. A RUNNING activity with nothing open
 * (suspended, e.g. a SUSPENDED entry of a version 1 file) is not.
 */
fun XEntry.runsElsewhere(): Boolean = segments.any { it.endUtc == null }

@Serializable
data class XSegment(
	val kind: String,
	val startUtc: String,
	val endUtc: String? = null,
	val source: String = "TRACKED",
	val kmEstimated: Double? = null,
	val kmDeclared: Double? = null,
	/* version 2: a planned segment of a TODO activity */
	val planned: Boolean = false,
)

/* A consumable left (issue #41): quantity and unit cost (EUR) optional. */
@Serializable
data class XMaterial(val description: String, val quantity: String? = null, val unit: String? = null, val unitCost: String? = null)

/* EUR; the receipt file does not travel, only the fact that there is one. */
@Serializable
data class XExpense(val amount: String, val description: String, val createdUtc: String, val receipt: Boolean = false)

/* Version 1 only. */
@Serializable
data class XAbsence(val date: String, val kind: String, val time: String? = null)

@Serializable
data class XDayOff(val date: String, val note: String? = null)
