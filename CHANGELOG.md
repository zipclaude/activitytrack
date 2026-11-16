# Changelog

What changes for users, administrators and integrators: behaviour, compatibility and
features. Bug fixes are not listed one by one (one line per release); security fixes are,
under "Security", with their reference. The details are in `git log` and in the issues.
Newest release first.

## 1.0.0 (pre-release: 1.0.0-alfa-1, 1.0.0-alfa-2, 1.0.0-beta-1, 1.0.0-beta-2, 1.0.0-beta-3, 1.0.0-rc-1)

Not released yet: the pre-releases alfa-1, alfa-2, beta-1, beta-2, beta-3 and rc-1 are out, and this list is cumulative
until the release (details in docs/ACTIVITY-MODEL.md).

### Compatibility

- Database: Room schema 23 (22 in 1.0.0-beta-1, which a later pre-release migrates in place), migrated once from 21 (or older) on the first start; there is no way back to 0.9 but the backup made before the upgrade (a 1.0 backup cannot be restored by 0.9).
- Sync: wire schema 4 (3 in alfa-1, alfa-2 and beta-1) with the window reset; the server refuses an app or a PC interface of 0.9, or of a 1.0 pre-release up to beta-1, with `upgrade_required` ("Aggiorna l'app per sincronizzare"), and a newer app gets `server_too_old` from a beta-1 server: app, server and interfaces must all be updated together; after its migration each node's first sync is a full realignment.
- Wire records: the types `todo` and `absence` are gone; `entry` drops `todoUid` and gains `kind`, `deletedUtcMs`, `closedUtcMs`, `externalKey`, the plan, due and priority fields, `absenceKind` and (schema 4) `completedUtcMs`, with the new `status` values (TODO, RUNNING, CLOSED); `segment` gains `planned`; `attachment` gains (schema 4) `description`.
- Data model: the `todo` table (and `todo_id`) and the `absence` table are gone: a to-do is an activity with status `TODO`, an absence an activity of kind `ABSENCE`; entry statuses ACTIVE/SUSPENDED/TO_COMPLETE/CLOSED become RUNNING/CLOSED.
- Interchange: format version 2 (no `todos`, `absences`, `todoKey`; an entry may carry `completedUtc`), which 0.9 cannot read; version 1 files are still imported through the migration.
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
- Four creation buttons on the Home: Da fare, Viaggio, Lavoro (now, already started, or past with its segments), Assenza.
- Displayed states with one icon and colour each (to-do, planned, late, work, travel, pause, suspended, done, incomplete, absence, deleted), the same on the Home, the detail, the agenda, widgets, the tile and notifications; "In <stato> dalle" tells since when.
- The bin: a deleted activity can be restored, or deleted for good from there; a swipe restores one way (toward the start) and deletes for good, after the confirmation, the other (#190). Deleting for good follows the policy: `deleteTodos` for a to-do, `deleteEntries` for any other activity.
- A ticked task can be unticked; "Annulla" after a tick or a done.
- An activity made of travel only can be declared complete ("Completa" among the actions of its detail, undone by "Annulla completamento"); it is then done unless a field the management system requires is empty (issue #189).
- Photos, voice notes and files have a description, typed in the dialog that opens when the attachment is tapped and shown under it in the list; synced with the attachment (issue #185).
- In the activity's detail the segments, expenses, materials, attachments and references are lists of small rows like the Home's; a swipe toward the start deletes one, asked first; tags and references are added with "+" (issue #183).
- Return trip on stop: an activity with an outbound trip offers to start the return trip, to stop, or to stop adding a return trip as long as the outbound one (with its km and its two ends swapped).
- Closing ahead: with editable times a stop, or the end of the last segment, may lie up to 24 hours ahead.
- Saving rounded times (Settings → Arrotondamento): the action buttons store instants rounded to the quanta of the report rules.
- Priority 0–5 with labels (Nessuna, Bassa, Media, Alta, Urgente, Emergenza) in a drop-down.
- A to-do's plan (day, time, duration) and due date on the activity, with its reminder; "Pianifica" from the start of a to-do.
- Restoring a backup of any earlier version migrates it as an upgrade does.
- More → "About and licences": the version, the copyright, the licence of the app and, with their
  texts, the licences of the libraries, icons and fonts in the package (#198).

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
- The detail's "Località" and "Dove" become one field "Luogo": free text or a pick from the sites of the client, of the company and "Casa", with ✕ to clear it; the kind of place stored with the activity (and synced and exported as `locationKind`) follows from it: a client or company site, home, elsewhere (issue #184).
- The route of an activity opens from the button beside "Luogo" in its detail, shown when the activity has location samples; the detail has no top-bar menu any more (issue #191).
- Settings → Sincronizzazione shows the app's version and the protocol (wire schema) it speaks, and the server's version and protocols as its last answer gave them (#194).
- Starting a planned activity as a travel whose place is a site of its client, of its company or home, with coordinates and more than 20 km away in a straight line, asks "Avviare il navigatore verso «…»?" and opens any navigation app on yes; Settings → Posizione → "Proponi il navigatore all'avvio di un viaggio" (on by default) turns the question off (#193).
- The to-do dialog has the place ("Luogo") under "Altre informazioni"; it is the activity's locality (#192).
- Choosing a company with a single client (not archived) on the start screen, in the to-do dialog or in the activity's detail fills the empty client field with it (#182).
- The time grid (to-do plan, absence, segments) scrolls through the 24 hours by dragging the hour labels, besides the Prima/Dopo arrows (#181).
- Lavoro is right of Viaggio wherever the two show side by side (Home, start screen, the running activity's buttons, notification, widget, the reopen question): the button used most under the right thumb (#180).

Various bug fixes.

## Earlier versions

Earlier versions: see the release notes of each tag.
