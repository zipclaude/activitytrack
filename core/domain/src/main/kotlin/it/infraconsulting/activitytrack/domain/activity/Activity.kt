/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.activity

/*
 * Activity model 1.0 (docs/ACTIVITY-MODEL.md): the to-do is no longer an
 * entity but a state of the activity. Storage keeps the old names
 * (`time_entry`, `entry_id`, wire type `entry`); the code calls it activity.
 */

/* What an activity is; never changes after creation (1.1). */
enum class ActivityKind {
	ACTIVITY,
	ABSENCE,	/* status always CLOSED, segments of kind ABSENCE only */
}

/* The lifecycle (1.1); replaces ACTIVE, SUSPENDED, TO_COMPLETE, CLOSED. */
enum class ActivityStatus {
	TODO,		/* "Da fare": not started, may have a plan, a due date, planned segments */
	RUNNING,	/* "In corso": at most one open segment, possibly none (suspended) */
	CLOSED,		/* "Chiusa" (or an absence) */
}

/* The kind of an ABSENCE activity: "Assenza", "Ferie", "Permesso", "Malattia". */
enum class AbsenceKind {
	ABSENCE,
	HOLIDAY,
	LEAVE,
	SICK;

	companion object {
		/*
		 * The four labels in it/en/de/es (the same table as the Python reference
		 * of the migration, scripts/activity_migration.py): a legacy absence
		 * to-do's title names its kind (6, step 2).
		 */
		val LABELS: Map<AbsenceKind, List<String>> = mapOf(
			ABSENCE to listOf("Assenza", "Absence", "Abwesenheit", "Ausencia"),
			HOLIDAY to listOf("Ferie", "Holiday", "Urlaub", "Vacaciones"),
			LEAVE to listOf("Permesso", "Leave", "Freistellung", "Permiso"),
			SICK to listOf("Malattia", "Sick leave", "Krankheit", "Enfermedad"),
		)

		private val byLabel: Map<String, AbsenceKind> =
			LABELS.flatMap { (k, ls) -> ls.map { it.lowercase() to k } }.toMap()

		/* The kind a title names (trimmed, any case), else ABSENCE. */
		fun fromTitle(title: String?): AbsenceKind = byLabel[title?.trim()?.lowercase()] ?: ABSENCE

		fun of(name: String?): AbsenceKind? = entries.firstOrNull { it.name == name }
	}
}

/* Priority 0..5 (5), the stored value is [value]; higher is more pressing. */
enum class Priority(val value: Int) {
	NONE(0),
	LOW(1),
	MEDIUM(2),
	HIGH(3),
	URGENT(4),
	EMERGENCY(5);

	companion object {
		/* The stored value; out of range is clamped (a value of a newer node). */
		fun of(value: Int): Priority = entries[value.coerceIn(0, 5)]

		/*
		 * The to-do's old priority (1 the highest, P1..P5), converted once (5):
		 * P1 -> 5 ... P5 -> 1, empty -> 0, above 5 -> 1, below 1 -> 0.
		 */
		fun fromLegacy(old: Int?): Priority = when {
			old == null || old < 1 -> NONE
			old > 5 -> LOW
			else -> of(6 - old)
		}
	}
}
