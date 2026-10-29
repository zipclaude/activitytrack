# ActivityTrack — technical design

Status: covers M0 (implemented) and the planned design for M1–M4.
Last update: 2026-10-15. Requirements: [INSTRUCTIONS.md](INSTRUCTIONS.md), [SPEC.md](../SPEC.md).

## 1. Toolchain

| Component | Version | Notes |
|---|---|---|
| Gradle (wrapper) | 9.6.0 | JUnit Platform launcher no longer auto-added → tests use JUnit 4 via `kotlin-test-junit` |
| Android Gradle Plugin | 9.4.0 | built-in Kotlin: **no** `org.jetbrains.kotlin.android` plugin; `kotlin.compilerOptions {}` instead of `kotlinOptions` |
| Kotlin | 2.4.21 | `org.jetbrains.kotlin.jvm` only for `:core:domain`; Compose compiler plugin = Kotlin version |
| KSP | 2.3.11 | Room code generation |
| Compose BOM | 2026.09.00 | Material 3 |
| Room | 2.8.5 | Room Gradle plugin, schemas in `core/data/schemas/` (commit them) |
| WorkManager | 2.12.0 | |
| DataStore | 1.2.1 | settings |
| androidx.test runner / ext-junit | 1.7.0 / 1.3.0 | instrumented repository tests |
| AndroidX core / activity / lifecycle | 1.19.1 / 1.13.0 / 2.11.0 | |
| JDK | 17+ (bytecode target 17) | |
| Android SDK | compile 37, target 36, min 33 | compile 37 required by Compose 1.12 AAR metadata |

Versions live in `gradle/libs.versions.toml`.

## 2. Module structure

```
:app                     application, Compose UI, notifications, receivers, services
:core:domain             pure Kotlin/JVM: models, state machine, rounding, attribution,
                         totals, tracking rules, interchange model and generic connector,
                         calendar events
:core:data               Room DB, DAOs, repositories, importer/exporter, geo clients,
                         settings (DataStore), SAF storage, backup
```

Packages of `:app`: `location` (service, tracker, monitor, geo worker),
`reminders`, `notify`, `backup`, `calendar` (CalendarSync), `quick` (tile,
widgets, shortcuts), `connect` (SecretStore), `ui/*` (one package per area:
home, start, entry, agenda, report, todo, masterdata, settings, setup,
common). Feature modules stay planned until the UI outgrows `:app`.

Dependency direction: `app → feature/service/export → core:data → core:domain`.
`:core:domain` has no Android dependency and is unit-tested on the host JVM.

Package root: `it.infraconsulting.activitytrack`.

### 2.1 Layers

- **UI**: Compose screens; one `ViewModel` per feature (setup, home, settings,
  master data) exposing `StateFlow`s, built from `AppContainer` via
  `appViewModel {}`. Navigation: an app-owned back stack (`ui/Navigation.kt`,
  saved as string keys), the Navigation 3 model without the dependency while
  the graph is small. UI lives in `:app` until feature modules pay off.
- **Domain**: pure functions and use cases (`StartEntry`, `ApplyCommand`,
  `BuildDailyReport`, `ProposeClient`, …).
- **Data**: repositories wrapping DAOs; every multi-row change in one Room
  transaction.
- **DI**: manual `AppContainer` owned by `ActivityTrackApp` (decision A15).

## 3. Time model

- Instants stored as UTC epoch milliseconds; each entry and segment also stores
  the IANA `zone_id` active when it was recorded.
- `java.time` (`Instant`, `ZonedDateTime`, `Duration`) everywhere; no
  `java.util.Date`.
- Local times of day (schedules, lunch breaks, absences) as minutes since
  midnight; dates as epoch days.
- Open segments have `end_utc_ms = NULL`; computations resolve them with `now`.

## 4. Data model (Room, DB version 3)

Source: `core/data/src/main/kotlin/it/infraconsulting/activitytrack/data/db/Entities.kt`.

| Table | Key columns | Notes |
|---|---|---|
| `company` | id, name (unique), archived | |
| `company_site` | id, company_id → company (CASCADE), label, lat, lon, radius_m=100, address | company pre-fill |
| `client` | id, company_id → company (RESTRICT), name, archived | unique (company_id, name) |
| `client_site` | id, client_id → client (CASCADE), label, lat, lon, radius_m=100, address | client proposal |
| `place` | id, kind HOME/OTHER, label, lat, lon, radius_m | location kind HOME |
| `role` | id, name (unique), archived | |
| `activity_preset` | id, text, default_role_id?, company_id? | |
| `time_entry` | id, company_id?, client_id?, role_id?, activity?, status, mode?, locality?, location_kind?, notes?, zone_id, last_confirmed_utc_ms?, unanswered_reminders, created/updated | company known before client is allowed; client null only before the client is chosen |
| `segment` | id, entry_id → time_entry (CASCADE), kind WORK/TRAVEL/PAUSE, start_utc_ms, end_utc_ms?, zone_id, source TRACKED/MANUAL, km_estimated?, km_declared? | |
| `location_sample` | id, entry_id, segment_id?, ts_utc_ms, lat, lon, accuracy_m, provider | never exported |
| `attachment` | id, entry_id, kind PHOTO/AUDIO/RECEIPT/FILE, rel_path, mime, transcript?, created | path relative to SAF root |
| `expense` | id, entry_id, segment_id?, amount_cents, description, attachment_id?, created | EUR |
| `external_ref` | id, entry_id, kind TICKET/URL/UUID/OTHER, value | kind auto-classified |
| `absence` | id, date_epoch_day, kind FULL_DAY/LATE_ENTRY/EARLY_EXIT, time_min? | |
| `work_schedule` | id, day_of_week 1–7, start_min, end_min | n intervals/day |
| `lunch_break` | id, day_of_week (unique), start_min, end_min | pause-reminder mute |

Invariants enforced in repositories (not expressible in Room annotations):
1. at most one open segment per entry;
2. `status = ACTIVE ⇔ mode ≠ NULL ⇔` an open segment of kind `mode` exists;
3. segments of the same entry never overlap;
4. `updated_utc_ms` bumped on every entry change;
5. `client_id ≠ NULL ⇒ company_id = client.company_id`.

Enums are stored by name (Room built-in converter): never rename enum constants
without a migration.

Version history: v1 (M0) as above; v2 (M2) `day_off(date_epoch_day, note)`;
v3 (M4, auto-migration with `FillEntryUids`) `todo(uid, company_id,
client_id?, external_key?, title, notes?, refs, due_epoch_day?, due_time_min?,
priority?, done)`, `time_entry.uid` (unique) and `time_entry.todo_id`,
`company.external_code / connector_id / connector_config`,
`client.external_code`. v16 (issue #111, manual `MIGRATION_15_16`): a
unique `uid` (canonical UUID) on every synced table, existing entry uids
canonicalised (schema 3 wrote 32 hex digits), day off and plugin list item
uids name-based (`SyncIds`); `sync_change` (change log written by SQLite
triggers while `sync_state.enabled` and not `applying`), `sync_shadow`,
`sync_state`; contact phones and entry tags log their parent record; a
`keep_uid` trigger keeps a row's identity across updates (`SyncSchema`).
Records travel as wire fields (issue #112): `SyncMapping` (core:domain) maps
each record type to its table and each field to its column and kind
(references as uids, epoch days as dates, JSON text as JSON, contact phones
and entry tags as lists); `SyncRecords` (core:data) reads and writes records
by those fields. `SyncEngine` (issue #114) runs a sync: the first time a
full pull with adoption of central master data (a personal company,
client or role of the same natural key takes the central uid), then every
local record logged and pushed; afterwards push (changes collapsed per
record, only the fields that differ from the shadow) and pull by cursor,
fields still pending locally kept. `SyncStore` holds the database side,
`SyncWorker` schedules it (15 min, a few seconds after a change, at once
in front, `sync/head` every 30 s while the app is open).
`ActivityTrackDatabase.VERSION` is the single source.

## 5. Domain logic (`:core:domain`, implemented in M0)

| Component | File | Content |
|---|---|---|
| Models | `model/Model.kt` | `Interval`, `Segment`, `EntryTimeline`, enums |
| State machine | `model/EntryStateMachine.kt` | `start(mode)`, `apply(state, cmd) → Transition(next, closeOpenSegment, openSegment)` |
| External refs | `model/ExternalRef.kt` | regex classifier URL / UUID / TICKET / OTHER |
| Rounding | `rounding/Rounding.kt` | `RoundingRule(mode, quantum, minimum)`, `ReportRounding.DEFAULT` |
| Day attribution | `report/DayAttribution.kt` | carry-over until 06:00 (inclusive), else midnight split |
| Daily report | `report/DailyReport.kt` | per entry/day lines, pause complement, solar union, reported sum |
| Geo | `geo/Geo.kt` | `GeoPoint`, haversine distance (mean radius 6 371 008.8 m) |
| Start pre-fill | `start/StartDefaults.kt` | site match → company/client, else company of today's last entry |

Rounding works on nanoseconds with integer arithmetic; CEIL_TOLERANT condition
`3·r < q` makes the tolerance exactly q/3 for any quantum.

Algorithms to add:
- **Client picker order** (after pre-fill): clients of the proposed company,
  most recently used first.
- **Default activity**: `EntryDao.lastOnClient(clientId, now − 30 d)`.
- **Movement detection** (WORK): anchor = first good fix of the current WORK
  segment; alert when `distance − accuracy > threshold` on 2 consecutive fixes.
- **Stationarity** (TRAVEL): all fixes in the last 15 min within 100 m of their
  centroid.
- **Km estimate**: OSRM route distance between consecutive fixes, fallback
  Σ haversine × factor (default 1.25, configurable).

## 6. Background execution

### 6.1 Reminders (M1)
- One `ReminderScheduler` computes the earliest due time among: entry
  confirmations (per ACTIVE WORK/TRAVEL entry: `last_confirmed + interval`),
  pause reminders (15 min, skipping the lunch break), day start (+15 min), end of
  day summary. Implemented as the pure `ReminderPlanner` (domain): confirm at
  `last_confirmed + interval * (unanswered + 1)` up to the maximum; pause at
  `last_confirmed + interval`, where each pause reminder moves `last_confirmed`
  (a time inside the lunch break is postponed to its end); day summary at the
  end of the last working interval, once per day. TO_COMPLETE entries keep a
  persistent notification instead of periodic nags.
- It arms a **single** alarm with `AlarmManager.setWindow(RTC_WAKEUP, t, 2 min)`;
  `setAndAllowWhileIdle` when the device may be in Doze. No
  `SCHEDULE_EXACT_ALARM`. Allow-while-idle alarms are rate-limited by the system
  (order of minutes), compatible with 15/30 min intervals.
- `ReminderReceiver` (BroadcastReceiver, `goAsync()`): posts due notifications,
  updates `unanswered_reminders`, re-arms.
- Re-arm on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`,
  `TIMEZONE_CHANGED`, and after every state change.
- Notification actions → `ActionReceiver` → repository command (no Activity
  start).

### 6.2 Location service (M2)
- `LocationService`: `foregroundServiceType="location"`, started on entry start
  or resume, stopped when no entry is ACTIVE (optionally paused during PAUSE).
- Its notification is the ongoing notification of one ACTIVE entry
  (`startForeground` with that id), switched when that entry leaves ACTIVE;
  `NotificationSync` drives the running instance in process.
- Fixes: one sample per ACTIVE entry tagged with its open segment; TRAVEL
  start/stop fixes are high accuracy and tagged with the TRAVEL segment.
- `TrackingMonitor` evaluates after every fix: departure (2 consecutive away
  fixes, time of the first) during WORK, stop (stationary run, time of its
  first fix) during TRAVEL; commands are backdated to those times.
- Provider: `LocationManager.FUSED_PROVIDER` when present, else GPS + network.
  `LocationRequestCompat` interval 5 min, quality balanced; temporary
  high-accuracy request for start/stop fixes of TRAVEL.
- `TYPE_SIGNIFICANT_MOTION` (`TriggerEventListener`, one-shot, re-armed) asks
  for an immediate fix.
- Permissions: `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (sideload
  allows it; needed when the service is started from a notification or widget
  while the app is not visible), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_LOCATION`.
- OEM battery management (OxygenOS): guided request for
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` plus a settings checklist.

### 6.3 Periodic work
- `BackupWorker` (WorkManager, 24 h, constraint battery-not-low).
- `GeoEnrichWorker` (network constraint): reverse geocoding and OSRM km for
  samples recorded offline.

## 7. Notifications

| Channel | Importance | Content |
|---|---|---|
| `ongoing` | LOW | one per ACTIVE entry: client, mode, chronometer; actions Pause/Resume, Travel/Work, Stop, Expense |
| `confirm` | DEFAULT | "Still on <client>?" — Yes / Pause / Stop at last confirmation |
| `pause` | DEFAULT | "Still on pause?" — Resume / Stop |
| `movement` | HIGH, sound | moved > X m — Travel from <departure> / Ignore / Stop at <departure> |
| `arrival` | DEFAULT | stopped while travelling — work at <client> / stop at home / work from <arrival>; still travelling |
| `day_start` | DEFAULT | Absence / Late entry / Start entry |
| `day_summary` | LOW | solar vs reported totals |
| `to_complete` | DEFAULT | open the completion form |

Notification ids derived from entry id and channel (`entryId * 16 + channelIndex`).

## 8. Storage

### 8.1 User data directory (SAF)
- Chosen at first run with `ACTION_OPEN_DOCUMENT_TREE`; permission persisted via
  `takePersistableUriPermission`.
- Layout:
  ```
  <root>/attachments/<yyyy>/<entryId>/<uuid>.<ext>
  <root>/backup/activitytrack-<yyyyMMdd-HHmm>.zip
  <root>/export/…
  ```
- Captures (camera, audio) are written to app cache through `FileProvider`, then
  copied into the tree; DB stores the relative path.

### 8.2 Backup and restore
- Snapshot: `VACUUM INTO '<cache>/snapshot.db'`, zipped with `manifest.json`
  (app version, DB version, timestamp, attachment count); retention N (default 14).
- Restore (not in M1): close Room, replace the DB file from the zip, run migrations on open.
- `android:allowBackup="false"`: Android cloud backup disabled, the SAF backup
  is the only one.

### 8.3 Settings (DataStore)
`AppSettings` (domain, `validated()` clamps ranges) persisted by
`SettingsRepository`. Lunch breaks are a table (default Mon–Fri 12:30–14:00
seeded at DB creation). Keys: confirmation interval, max confirmations, pause interval, movement threshold,
stationarity radius/time, day carry-over time, rounding profile, online service
toggles and endpoints, km factor, backup retention, SAF root URI.

## 9. Media and input (M3–M4)

| Function | Implementation | License |
|---|---|---|
| Photo / receipt | `ActivityResultContracts.TakePicture` (system camera) | — |
| Voice note | `MediaRecorder`, AAC in MPEG-4 (`.m4a`) | — |
| Dictation (M3) | `SpeechRecognizer`: on-device first, default service as fallback, in-app dialog; language from settings | — |
| Offline STT (future) | Vosk (it/en/de/es models) or whisper.cpp | Apache-2.0 / MIT |
| OCR receipts (future) | tesseract4android, `traineddata` in app files | Apache-2.0 |
| Map | MapLibre Native Android (osmdroid archived) | BSD-2 |
| Geocoding | Nominatim HTTP API, User-Agent set, ≤ 1 req/s, cache by rounded coordinates | ODbL data |
| Routing | OSRM `/route/v1/driving` | BSD-2 |

HTTP via `HttpURLConnection` + kotlinx coroutines (no extra client library).

## 10. Quick interaction (M3)

- Glance widgets (`quick/Widgets.kt`): 4×1 running entry with coloured
  commands (ActionCallback → repository) and €, or recent clients (open the
  start screen); 1×1 toggle. Refreshed by `NotificationSync`.
- `ToggleTileService`: active tile, WORK/PAUSE of the most recent ACTIVE
  entry, start screen otherwise; added via `StatusBarManager.requestAddTileService`.
- `ClientShortcuts`: 4 dynamic shortcuts; static "Expense" shortcut
  (`res/xml/shortcuts.xml`, debug variant with the `.debug` package).
- Quick expense screen for the most recent open entry.

## 11. Export

```kotlin
interface Exporter {
	val id: String
	val mimeType: String
	fun export(rows: List<ExportRow>, out: OutputStream)
}
```

`ExportRow` is built from `DailyReport` lines and **excludes coordinates**.
Default CSV profile: UTF-8, `;` separator, decimal comma for Italian locale.
Header (stable, English): start_date, start_time, end_date, end_time,
work_hours, pause_hours, travel_hours, company, client, role, activity, km,
expenses_eur, notes, external_refs, locality. UTF-8 with BOM, CRLF, RFC 4180
quoting; km and expenses on the first day of an entry split at midnight.
Original column list: start date, start time, end date, end time, pause hours, travel hours,
company, client, role, activity, km, expenses total, notes, external refs,
locality. Rounding and column set configurable per export profile.

### 11.1 Interchange and connectors (M4)

`domain/interchange`: `Interchange` (kotlinx.serialization), `Connector`
(read/write files, pull/push remote, setting fields, secrets),
`GenericConnector` (JSON; XML derived mechanically from the JSON tree with
DOM, DTDs refused). `data/interchange`: `Importer` (one transaction, matching
by external code → name / uid, app data wins, report of conflicts and
skips), `Exporter` (sections, period, company, client; site coordinates on
request). Format: docs/INTERCHANGE.md; samples in docs/samples.

### 11.2 Calendar sync (M5)

`domain/calendar/CalendarEvents` turns report lines into events;
`calendar/CalendarSync` writes them through `CalendarContract` with
`CUSTOM_APP_PACKAGE` / `CUSTOM_APP_URI = activitytrack://entry/<uid>/<day>`,
so events are found again without a local table and others are never
touched. Re-sync of 14 days on every change (debounced), 90 days on demand.
Calendar ids are device-local settings (`calendarId`, `companyCalendars`).

### 11.3 Consultation (M6)

`ui/agenda`: `AgendaViewModel` loads the report lines of the visible period
(list: month, week, day, month grid: whole weeks) plus open to-dos due;
`Timeline` assigns concurrent entries to columns greedily; colours by
company. Bottom navigation in `MainActivity` (`TABS`, `LocalBottomBar` for
insets).

## 12. Internationalisation

Default resources in English; `values-it`, `values-de`, `values-es`.
`res/xml/locales_config.xml` enables per-app language selection (API 33+).

## 13. Testing

| Level | Tooling | Scope |
|---|---|---|
| Domain | JUnit 4 + kotlin-test on host JVM | rounding, attribution, totals, state machine, geo math |
| Data | Room in-memory DB, instrumented (`RepositoryTest`, on device) | repository validation and invariants; migrations (`room-testing`) later |
| App | JUnit 4 on host JVM | input parsing, back-stack keys |
| UI | Compose UI tests, instrumented | critical flows (start, stop, complete) |

M0 test vectors were cross-checked with an independent Python implementation.

## 14. Risks and open points

| Item | Status |
|---|---|
| M0 never compiled (first environment without Maven/Google access) | owner to run `./gradlew :core:domain:test assembleDebug` |
| KSP 2.3.11 with AGP 9.4 built-in Kotlin | to verify at first build |
| FGS location started from notification/widget while app in background | verify on Android 16; background location granted |
| OxygenOS background restrictions | test reminders in Doze and with screen off |
| Entry ending exactly at 06:00 attributed to start day | confirmed by owner 2026-10-16 |
| Site coordinates typed by hand | solved in M2 (current position, MapLibre picker) |
| Inexact reminder alarm in Doze | setWindow when awake, setAndAllowWhileIdle when idle at scheduling; a device entering Doze later may delay a reminder to the next maintenance window; verify on OxygenOS |
| M1 steps 5–8 | implemented, host-tested; device test pending |
| CI | GitHub Actions: domain + app unit tests, instrumented tests compiled (not run), `assembleDebug`, APK artifact |
| `connectedDebugAndroidTest` exit status | AGP 9.4 test engine fails the task with all tests green; verify via report or `am instrument` |
