# ActivityTrack — instructions and architectural decisions

Owner: Zippo. Last update: 2026-10-15.
Companion documents: [SPEC.md](../SPEC.md) (functional rules), [DESIGN.md](DESIGN.md)
(technical design), [CLAUDE.md](../CLAUDE.md) (assistant resume notes).

## 1. Assignment

Write a new open-source Android app for time tracking. Existing open-source
software may be used, paying attention to license compatibility; the project
license is chosen accordingly. Start from a simple working prototype and extend
it incrementally.

## 2. Requirements stated by the owner

### 2.1 Context
- Freelancer working for several **companies**, on their **clients**, in
  different **roles**. Poor memory: the app must be immediate to use, with home
  screen widgets and quick-settings interaction.
- Reporting granularity is normally 15 min; activities may last a full day or
  extend to the next day (night maintenance).

### 2.2 Data to collect per activity
start date, start time, end date, end time, pause hours, travel hours,
company, client, role, activity, travel km, expenses, notes, external
reference, locality.

### 2.3 Master data
- A client belongs to exactly one company (exceptions: two clients with
  different names).
- Role depends on the kind of work: Teacher, Consultant, System engineer,
  Presales, …
- Activity: free text plus selectable presets; default = activity of the last
  entry on the same client within 30 days.
- Companies and clients have **sites** (locations); plus the HOME place. There
  is no generic office: work happens at a company site, remotely on its
  clients, at a client site, or at home.
- Position inside a company site → fill the company and propose its clients.
- Work is normally for the same company the whole day → a new entry copies the
  company of the previous one.

### 2.4 Activity lifecycle
- Start with minimal information: the **client** only (a travel may start with
  the client still unknown, resolved on arrival).
- At closing, ask for the remaining data, at least the activity text, a voice
  note, dictation or an attachment.
- Activities can be suspended and interleaved; several may be open at once.
  Starting a new one while another runs asks: close / suspend / leave
  unchanged. Closing one proposes to resume a suspended one.
- Pause suspends only the current activity.
- Travel is a state of the activity (outbound, between client sites, return);
  it can be added afterwards (time and km).

### 2.5 Reminders
- Every 30 min (configurable) during an active entry: confirm it is still in
  progress. Applies also outside working hours. The entry keeps running when
  unanswered; maximum number of notifications configurable.
- During pause: every 15 min "still on pause?", except during the lunch break.
- Working hours and lunch break are configurable per weekday.
- Movement > 100 m (configurable) during an activity, not in travel: distinct,
  audible notification.
- During travel, staying within 100 m for 15 min: confirmation prompt. Starting
  an activity during travel asks whether the travel ended (meetings may happen
  while travelling).
- 15 min after the working-day start with no activity: propose absence,
  late entry / early exit, or starting an activity.

### 2.6 Reporting rules
- Timestamps in the DB are always exact.
- Work: rounded up with tolerance — below one quantum counts one quantum; then
  remainder < q/3 rounds down, otherwise up (q = 15 → <20 min = 15, ≥20 = 30).
  The quantum may become 1, 5, 30 or 60 min; tolerance stays proportional.
- Travel: rounded up to 5 min. Pause: rounded down to 5 min, minimum 5 min.
  Rounding is configurable per export.
- Concurrent activities: time counted in full on each. End of day: total worked
  (solar time, travel included) and total reported (sum of activities).
- Single pauses are tracked; generic reports show the cumulative pause.
- An activity ending before 06:00 of the next day is attributed entirely to its
  start day; otherwise it is split across the days.
- Absences: full day, late entry at a time, early exit at a time.

### 2.7 Location
- Precise position, one fix every 5 min is enough; battery consumption matters.
- Purposes: propose the locality (OSM), propose the client when within 100 m of
  a client site, know where the work was done (company / client / home),
  estimate travel km and remember the route, movement alerts.
- Coordinates are stored and displayed, **never exported**; reports, exports and
  integrations carry only the locality text.
- Online services (OSM-based) are accepted but must be individually disableable.

### 2.8 Attachments and input
- Photos, voice notes, expenses with receipt photo, dictation of the activity.
- Expenses: several per activity, EUR only, amount + description + attachment;
  quick entry during travel or activity.
- Speech-to-text, OCR, etc.: use Android built-ins or free software.
- External reference: ticket code, URL or UUID; several per activity, separate
  table with internal id.

### 2.9 Storage, export, distribution
- SQLite database; attachments in a dedicated, externally accessible directory;
  daily backup.
- Export: CSV now; later plug-ins for management systems via XML or JSON.
- Personal APK for now. Device: OnePlus 15R, Android 16; keep compatibility down
  to Android 13 if possible.
- UI languages: Italian, English, German, Spanish.
- Name **ActivityTrack**, domain `activitytrack.infraconsulting.it` → application ID
  `it.infraconsulting.activitytrack`. Public repository on GitHub.

## 3. Architectural decisions

| # | Decision | Rationale |
|---|---|---|
| A1 | License GPL-3.0-or-later | copyleft; compatible with Apache-2.0 / MIT / BSD dependencies |
| A2 | Kotlin, Jetpack Compose, Material 3 | current Android standard |
| A3 | minSdk 33, targetSdk 36, compileSdk 37 | owner request; notification permission and per-app locale available natively |
| A4 | No Google Play Services, no proprietary SDKs (ML Kit, Firebase) | FOSS, F-Droid compatible, sideload |
| A5 | Room over SQLite, DB in app-private storage | integrity (WAL), migrations; SQLite cannot live safely on SAF |
| A6 | User data directory via SAF tree (attachments, backups) | externally accessible, survives uninstall |
| A7 | Timing without a running service: state and timestamps in DB, notification chronometer | battery |
| A8 | Reminders via inexact `AlarmManager` (`setWindow` / `setAndAllowWhileIdle`) | no exact-alarm permission, Doze-compatible |
| A9 | Location: `LocationManager` (fused provider from API 31), foreground service of type `location` only while an entry is ACTIVE, 5 min interval, significant-motion trigger | precision with bounded battery cost, no GMS |
| A10 | OSM ecosystem: Nominatim, OSRM, osmdroid/MapLibre; each optional with configurable endpoint | free, self-hostable, disableable |
| A11 | Speech: `SpeechRecognizer`; offline Vosk / whisper.cpp later. OCR: Tesseract | free software |
| A12 | Multi-module Gradle: `:core:domain` (pure JVM), `:core:data`, `:app`, later feature modules | testable business rules on host JVM |
| A13 | Rounding and day attribution only at report/export time | exact data, configurable rules |
| A14 | Exporters compiled in-app behind an interface, no dynamic code loading | security, simplicity |
| A15 | Manual dependency injection (application container) | small app, fewer annotation processors |
| A16 | Daily backup via WorkManager: `VACUUM INTO` snapshot zipped to SAF | consistent snapshot without closing the DB |

## 4. Working conventions

- Chat with the owner in Italian; code, comments, commits and docs in English
  (translate any leftover Italian text in code).
- Indentation: tabs, Linux kernel style where applicable (C/C++/Rust strictly).
- Technical, precise, concise answers; prefer authoritative sources; no
  unrequested deep dives.
- **Never use npm**; prefer official repositories and vendor documentation.
- Verify dependency versions on official release pages before bumping.
- Every source file carries `SPDX-License-Identifier: GPL-3.0-or-later`.
