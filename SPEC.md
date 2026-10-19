# ActivityTrack — functional specification

Status: v0.13 (2026-10-19, M14: field importance, 24-hour to-dos, recent completed entries). Application ID `it.infraconsulting.activitytrack`.

Personal Android app for a freelancer who works for several companies, on their
clients, in different roles. Goal: capture time with minimal interaction and
produce reports, despite poor memory.

## 1. Platform and constraints

| Item | Decision |
|---|---|
| License | GPL-3.0-or-later |
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| SDK | minSdk 33 (Android 13), targetSdk 36 (Android 16), compileSdk 37 (required by Compose BOM 2026.09) |
| Reference device | OnePlus 15R, Android 16 |
| Distribution | personal APK (sideload); no Google Play Services, no proprietary SDKs |
| Database | SQLite via Room, app-private storage |
| User data directory | folder chosen via SAF (persistable permission), e.g. `Documents/ActivityTrack/` with `attachments/` and `backup/` |
| Backup | daily: `VACUUM INTO` snapshot, zipped in `backup/`, retention N |
| UI languages | it, en, de, es (per-app language) |
| Online services | Nominatim (reverse geocoding), OSRM (road distance), OSM tiles — each optional, endpoint configurable |
| Speech-to-text | Android `SpeechRecognizer` (on-device when available); offline option Vosk / whisper.cpp |
| OCR | Tesseract (tesseract4android) |

## 2. Master data

- **Company**; **Client** belongs to exactly one company.
- **Company site**: label, coordinates, radius (default 100 m), address. There is
  no generic "office": work happens at a company site, remotely on a company's
  clients, at a client site, or at home.
- **Client site**: label, coordinates, radius (default 100 m), address.
- **Place**: HOME, OTHER, with coordinates and radius.
- **Role**: Teacher, Consultant, System engineer, Presales, … Chosen per entry.
- **Activity preset**: text with optional default role and company.
  The activity is free text with selectable presets. The default activity and
  role are those of the last entry on the same client in the last 30 days.

### 2.1 Start pre-fill

Priority order:
1. position inside a site (nearest wins): client site → company and client;
   company site → company, client picker limited to that company's clients;
2. otherwise the company of the most recent entry started **today** (work is
   normally for one company per day); client picker limited to its clients;
3. otherwise no pre-fill.

All pre-filled fields stay editable. Location kind is COMPANY / CLIENT from the
matched site, HOME from the home place, OTHER otherwise.

## 3. Entries

### 3.1 State machine

```
status : ACTIVE | SUSPENDED | TO_COMPLETE | CLOSED
mode   : WORK | TRAVEL | PAUSE      (only when ACTIVE)
```

| From | Command | To | Segment ops |
|---|---|---|---|
| (new) | start WORK / TRAVEL | ACTIVE | open |
| ACTIVE(m) | WORK / TRAVEL / PAUSE (≠ m) | ACTIVE(new) | close + open |
| ACTIVE(WORK/TRAVEL) | SUSPEND | SUSPENDED | close |
| SUSPENDED | WORK / TRAVEL | ACTIVE | open |
| ACTIVE / SUSPENDED | STOP | TO_COMPLETE | close if open |
| TO_COMPLETE | COMPLETE | CLOSED | — |
| TO_COMPLETE / CLOSED | REOPEN | SUSPENDED | — |

- Minimal start: client only. A travel may start with the client unknown; the
  client is resolved on arrival (site proximity proposal).
- Several entries may be ACTIVE at the same time.
- Starting an entry while another is ACTIVE asks: *close / suspend / leave
  unchanged*. If the other one is in TRAVEL, it asks whether the travel ended.
- Closing an entry, when suspended entries exist, proposes to resume the most
  recent one.
- Pause affects only the entry it is started on.
- STOP offers *close* or *start return travel*; the return travel belongs to
  the same entry and the entry is closed on arrival.
- Completion requires the client. Without activity text, dictation, voice
  note or attachment the app asks: leave it empty, copy the last activity on
  the same client, or go back to editing.
- TRAVEL and PAUSE segments can be added manually afterwards (time and km).

### 3.2 Other entry data

Locality (text, exported), location kind (COMPANY / CLIENT / HOME / OTHER),
notes, external references (n per entry; kind TICKET / URL / UUID / OTHER
auto-detected), expenses (n per entry, EUR, amount + description + optional
attachment), attachments (photo, audio, receipt, file).

Coordinates are stored and shown on a map but **never exported**; exports and
integrations carry only the locality text.

## 4. Reporting rules

### 4.1 Day attribution

If an entry ends no later than **06:00** (inclusive, configurable) of the day after its
start day, all its time is attributed to the start day. Otherwise every segment
is split at local midnight and each part goes to its own day. Elapsed time is
real (DST transitions are handled).

### 4.2 Per entry and day

- `work = Σ WORK`, `travel = Σ TRAVEL`
- `pause = (last end − first start) − work − travel` — includes explicit pauses
  and suspension gaps; single pauses remain available in the detail view.
- Start and end times are exported exact.

### 4.3 Rounding (configurable per export profile)

| Quantity | Mode | Quantum | Minimum |
|---|---|---|---|
| WORK | CEIL_TOLERANT | 15 min (may become 1, 5, 30, 60) | one quantum |
| TRAVEL | CEIL | 5 min | — |
| PAUSE | FLOOR | 5 min | 5 min |

CEIL_TOLERANT: non-zero durations ≤ q count as q; above that, the remainder r is
dropped when `3·r < q`, otherwise rounded up (tolerance proportional, q/3).
With q = 15: 7 → 15, 19:59 → 15, 20 → 30.

### 4.4 Daily totals

- **Solar**: union of WORK and TRAVEL intervals of all entries attributed to
  the day (concurrent time counted once).
- **Reported**: Σ over entries of rounded WORK + rounded TRAVEL.
- Shown in the day view and in an end-of-day notification.

## 5. Reminders and automation

| Trigger | Behaviour |
|---|---|
| Entry ACTIVE (WORK/TRAVEL) | confirmation every 30 min (configurable), also outside working hours; the entry keeps running if unanswered; max number of notifications configurable |
| Entry in PAUSE | reminder every 15 min, muted during the lunch break (configurable per weekday like working hours, default 12:30–14:00) |
| Movement > 100 m (configurable) during WORK | audible alert on a dedicated high-importance channel; fires only if `distance − accuracy > threshold` on 2 consecutive samples |
| Stationary ≤ 100 m for 15 min during TRAVEL | "Arrived?"; near a client site proposes WORK, near HOME proposes closing |
| No entry 15 min after working-day start | actions: *Absence (full day) / Late entry at… / Start entry* |
| Entries TO_COMPLETE | reminder until completed |
| Planned to-do | 15 min (configurable, advanced) before the planned start: *Start / Postpone 30 min (configurable; from the planned start, or from now once it has passed) / Re-plan*; also outside working hours and on days off; once per planned start, dropped when the block is over, the to-do is done or an entry runs from it |

Working hours: several intervals per weekday. Public holidays (Italian national,
Easter Monday computed) and manual days off suppress the day-start prompt.
Absences: FULL_DAY, LATE_ENTRY (time), EARLY_EXIT (time).

## 6. Location

- Foreground service of type `location` only while an entry is ACTIVE.
- One fix every 5 min; `TYPE_SIGNIFICANT_MOTION` triggers an extra fix.
- TRAVEL: fix at start, every 5 min, at stop; km estimated via OSRM routing
  between consecutive fixes, fallback straight-line distance × configurable
  factor. `km_declared` overrides `km_estimated` in exports.
- Uses: locality proposal (Nominatim), company/client proposal (site within radius),
  location kind, travel track and km, movement alerts.

## 7. Quick interaction

- Ongoing notification with actions, confirmation notifications with actions.
- Command buttons as coloured icons: start/resume green (play), stop red
  (stop), pause yellow (pause), suspend orange (moon), travel blue (car).
- Glance widgets: 4×1 (running entry with its commands and €, or recent
  clients), 1×1 WORK/PAUSE toggle; Quick Settings tile with the same toggle
  (added from Settings: some launchers do not list it).
- Launcher shortcuts: the 4 most recent clients and "Expense".
- Nothing that needs a confirmation starts outside the app: widgets and
  shortcuts open the start screen with the client picked.
- Client fields are searchable combo boxes (case and accents ignored).
- Dictation of the activity text through the system speech recognizer; its
  language is a setting (default Italian).
- Completion with the client only, after a confirmation (leave empty / copy
  the last activity on the client / edit).

## 8. Export, import and integrations

- **Interchange model** (docs/INTERCHANGE.md): companies, clients, sites,
  roles, presets, to-dos, entries with segments, expenses and references,
  absences, days off; every section optional; never location samples.
- **Connectors**: plugins compiled into the app, one per company (management
  system); the generic XML / JSON plugin first, API or web-service plugins
  later. Settings per company; secrets encrypted with the Android keystore.
- **Import** is idempotent (external codes, uids); on conflicts the app data
  wins and the report lists them; invalid records are skipped with a reason.
- **Export**: CSV report rows or the interchange (JSON / XML) with sections,
  period, company and client filters; to the data folder and the share sheet.
- **To-dos**: planned activities (title, client, due date/time, priority,
  notes, references), imported or created in the app; an entry can start from
  a to-do (pre-filled, references copied); completing it asks whether the
  to-do is done; home shows the to-dos due or planned today, and flags as
  overdue those still open past their planned block or their due date/time
  (date only: from the next day).
- **Planned to-dos (M9)**: states open (no plan) → planned (local date and
  start time, apart from the due date) → done. Optional duration; without it
  the block lasts one hour and grows hour by hour while the to-do stays open,
  up to the end of the planned day. Created by hand (To-dos, agenda) or
  imported (`plannedDate`, `plannedTime`, `durationMin`).
- **Backup restore**: a daily backup replaces all data after a safety backup.

### 8.1 Calendar sync

One-way to the phone's calendars (Google account or CalDAV via DAVx5): one
event per entry and report day, from the entry start (a running entry ends
"now"), title "Client – Activity", rounded times, km, references and notes in
the description, locality as location. A generic calendar (chosen, or a local
"ActivityTrack" one) and optionally one per company, which replaces it.
Planned open to-dos become tentative events ("Client – Title") up to a year
ahead; done ones leave the calendar. The last description line carries
hashtags per kind of time (#lavoro #viaggio #pausa, #pianificata; always Italian).

### 8.2 Consultation and navigation

**Home (M10)**: the big card always shows: the most recent open entry, or the
one tapped, or an empty-state message; every other open entry has a small
card (about a third of it) with a resume / pause / complete button; the
to-dos due or planned by today follow under a separator, one card each, the
ones ignored today at the bottom.

**Swipe gestures (M10)**, on Home, Agenda list and To-dos, configurable per
card type and direction (layout-relative start/end): to-do left = Start,
right = Postpone (if planned) or Ignore, an ignored to-do restores; entry
left = Pause / Resume, right = Stop (same questions as the button) or
Suspend. A short swipe reveals the buttons, a long one runs the first
(switchable). **Ignored** to-dos replace deletion: kept struck through at
the bottom, no reminder nor calendar event, never restored by an import.

**Home start (M11)**: the bottom start button is gone. The empty big card
holds Start (start screen) and Travel (starts at once, no destination) and
swipes left = Start, right = Travel; with entries open, Start and Travel
are in the top bar (Travel opens the start screen in travel mode, which asks
about the running entries). **Compact display** (setting): text at 88 %,
tighter cards and buttons. **Quick add**: "+" beside Company and Client in
the start screen and the to-do editor creates one and selects it.

**M14**: field borders by importance (required red until filled, then
green; important yellow; accessory blue); company and client colour dots in
drop-downs; to-dos in the next 24 hours (overdue included) on Home and with
the "Next 24 h" chip, "Ignored" chip to list or hide ignored ones; Home keeps
the last 2 (normal) or 3 (compact) completed entries as small dimmed cards,
open background entries taking their places first.

**Backlog, first part (M13)**: settings in sub-screens (Gestures among
them) and a Notifications section opening the Android page of each
channel (banner, sound, vibration); "+" also for preset and role in the
start screen; coloured icon buttons for the "add" actions of the entry
screen; a plan with day and no time (or the reverse) is flagged on the
field; anything under one minute is an error (manual segments rejected; an
entry stopped from the app under a minute, with no attachment or expense,
can be discarded); To-dos "Today" filter; agenda Day/Week cover the working
hours, open on the current activity and can fit the working day to the
screen.

**Colours (M12)**: a colour per company and client (12-colour palette or
none; interchange `color` "#RRGGBB"). Entry and to-do cards carry a thin
band at the start edge in the company colour; a to-do list that shows a
single company uses the client colour. The agenda uses the chosen company
colours. A to-do **company filter** (Home section and To-dos screen) stays
until removed; entries are not filtered.

**Pickers (M10)**: every date and time field uses the Material date and time
pickers (optional ones can be cleared).

Bottom navigation: Home · Agenda · Report · More. The agenda shows the
report lines and the to-dos due as a list (with search), a day or week
timeline (concurrent entries side by side) and a month grid with reported
hours; one colour per company; taps open the entry or the day. Planned
to-dos are outlined blocks; a tap on a free slot (half-hour steps) or "Add"
plans a new to-do there.

## 9. Milestones

| | Scope |
|---|---|
| M0 | Gradle skeleton, license, domain rules with tests, Room schema |
| M1 | master data, entries and state machine UI, pauses, reminders, schedules, rounding, CSV export, SAF directory, daily backup |
| M2 | travel and location, locality, site proposal, movement alerts, day-start prompt |
| M3 | widgets, tile, shortcuts, quick expenses, attachments, dictation |
| M4 | export and import (XML / JSON exporters, import; details to agree) |
| M5 | external calendar sync: a generic calendar for the app and/or one per company (Google Calendar or CalDAV) |
| M6 | consultation of saved entries: list, day agenda, week and month calendars |
| M7 | user interface polish |
| M8 | review fixes, backup restore, user manual |
| M14 | field importance borders, drop-down colours, 24-hour to-dos, ignored filter, recent completed entries on Home |
| M13 | owner backlog 2, 4, 5, 6, 7, 9, 10, 12 (settings sub-screens, notifications, quick add, agenda fit, Today filter, one-minute rule, coloured add buttons) |
| M12 | colours of companies and clients (bands, agenda), to-do company filter (schema v6) |
| M11 | Home start controls (empty card, top bar), compact display, quick add of companies and clients |
| M10 | UI: Home cards, swipe gestures (Gestures screen), date/time pickers, ignored to-dos (schema v5) |
| M9 | planned to-dos: manual and imported, planned date/time and duration, reminder with postpone / re-plan, agenda planning, calendar events, tags |

Owner priorities (2026-10-17): M4 → M7 in this order.

## 10. Future improvements (to do)

- OCR of receipts (tesseract4android) to pre-fill expenses.
- Offline speech-to-text with free software (Vosk / whisper.cpp).
