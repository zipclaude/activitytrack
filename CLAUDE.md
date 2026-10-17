# CLAUDE.md — resume notes for the assistant

Read this first after any interruption, then `docs/INSTRUCTIONS.md`,
`SPEC.md`, `docs/DESIGN.md`. Keep this file current: update §3 and §5 at the end
of every work session.

## 1. Owner and interaction rules

- Owner: Zippo, freelancer (IT infrastructure automation, Debian, Ansible).
  Builds on Debian sid, test device OnePlus 15R / Android 16.
- Language (permanent owner rule):
  - **English**: internal reasoning/thinking; code, comments, commits, docs.
  - **Italian**: interactions with the owner, answers to his direct questions,
    non-technical topics, anything health-related. He writes mostly in Italian.
  - Technical questions and exchanges may be in English (more immediate); he
    welcomes picking up English vocabulary, especially idioms and set phrases.
- Answers: technical, precise, concise, schematic; authoritative sources; no
  unrequested deep dives.
- Indentation: tabs (`.editorconfig`); kernel style for C/C++/Rust.
- **Never use npm** (explicit prohibition), not even to fetch tools.
- Ask before guessing on ambiguous requirements; the owner answers in numbered
  lists matching the numbered questions.

## 2. Build environments

Detect where you run (`hostname`, presence of `~/Android/Sdk`).

### 2.1 Owner's machine (preferred)
- Debian sid, repo in `~/src/activitytrack`, JDK 21, Android SDK (platform 37,
  `ANDROID_HOME=~/Android/Sdk`), adb from SDK platform-tools.
- Build and test locally: `./gradlew :core:domain:test assembleDebug`.
- Device: OnePlus 15R via wireless debugging. Pairing is done once; after each
  re-enable the owner reads the new port and runs `adb connect <ip>:<port>`.
  Then `./gradlew installDebug` and
  `adb shell am start -n it.infraconsulting.activitytrack.debug/it.infraconsulting.activitytrack.MainActivity`.
- Push to `main` only after a green local build; CI must stay green.

### 2.2 Cloud sandbox (claude.ai session)
- Maven Central, `dl.google.com`, `services.gradle.org` and Actions log storage
  are blocked: no local build. Push and read CI; errors are exposed as
  annotations: `gh api repos/zipclaude/activitytrack/check-runs/<job-id>/annotations`.
- Cross-check algorithms with an independent Python script (`python3 -I`).

### 2.3 Always
- Check versions with WebSearch/WebFetch on official pages before changing
  `gradle/libs.versions.toml`.

## 3. Project state

| Milestone | State |
|---|---|
| M0 skeleton, domain rules, Room schema | **done**, CI green; debug APK installed and launched on the OnePlus 15R (2026-10-17) |
| M1 core tracking | **done**, device-tested, merged (PR #1, 2026-10-17) |
| M2 travel & location | **done**, device-tested, merged (PR #2, 2026-10-17) |
| M3 widgets, tile, attachments, dictation | **done**, device-tested, merged (PR #3, 2026-10-17) |
| M4 export and import | next, branch `m4-export-import` |
| M5 external calendar sync (generic / per company; Google or CalDAV) | — |
| M6 consultation: list, day agenda, week, month | — |
| M7 UI polish | — |
| Future (to do) | OCR receipts, free offline STT (SPEC §10) |

Repository: GitHub `zipclaude/activitytrack` (public), branch `main`. Docs are
mirrored in the claude.ai Project: `claude/SPEC.md`, `claude/INSTRUCTIONS.md`,
`claude/DESIGN.md`, `claude/CLAUDE.md`.

## 4. File map

```
SPEC.md                         functional specification (v0.6)
docs/INSTRUCTIONS.md            owner requirements + architectural decisions A1–A16
docs/DESIGN.md                  technical design, toolchain, risks
gradle/libs.versions.toml       verified versions (2026-10-15; androidx.test 2026-10-16)
core/domain/…/model/            Model.kt, EntryStateMachine.kt, EntryInvariants.kt, ExternalRef.kt
core/domain/…/reminder/         ReminderPlanner.kt (confirm, pause + lunch mute, day summary)
core/domain/…/export/           Export.kt (ExportRow, Exporter, ExportRows), CsvExporter.kt
core/domain/…/time/             LocalMinutes.kt (DST-safe LocalDate.atMinute)
core/domain/…/rounding/         Rounding.kt
core/domain/…/report/           DayAttribution.kt, DailyReport.kt
core/domain/…/geo/              Geo.kt (haversine), Tracking.kt (Fix, Movement, Stationarity, KmEstimate)
core/domain/…/calendar/         ItalianHolidays.kt (Easter, 4 Oct from 2026)
core/domain/…/start/            StartDefaults.kt (pre-fill, nearestContaining), ClientPicker.kt, ArrivalAdvice.kt
core/domain/…/settings/         AppSettings.kt (defaults + validated())
core/domain/src/test/…          Rounding, DailyReport, EntryStateMachine, EntryInvariants, StartDefaults,
                                ClientPicker, AppSettings, ReminderPlanner, CsvExporter, LocalMinutes
core/data/…/db/                 Entities.kt, EntryDao.kt, MasterDataDao.kt, ActivityTrackDatabase.kt (lunch seed)
core/data/…/repo/               EntryRepository, MasterDataRepository, ReportRepository, LocationRepository, ValidationException
core/data/…/geo/                GeoClients.kt (Nominatim, Osrm; HttpURLConnection + org.json)
core/data/…/settings/           SettingsRepository (Preferences DataStore)
core/data/…/storage/            UserDataDir (SAF tree, DocumentsContract), DatabaseBackup (VACUUM INTO + zip)
core/data/src/androidTest/…     RepositoryTest (in-memory Room, run on device)
app/…/                          ActivityTrackApp.kt, AppContainer.kt, MainActivity.kt (setup gate + navigator)
app/…/ui/                       Navigation (own back stack, per-entry ViewModel stores), ViewModels, Theme,
                                common/, setup/, home/, start/, entry/, report/ (day, export), settings/, masterdata/
app/…/notify/                   NotificationChannels, EntryNotifications, NotificationSync, ActionReceiver, EntryLinks
app/…/reminders/                ReminderScheduler, ReminderNotifications, ReminderReceiver + BootReceiver
app/…/backup/                   BackupWorker (WorkManager, daily)
app/…/location/                 LocationService (FGS), LocationTracker, TrackingMonitor, TrackingNotifications,
                                GeoEnrichWorker, CurrentPosition
app/…/quick/                    ClientShortcuts, QuickToggle, ToggleTileService, Widgets (Glance 4×1, 1×1)
app/…/ui/entry/                 EntryScreen, Attachments, Expenses (+ QuickExpenseScreen)
app/…/ui/common/                Components (SearchableChoiceField), CommandButtons, MapPicker, Dictation
app/src/debug/                  MockLocationReceiver (adb-driven fused test provider), debug shortcuts.xml
app/src/test/…                  UiParsingTest, TimeInputTest, OngoingActionsTest
app/src/main/res/               strings en/it/de/es, locales_config, data_extraction_rules, adaptive icon
```

## 5. Next steps (M4, branch `m4-export-import`)

M3 merged in `main` on 2026-10-17 (PR #3). Roadmap restructured by the owner
(2026-10-17): M4 export/import, M5 calendar sync, M6 consultation views, M7 UI
polish; OCR and free offline STT moved to future improvements (SPEC §10).

1. Agree the export/import, calendar and consultation details (questions
   asked 2026-10-17).

## 6. Decisions log

| Date | Decision |
|---|---|
| 2026-10-14 | GPL-3.0-or-later; Kotlin/Compose; Room; no GMS; minSdk 33 / target 36 |
| 2026-10-17 | compileSdk 37 (Compose 1.12 AAR metadata requires it); targetSdk stays 36 |
| 2026-10-15 | Client → one company; role per entry; activity free text + presets, default from last 30 d on same client |
| 2026-10-15 | Concurrent entries allowed; pause per entry; travel is an entry mode (outbound, inter-site, return), manual add allowed |
| 2026-10-15 | Rounding: WORK CEIL_TOLERANT q=15 tol q/3; TRAVEL CEIL 5; PAUSE FLOOR 5 min 5; per-export configurable |
| 2026-10-15 | Pause in reports = complement (includes suspensions); daily solar includes travel |
| 2026-10-15 | Day attribution: end ≤ 06:00 next day → start day, else midnight split (06:00 exactly → start day) |
| 2026-10-15 | Online services (Nominatim, OSRM, tiles) optional, endpoints configurable; coordinates never exported |
| 2026-10-15 | Absence kinds: FULL_DAY, LATE_ENTRY, EARLY_EXIT; movement threshold default 100 m |
| 2026-10-15 | Personal APK distribution (no store for now) |
| 2026-10-17 | Final name ActivityTrack, application ID `it.infraconsulting.activitytrack`; public GitHub repo `zipclaude/activitytrack` |
| 2026-10-15 | No "office": company sites (pre-fill company, propose its clients); LocationKind COMPANY/CLIENT/HOME/OTHER |
| 2026-10-15 | Lunch break per weekday (mutes pause reminders); new entry copies company of today's last entry |
| 2026-10-16 | 06:00 carry-over boundary is inclusive (confirmed by owner) |
| 2026-10-16 | Own back stack (Navigation 3 model) instead of the library; one ViewModel per feature; UI stays in `:app` until feature modules pay off |
| 2026-10-16 | Default lunch break Mon–Fri 12:30–14:00 seeded at DB creation; max confirmations default 0 = unlimited; online services enabled by default |
| 2026-10-16 | A client's company cannot change once entries reference it |
| 2026-10-17 | New entry while another is in TRAVEL: "Is the travel completed?" Yes = STOP, No = leave running, "Suspend travel" = SUSPEND; while in WORK: close / suspend / leave; while in PAUSE: close / leave |
| 2026-10-17 | STOP while working asks "Stop now / Start return travel"; STOP from TRAVEL stops directly. Minimal completion dialog (activity, preset, role, notes, client) until step 7 |
| 2026-10-17 | Pause reminders count from the previous pause reminder (lunch postponement cannot repeat); TO_COMPLETE = persistent notification, no periodic nag; day summary at the end of the day's last working interval, none on days without working hours |
| 2026-10-17 | Notification STOP stops at once (no return-travel question); confirm reminder offers "Stop at <last confirmation>" |
| 2026-10-17 | CSV: stable English header, adds work hours, km and expenses on the first day of a split entry, UTF-8 BOM; export range ≤ 1 year |
| 2026-10-17 | Backup restore not implemented in M1 (manual: replace the DB from the zip) |
| 2026-10-17 | Map: MapLibre Native Android (osmdroid archived 2024-11-20), raster tiles from the configured endpoint |
| 2026-10-17 | Location service stays on during PAUSE (movement alerts too) |
| 2026-10-17 | Arrival closes/switches at the start of the stop (first fix of the stationary run); movement "Start travel" backdated to the first fix beyond the threshold |
| 2026-10-17 | "Arrived?" near a client site assigns that client to the travelling entry and switches it to WORK (no new entry) |
| 2026-10-17 | Day-start "Late entry at…" records only the LATE_ENTRY absence; manual days off = list of dates (no ranges yet) |
| 2026-10-17 | Holidays: Italian national + Easter Monday + 4 October from 2026 (L. 151/2025); patron saints as manual days off |
| 2026-10-17 | The location service's notification is the ongoing notification of one ACTIVE entry (no extra notification; not dismissible while tracking) |
| 2026-10-17 | Completion with the client only, after confirmation (leave empty / copy last activity on the client / edit) |
| 2026-10-17 | Arrival at a site of the entry's own client sets location kind CLIENT |
| 2026-10-17 | Locality from Nominatim in the local language (no accept-language), not the phone language (provisional, owner away) |
| 2026-10-17 | Coloured icon buttons in the app; notifications keep text actions, icons attached for Wear/launchers (provisional, owner away) |
| 2026-10-17 | M3: Glance for widgets; widget/shortcut client tap opens the start screen with the client selected (no direct start: no dialogs outside the app) |
| 2026-10-17 | M3: 1×1 widget and QS tile toggle WORK/PAUSE of the most recent ACTIVE entry; none active → start screen (provisional) |
| 2026-10-17 | M3: no expense action on the notification (3-action limit); expenses from app, widget, shortcut |
| 2026-10-17 | M3: dictation through the system recognizer (Google on the OnePlus) accepted; free offline STT stays in M4 |
| 2026-10-17 | M3: voice notes without length limit, explicit stop |
| 2026-10-17 | Dictation language is a setting (it/en/de/es, default Italian), not the phone locale (en-IT is no recognizer language); SpeechRecognizer with in-app dialog, on-device first |
| 2026-10-17 | QS tile added from Settings (StatusBarManager.requestAddTileService): the OxygenOS panel editor does not list it |
| 2026-10-17 | Roadmap: M4 export/import, M5 calendar sync, M6 consultation views, M7 UI polish; OCR and free offline STT to future improvements |

## 7. Pitfalls to remember

- AGP 9 built-in Kotlin: do not apply `org.jetbrains.kotlin.android`; use
  `kotlin.compilerOptions`; kapt incompatible → KSP only.
- Gradle 9: JUnit Platform needs explicit launcher → domain tests use JUnit 4.
- Room stores enums by name: renaming constants requires a migration.
- Exported Room schemas in `core/data/schemas/` must be committed.
- Allow-while-idle alarms are rate-limited by Doze; never schedule below ~10 min.
- Exports must use DTOs without lat/lon.
- `connectedDebugAndroidTest` (AGP 9.4 test engine) reports "failing tests" even
  when all pass: read `core/data/build/reports/androidTests/` or run
  `adb shell am instrument -w it.infraconsulting.activitytrack.core.data.test/androidx.test.runner.AndroidJUnitRunner`.
- JUnit 4 test methods must return Unit: use `runBlocking<Unit> { }`.
- Companion maps over sealed `data object`s must be lazy (class-init cycle → nulls).
- Wireless adb: the phone may be dozing; screenshots come out black. The port
  changes at every re-enable (ask the owner).
- ViewModels must live in the back-stack entry store (`ScopedEntry`), not the
  Activity: otherwise a reopened screen shows stale state.
- Device UI tests that create data: back up `databases/` with `run-as` first and
  restore afterwards (entries cannot be deleted from the UI yet).
- Local wall-clock times: build `LocalDate.atTime(...)` / `atMinute`, never
  `atStartOfDay().plusMinutes()` (wrong on DST days).
- Regexes run on ICU on Android: escape both braces (`\\{`, `\\}`); a bare `}`
  passes JVM unit tests and crashes on device.
- API 34+: users can dismiss `setOngoing` notifications; `MainActivity.onStart`
  re-posts them (owner's choice, no foreground service).
- Never tap the notification shade by coordinates: OxygenOS layout shifts and a
  miss hits "clear all" (owner's notifications lost, 2026-10-17). `uiautomator`
  cannot read the shade; ask the owner to tap notification actions.
- WorkManager ignores `cmd jobscheduler run -f` before schedule; use Settings →
  "Back up now" (`BackupWorker.runNow`).
- Coordinates in URLs: format with `Locale.ROOT` (Italian locale writes `45,4`).
- A location FGS started from the background without "Allow all the time"
  throws `SecurityException`: `LocationService.update` catches it and stops.
- Notification `NotificationManager.cancel` is ignored for the FGS notification:
  switch with `startForeground(newId)` first, then cancel the old id.
- The worktree guard rejects complex shell lines that mention git or run
  interpreters on inline text: write scripts to `$CLAUDE_JOB_DIR/tmp` and run them.

## 8. Commit format

End commit messages with:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
plus the session link line provided by the environment.
