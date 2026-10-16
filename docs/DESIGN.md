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
:core:domain             pure Kotlin/JVM: models, state machine, rounding, attribution, totals
:core:data               Room DB, DAOs, repositories, settings (DataStore), SAF storage
planned
:feature:tracking        home, start/stop flows, entry editor, day view       (M1)
:feature:masterdata      companies, clients, sites, roles, presets, places    (M1)
:feature:report          reports and export UI                                (M1)
:service:reminders       alarm scheduler, notification builders, receivers    (M1)
:service:location        foreground service, location pipeline, geo clients   (M2)
:export:csv  :export:json  :export:xml                                        (M1 / M4)
:widget                  Glance widgets, tile, shortcuts                      (M3)
```

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

## 4. Data model (Room, DB version 1)

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
  day summary, TO_COMPLETE nags.
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
| `movement` | HIGH, sound | moved > X m — Start travel / Ignore / Stop |
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
- Restore: close Room, replace the DB file from the zip, run migrations on open.
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
| Dictation | `SpeechRecognizer.createOnDeviceSpeechRecognizer` if available, else default recognizer | — |
| Offline STT | Vosk (it/en/de/es models) or whisper.cpp | Apache-2.0 / MIT |
| OCR receipts | tesseract4android, `traineddata` in app files | Apache-2.0 |
| Map | osmdroid or MapLibre Native | Apache-2.0 / BSD-2 |
| Geocoding | Nominatim HTTP API, User-Agent set, ≤ 1 req/s, cache by rounded coordinates | ODbL data |
| Routing | OSRM `/route/v1/driving` | BSD-2 |

HTTP via `HttpURLConnection` + kotlinx coroutines (no extra client library).

## 10. Quick interaction (M3)

- Glance widgets: 4×1 (state, chronometer, recent clients, actions), 1×1 toggle.
- `TileService`: toggle WORK/PAUSE of the most recent active entry; long press
  opens the app.
- Dynamic shortcuts: top 4 clients by recent use.
- Quick expense: translucent activity (amount keypad, description, camera).

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
Columns: start date, start time, end date, end time, pause hours, travel hours,
company, client, role, activity, km, expenses total, notes, external refs,
locality. Rounding and column set configurable per export profile.

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
| M0 never compiled (sandbox without Maven/Google access) | owner to run `./gradlew :core:domain:test assembleDebug` |
| KSP 2.3.11 with AGP 9.4 built-in Kotlin | to verify at first build |
| FGS location started from notification/widget while app in background | verify on Android 16; background location granted |
| OxygenOS background restrictions | test reminders in Doze and with screen off |
| Entry ending exactly at 06:00 attributed to start day | confirmed by owner 2026-10-16 |
| Site coordinates typed by hand | until M2 (current position, map picker) |
| CI | GitHub Actions: domain + app unit tests, instrumented tests compiled (not run), `assembleDebug`, APK artifact |
| `connectedDebugAndroidTest` exit status | AGP 9.4 test engine fails the task with all tests green; verify via report or `am instrument` |
