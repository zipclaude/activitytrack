# Activity model 1.0

Reference for the 1.0.0 restructuring (owner's proposal and answers of 2026-11-11): the
to-do stops being an entity and becomes a state of the activity. This document is the
contract shared by the app, ActivityTrack Web, the Python library and its interfaces;
`docs/samples/activity-states.json` holds the cases every implementation must pass.

Sections marked **(decided by the assistant)** fill gaps the owner left to us: they are
the first thing to check when reviewing.

## 1. Entities

Only the to-do changes. Every other table keeps its shape.

| Today (DB 21, wire 2) | 1.0 (DB 22, wire 3) |
|---|---|
| `time_entry` + `todo` linked by `time_entry.todo_id` (one to-do, many entries) | `time_entry` alone: the **activity** |
| `todo` with `kind = ABSENCE` | an activity of kind `ABSENCE` with segments of kind `ABSENCE` |
| `absence` (legacy table and wire type) | removed |
| `segment` WORK, TRAVEL, PAUSE | + `ABSENCE`, + `planned` flag |
| server `TodoAssignment` | `ActivityAssignment` (only while the activity is `TODO`) |

**Physical names stay** (decided by the assistant): the table remains `time_entry`, the
children keep `entry_id`, the wire type remains `entry` with `entryUid` in the children.
Renaming would touch every DAO, trigger, server model and test for no gain. Code may call
the Kotlin/Python classes `Activity`; storage and wire keep the old names.

### 1.1 Activity fields

New or changed columns of `time_entry`:

| Column | Type | Meaning |
|---|---|---|
| `kind` | `ACTIVITY` \| `ABSENCE`, not null, default `ACTIVITY` | never changes after creation |
| `status` | `TODO` \| `RUNNING` \| `CLOSED` | the lifecycle (replaces ACTIVE, SUSPENDED, TO_COMPLETE, CLOSED) |
| `mode` | segment kind or null | cache of the open segment's kind; null when none is open (invariant) |
| `deleted_utc_ms` | instant or null | "eliminata": restorable; set → hidden everywhere but the bin |
| `closed_utc_ms` | instant or null | when it was closed (a to-do ticked off has no segment to tell) |
| `activity` | text | the description (the to-do's `title` lands here) |
| `external_key` | text or null | key in the management system; unique with `company_id` |
| `due_epoch_day`, `due_time_min` | | deadline ("Scadenza") |
| `plan_epoch_day`, `plan_start_min`, `duration_min` | | plan ("Data", "Orario", duration optional) |
| `plan_reminded_utc_ms` | | the plan's reminder was shown |
| `priority` | int 0–5, not null, default 0 | 0 none, 1 low, 2 medium, 3 high, 4 urgent, 5 emergency |
| `absence_kind` | `ABSENCE` \| `HOLIDAY` \| `LEAVE` \| `SICK` or null | "Assenza", "Ferie", "Permesso", "Malattia"; set iff `kind = ABSENCE` |

Removed: `todo_id`. Not carried over from the to-do: `color` (the colour comes from the
client, else the company; tags keep theirs), `plan_end_epoch_day` (a multi-day absence is
one segment per day), `ignored_utc_ms` (becomes `deleted_utc_ms`), `done` (becomes
`status = CLOSED`), `refs` (become `external_ref` rows, as entries already do).
`plugin_fields` is one JSON object: the to-do's values, overridden by the entry's.

### 1.2 Segment

`segment.kind` gains `ABSENCE`; new column `planned` (bool, default false).

- A **planned** segment belongs to a `TODO` activity only. It never counts in totals,
  reports, exports or the plugin export; it is drawn dashed.
- On **start** (`TODO → RUNNING`) the planned segments are deleted: the plan has served
  (decided by the assistant; the owner's "converted to real on start" read as "the real
  segment replaces the plan").
- `ABSENCE` segments belong to `ABSENCE` activities only, never planned, never open.

## 2. States

### 2.1 Real states (stored)

The five states the user sees are `kind`, `status` and `deleted_utc_ms` together:

| Shown as | Stored |
|---|---|
| Da fare | `kind = ACTIVITY`, `status = TODO` |
| In corso | `kind = ACTIVITY`, `status = RUNNING` |
| Chiusa | `kind = ACTIVITY`, `status = CLOSED` |
| Assenza | `kind = ABSENCE` (`status` is always `CLOSED`) |
| Eliminata | `deleted_utc_ms` set, whatever the rest |

### 2.2 Displayed states (computed)

One pure function `display(activity, segments, now, zone, missingRequired) → (state, since)`
in the domain library of each codebase. Rules in this order; the first that matches wins.

| # | Condition | State | Icon | Colour |
|---|---|---|---|---|
| 1 | `deleted_utc_ms` set | `DELETED` | bin | grey |
| 2 | `kind = ABSENCE` | `ABSENCE` | dot | pink/fuchsia |
| 3 | `TODO` and late (2.3) | `LATE` | exclamation mark | brown |
| 4 | `TODO` with a plan, a due date or planned segments | `PLANNED` | clock | grey |
| 5 | `TODO` | `TODO` | check box | white |
| 6 | `RUNNING`, open segment WORK | `WORK` | play | green |
| 7 | `RUNNING`, open segment TRAVEL | `TRAVEL` | car | blue |
| 8 | `RUNNING`, open segment PAUSE | `PAUSE` | pause | yellow |
| 9 | `RUNNING`, no open segment | `SUSPENDED` | crescent moon | orange |
| 10 | `CLOSED` and complete (2.4) | `DONE` | check mark | greenish |
| 11 | `CLOSED` | `INCOMPLETE` | stop | reddish |

`since` ("In <stato> dalle"): the open segment's start (6–8); the last segment's end (9,
10, 11; `closed_utc_ms` when there is no segment); the plan's start, else the first planned segment's start (3–5; null without either); the first absence segment's start (2); `deleted_utc_ms` (1).

### 2.3 Late

A `TODO` activity is late when **any** of these holds:
- the due date has passed (`dueOverdue` of today's `TodoPlan.kt`: after the due time,
  or after the due day when there is no time);
- it has planned segments and the last one has ended;
- it has no planned segment and the plan is over (`TodoPlan.over`: after its end; a
  whole-day plan from the next day).

This replaces the two rules that differ today (app and web home vs the web's work page).

### 2.4 Complete

A `CLOSED` activity is complete when it has no real segment at all (a task ticked off), or
when it has a client, at least one WORK segment and every field its company's plugin
declares required is filled (`missingRequired = false`).

## 3. Commands and transitions

| Command | From | To | Effect on segments |
|---|---|---|---|
| `start(kind, at)` kind ∈ WORK, TRAVEL | `TODO` | `RUNNING` | planned segments deleted; open `kind` at `at` |
| `switch(kind, at)` | `RUNNING` with an open segment of another kind | `RUNNING` | close the open one at `at`, open `kind` at `at` |
| `resume(kind, at)` kind ∈ WORK, TRAVEL | `RUNNING`, none open | `RUNNING` | open `kind` at `at` |
| `suspend(at)` (internal, no button) | `RUNNING` | `RUNNING` | close the open one at `at` |
| `stop(at)` | `RUNNING` | `CLOSED` | close the open one at `at`; `closed_utc_ms = now` |
| `tick()` | `TODO` | `CLOSED` | planned segments deleted |
| `reopen(kind, at)` kind ∈ WORK, TRAVEL | `CLOSED` | `RUNNING` | open `kind` at `at`; the gap stays a gap |
| `delete()` | any | same, `deleted_utc_ms = now` | none |
| `restore()` | deleted | same, `deleted_utc_ms = null` | none |

Rules:
- `switch` to PAUSE only from WORK or TRAVEL; from PAUSE to WORK, TRAVEL (owner's change),
  or "Riprendi" = the kind open before the pause.
- **Travel from WORK** (owner's answer): the app asks "Hai completato l'attività
  lavorativa?". Yes → `stop(at)` and a new anonymous activity `start(TRAVEL, at)`, same
  instant. No → `switch(TRAVEL, at)` in the same activity.
- `reopen` always asks for confirmation (both buttons). No "Sospesa" segment is stored.
- `suspend` is what happens to the other activities when one starts and the policy does
  not allow concurrent ones (as today). Without that policy several activities may be
  running, each with at most one open segment.
- `start`/`resume`/`reopen` with a running activity elsewhere follow today's rules.

Creation:

| Button | Creates |
|---|---|
| Da fare | `TODO` with the dialog's fields (plan, due, priority, optional planned segments) |
| Lavoro · Adesso / Già iniziata | `RUNNING`, `start(WORK or TRAVEL, now or chosen time)` |
| Lavoro · Passata | `CLOSED` with the segments entered, one "Salva" button |
| Viaggio | anonymous `RUNNING`, `start(TRAVEL, now)` |
| Assenza | `ABSENCE` (3.1) |

Policy `entries.source = todosOnly` becomes `plannedOnly` (old value read as an alias):
activities may only be started from a `TODO` activity (planned or assigned).

### 3.1 Absences

An absence is an activity of kind `ABSENCE` with one `ABSENCE` segment per day:
- dialog fields: company (optional: null = every company), absence kind (default
  `ABSENCE`), notes, from day, to day (default = from), optional start and end time;
- with times: one segment per day with those times (a "permesso");
- without times: one segment per **working day** in the range, covering that weekday's
  `work_schedule` (two segments when a `lunch_break` splits it); days without schedule and
  `day_off` days are skipped (decided by the assistant; owner agreed 2026-11-11);
- reports count absence segments apart from work, travel and pause.

### 3.2 Return trip on stop (owner, 2026-11-11)

When `stop` is asked for an activity that has a TRAVEL segment followed later by a WORK
segment, and no TRAVEL segment after its last WORK segment, the app offers three choices:

1. **Avvia viaggio di ritorno**: `switch(TRAVEL, at)`; the activity stays running.
2. **Termina l'attività**: `stop(at)`.
3. **Termina l'attività ed aggiungi il viaggio di ritorno**: in one transaction, close the
   open segment at `at`; add a TRAVEL segment (`source = MANUAL`) starting at `at` and
   lasting as long as the **outbound** one (the last TRAVEL segment before the first WORK
   segment that follows it), with the same `km_estimated` and `km_declared`; give it two
   location samples only, its start at the outbound's last sample and its end at the
   outbound's first sample (start and arrival swapped, no intermediate points; none when
   the outbound has no samples); then close the activity with `closed_utc_ms = now`.

The return's end may lie in the future: it is a declared estimate, allowed beyond the
60 minutes of 4.1, so choice 3 is offered only when times are editable (decided by the
assistant). With saving rounded times on, `at` is rounded as a stop and the return keeps the
outbound's exact duration. The same three choices replace today's "return travel" option
of the stop sheet.

## 4. Times

### 4.1 Closing in the future

`stop(at)` and editing the end of the **last** segment accept `at` up to **60 minutes**
after now (decided by the assistant), and only when times are editable
(`AppSettings.editableTimes`, policy `editableTimes`). Every other "no future time" check
stays. A closed activity whose end is in the future shows `DONE`/`INCOMPLETE` at once,
with the end time highlighted.

When another activity starts before that end, the end of the earlier one moves back to the
new start, with a notice (decided by the assistant, owner left it to us).

### 4.2 Saving rounded times

New setting `roundStoredTimes` (Settings → Arrotondamento), default off, effective only
when times are editable. When on, the **action buttons** (start, switch, stop, reopen)
store rounded instants; manual entry always stores what the user types.

- Quantum of a segment = the quantum of its kind's `ReportRounding` rule (work 15,
  travel 5, pause 5 by default); the mode of the rule is not used for instants.
- A start (`start`, `resume`, `reopen`) rounds **down**, never before the end of the
  activity's previous segment.
- A stop rounds **up** (the result may be in the future: 4.1 applies, capped at 60 min).
- A `switch` uses **one** instant for both segments: rounded to the **nearest** multiple
  of the smaller of the two quanta, never before the start of the segment being closed
  (decided by the assistant: down or up alone would favour one of the two kinds).

## 5. Priority

Labels translated (it/en/de/es); a drop-down, default 0. Existing values (today 1 is the
highest, P1…P5) convert once: P1→5, P2→4, P3→3, P4→2, P5→1, empty→0, other values >5 → 1,
<1 → 0. A plugin whose management system uses another scale declares a mapping in its
descriptor.

## 6. Migration (DB 21 → 22, wire 2 → 3, server)

The same algorithm runs in the app's Room migration, in the app's import of an old
interchange file and in the server's data migration. It is written once per language as
a pure function over plain rows and tested against `docs/samples/activity-migration.json` (written with the migration, task 1.2).
New uids are deterministic, so the phone and the server produce the same records:
`uuid5(NS, "<todo uid>/<suffix>")` with `NS = 6f1c2a52-6b1e-5a8e-9d7e-0a5c1e7d1000`.

1. Entries: ACTIVE, SUSPENDED → `RUNNING`; TO_COMPLETE, CLOSED → `CLOSED`
   (`closed_utc_ms` = last segment end, else `updated_utc_ms`); `kind = ACTIVITY`.
2. To-dos of kind ABSENCE → an activity with **the to-do's uid**, `kind = ABSENCE`,
   `absence_kind` from the title when it is one of the four labels in any language, else
   `ABSENCE`; segments per 3.1 (uid suffix `abs/<yyyy-mm-dd>/<n>`). Then the to-do goes.
   An ignored absence to-do goes to the bin (`deleted_utc_ms = ignored_utc_ms`), as an
   ignored task does.
3. To-dos of kind TASK, by the entries linked to them:
   - **none** → an activity with the to-do's uid: `TODO`, or `CLOSED` when done
     (`closed_utc_ms = done_utc_ms`); ignored → `deleted_utc_ms = ignored_utc_ms`;
   - **some, and the to-do is done or one of them is running** → the earliest entry
     (first segment start, then `created_utc_ms`, then uid) absorbs the to-do: due, plan,
     priority, external key, refs as `external_ref`, plugin fields merged (the entry
     wins), title as description when the entry has none. The other entries get an
     `external_ref` of kind UUID with the absorbing activity's uid;
   - **some, the to-do still open and all of them closed** → the to-do stays its own
     `TODO` activity (with its uid) and each entry gets an `external_ref` of kind UUID
     with that uid (the work is not finished: the plan must not vanish).
4. Priority per 5. `legacy absence` rows (none should be left since DB 21) follow 2.
   `external_key` is unique with the company: when several resulting activities would share
   `(company_id, external_key)`, the earliest created (then the lower uid) keeps it, the
   others get none.
5. Drop `todo`, `todo_id`, the `absence` table and wire type.

Sync: the server rejects wire schema < 3 with the error `upgrade_required`, which the
app and the PC interfaces show as "Aggiorna l'app per sincronizzare". After its own
migration a node's first sync is a full realignment: the server's version wins for every
migrated record, except for records with unsynced local changes, which are pushed (the
record itself, the activity a dirty to-do or absence became or the entries that absorbed it,
and what the migration derived from them: absence segments, references; coordinator,
2026-11-12); changes made after the migration are pushed as usual, records the server never
had too. The pushed changes carry the time of that sync, so they win over the fields the
server's migration stamped. Reimporting a phone's data on the server is the fallback
(owner, 2026-11-11).

### 6.1 Restoring an older backup

A backup of any earlier version (DB ≤ 21) is restored as a migration: `DatabaseBackup`
already accepts older schemas and Room migrates them on open, so the 21 → 22 migration
runs on the restored file exactly as on an upgrade. After it the restored data are
treated as freshly migrated: the next sync is the full realignment above. Interchange
files of version 1 follow the same function (section 6). The server's own backups (SQLite
file or PostgreSQL dump) are restored by Django's migrations, the data migration included.
Tests: a v21 backup zip restored into the new app; a v20 one (two migrations in a row).

### 6.2 Wire and interchange

Wire 3, `entry` record: drops `todoUid`; adds `kind`, `deletedUtcMs`, `closedUtcMs`,
`externalKey`, `dueDate`, `dueTimeMin`, `planDate`, `planStartMin`, `durationMin`,
`priority`, `absenceKind`; `status` takes the new values. `segment` adds `planned`.
Types `todo` and `absence` disappear.

Interchange format: `VERSION = 2`, same changes; version 1 files are imported through the
migration function.

## 7. Integrations

- Plugins: imported to-dos become `TODO` activities matched by `(company, external_key)`;
  `@todo` in descriptors becomes `@activity` (the planned fields of the same record);
  the hook `todoDone` becomes `activityClosed`. Both bundled descriptors are updated.
- Calendar: one event per activity; a `TODO` activity's plan is a tentative event keyed
  `plan:<uid>` (was `todo:<uid>`); stale keys are removed on the first run.
- Reminders: the plan's reminder moves to the activity unchanged.
- Policy features `todos`, `ignoreTodos`, `deleteTodos` keep their keys, now about `TODO`
  activities; `ignoreTodos` maps to delete/restore.

## 8. User interface (summary; the owner's proposal is the detailed reference)

- Home: without running activities, four buttons (Da fare, Lavoro, Viaggio, Assenza) in
  the panel; with one, the activity as today and the four buttons top right on a dark grey
  circle. No "Sospendi".
- Detail dialog, top to bottom: state in the home's style ("In <stato> dalle", icon and
  colour, total work time on the right); timeline; the actions of section 3 allowed in
  that state; company, client, description, role, segments (split button Lavoro ·
  Viaggio · Pausa), expenses and attachments, place (pre-filled from a site of the
  client/company/user, else the municipality, when geo services are on), notes, tags;
  Annulla, Salva.
- Creation dialogs as in the table of section 3; the fields of the management systems
  (required among the visible ones, optional under "Altre informazioni").
- Every state's icon and colour come from one table in the UI layer (2.2).

## 9. Versions

App, web, library, cli, tui, desktop, doc: **1.0.0**. The first release is the pre-release
`v1.0.0-alfa-1` (Debian `1.0.0~alfa-1-1`; the update check treats it as a beta), then
betas `v1.0.0-beta-N`. Room
`VERSION = 22`, wire `SCHEMA = 3` with `WINDOW` reset (no older schema accepted),
interchange `VERSION = 2`. Branch `feature/activity-1.0` in every repository, merged into
`next` and then `main` at the release.
