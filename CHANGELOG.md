# Changelog

What changes for users, administrators and integrators: behaviour, compatibility and
features. Bug fixes are not listed one by one (one line per release); security fixes are,
under "Security", with their reference. The details are in `git log` and in the issues.
Newest release first.

## 1.0.0 (pre-release: 1.0.0-alfa-1, 1.0.0-alfa-2, 1.0.0-beta-1)

Not released yet: the pre-releases alfa-1, alfa-2 and beta-1 are out, and this list is cumulative
until the release (details in docs/ACTIVITY-MODEL.md).

### Compatibility

- Database: Room schema 22, migrated once from 21 (or older) on the first start; there is no way back to 0.9 but the backup made before the upgrade (a 1.0 backup cannot be restored by 0.9).
- Sync: wire schema 3 with the window reset; the server refuses an app or a PC interface of 0.9 with `upgrade_required` ("Aggiorna l'app per sincronizzare"): app, server and interfaces must all be 1.0; after its migration each node's first sync is a full realignment.
- Wire records: the types `todo` and `absence` are gone; `entry` drops `todoUid` and gains `kind`, `deletedUtcMs`, `closedUtcMs`, `externalKey`, the plan, due and priority fields and `absenceKind`, with the new `status` values (TODO, RUNNING, CLOSED); `segment` gains `planned`.
- Data model: the `todo` table (and `todo_id`) and the `absence` table are gone: a to-do is an activity with status `TODO`, an absence an activity of kind `ABSENCE`; entry statuses ACTIVE/SUSPENDED/TO_COMPLETE/CLOSED become RUNNING/CLOSED.
- Interchange: format version 2 (no `todos`, `absences`, `todoKey`), which 0.9 cannot read; version 1 files are still imported through the migration.
- Interchange version 1: a to-do's uid is now derived from its company key and key (issue #177), so importing again after 1.0 an old file that a 1.0 pre-release (alfa-1, alfa-2) had imported does not find the to-dos it created, and makes them again.
- Priority: the scale is reversed, 0 none, 1 low … 5 emergency; the old P1…P5 (1 the highest) are converted once (P1→5 … P5→1) in the database, in version 1 files and by the server; a plugin feeding the old scale must map it (`map` transform).
- Plugin descriptors: `@todo` becomes `@activity` and the export hook `todoDone` becomes `activityClosed`; the old names are still read as aliases (`PluginParser`, `Expr`); `@activity.activities` lists the activity's description and those of the activities pointing at it with a UUID reference.
- Plugin import: an imported to-do is a `TODO` activity matched by (company, external key); its `fields` become the activity's plugin fields.
- Policy: `entries.source = "todosOnly"` becomes `"plannedOnly"` (the old value is still read as an alias); the features `todos`, `ignoreTodos`, `deleteTodos` keep their keys and now apply to `TODO` activities (`ignoreTodos` maps to delete/restore).
- Calendar: a `TODO` activity's plan is a tentative event keyed `plan:<uid>` instead of `todo:<uid>`; the old events are removed on the first calendar sync.
- CSV export: absences are rows of their own, with the new last columns `absence_min` and `absence_kind` (the earlier columns keep their positions).

### Features

- The to-do is a state of the activity: "Da fare" (planned, late), "In corso", "Chiusa", "Assenza", "Eliminata"; a to-do may carry planned segments, drawn dashed, which never count in totals and give way to the real ones when it starts.
- Absences are activities of kind Assenza, Ferie, Permesso or Malattia, with one segment per working day (following the work schedule, lunch breaks and days off) or with the times given; reports count them apart, by kind.
- Four creation buttons on the Home: Da fare, Lavoro (now, already started, or past with its segments), Viaggio, Assenza.
- Displayed states with one icon and colour each (to-do, planned, late, work, travel, pause, suspended, done, incomplete, absence, deleted), the same on the Home, the detail, the agenda, widgets, the tile and notifications; "In <stato> dalle" tells since when.
- The bin: a deleted activity can be restored, or deleted for good from there.
- A ticked task can be unticked; "Annulla" after a tick or a done.
- Return trip on stop: an activity with an outbound trip offers to start the return trip, to stop, or to stop adding a return trip as long as the outbound one (with its km and its two ends swapped).
- Closing ahead: with editable times a stop, or the end of the last segment, may lie up to 24 hours ahead.
- Saving rounded times (Settings → Arrotondamento): the action buttons store instants rounded to the quanta of the report rules.
- Priority 0–5 with labels (Nessuna, Bassa, Media, Alta, Urgente, Emergenza) in a drop-down.
- A to-do's plan (day, time, duration) and due date on the activity, with its reminder; "Pianifica" from the start of a to-do.
- Restoring a backup of any earlier version migrates it as an upgrade does.

### Behaviour

- No "Sospendi" button: an activity is suspended only when another one starts and the policy does not allow concurrent ones.
- Travel asked during work asks "Hai completato l'attività lavorativa?": yes stops the work and starts a new anonymous travel at the same instant, no switches the same activity to travel.
- Reopening a closed activity always asks for confirmation; the gap stays a gap.
- A closed activity with a field its company's plugin requires left empty is incomplete.
- A pause can switch straight to work or travel; "Riprendi" goes back to the kind the pause interrupted.
- When an activity starts, resumes or is reopened before the end ahead of another one (or of its own last segment), that end moves back to the new start, with a notice; a segment that lay wholly ahead (the declared return trip) is removed instead, with its own notice.
- With saving rounded times, a switch stores the nearest round instant, which may lie a little ahead of the clock (the elapsed time reads zero until it comes); a segment that has not begun and would last nothing is dropped.
- A stop time typed without a date is the nearest instant at that time of day (at 10:00, "09:00" is an hour ago); a later end is typed with its date.
- After the upgrade the first sync realigns with the server: the server's version wins for each record, except the changes not synced, which are pushed. When the phone and the server made different activities of the same to-do, the phone is the source of trust, unless a policy signed by the server is in force on the phone; a PC interface never is.
- "Scarta" on a sync error never deletes an activity that holds work and never reached the server.
- `entries.source = "plannedOnly"`: activities start only from a planned or assigned to-do.

Various bug fixes.

## Earlier versions

Earlier versions: see the release notes of each tag.
