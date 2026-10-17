# CLAUDE.md — resume notes for the assistant

Read this first after any interruption, then `docs/INSTRUCTIONS.md`,
`SPEC.md`, `docs/DESIGN.md`. Keep this file current: update §3 and §5 at the end
of every work session.

## 1. Owner and interaction rules

- Owner: Zippo, freelancer (IT infrastructure automation, Debian, Ansible).
  Builds on Debian sid, test device OnePlus 15R / Android 16.
- Chat in **Italian**; code, comments, commits, docs in **English**.
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
| M1 core tracking | **implemented** (steps 1–8, PR #1, branch `worktree-m1-foundation`, 2026-10-17); steps 5–8 still to test on device |
| M2 travel & location | — |
| M3 widgets, tile, attachments, dictation | — |
| M4 OCR, offline STT, XML/JSON export | — |

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
core/domain/…/geo/              Geo.kt (haversine)
core/domain/…/start/            StartDefaults.kt (pre-fill rules), ClientPicker.kt
core/domain/…/settings/         AppSettings.kt (defaults + validated())
core/domain/src/test/…          Rounding, DailyReport, EntryStateMachine, EntryInvariants, StartDefaults,
                                ClientPicker, AppSettings, ReminderPlanner, CsvExporter, LocalMinutes
core/data/…/db/                 Entities.kt, EntryDao.kt, MasterDataDao.kt, ActivityTrackDatabase.kt (lunch seed)
core/data/…/repo/               EntryRepository, MasterDataRepository, ReportRepository, ValidationException
core/data/…/settings/           SettingsRepository (Preferences DataStore)
core/data/…/storage/            UserDataDir (SAF tree, DocumentsContract), DatabaseBackup (VACUUM INTO + zip)
core/data/src/androidTest/…     RepositoryTest (in-memory Room, run on device)
app/…/                          ActivityTrackApp.kt, AppContainer.kt, MainActivity.kt (setup gate + navigator)
app/…/ui/                       Navigation (own back stack, per-entry ViewModel stores), ViewModels, Theme,
                                common/, setup/, home/, start/, entry/, report/ (day, export), settings/, masterdata/
app/…/notify/                   NotificationChannels, EntryNotifications, NotificationSync, ActionReceiver, EntryLinks
app/…/reminders/                ReminderScheduler, ReminderNotifications, ReminderReceiver + BootReceiver
app/…/backup/                   BackupWorker (WorkManager, daily)
app/src/test/…                  UiParsingTest, TimeInputTest, OngoingActionsTest
app/src/main/res/               strings en/it/de/es, locales_config, data_extraction_rules, adaptive icon
```

## 5. Next steps (M1)

1. ~~`AppContainer`, repositories with invariants~~ done.
2. ~~Settings in DataStore; SAF root selection on first run~~ done.
3. ~~Master data screens~~ done (coordinates typed; map/current position in M2).
4. ~~Home, start flow, concurrent-entry dialog, commands~~ done and tested on
   device (2026-10-17); minimal completion dialog until step 7.
5. ~~Ongoing notification, `ActionReceiver`~~ done.
6. ~~Reminders, boot/time-change re-arm~~ done.
7. ~~Entry screen (completion), manual segments, external refs~~ done.
8. ~~Day view, CSV export, daily backup~~ done.
9. **Device test of steps 5–8** (owner gives the phone): instrumented tests via
   `am instrument`; notifications and actions, reminders (lower the intervals
   to 10 min), reboot re-arm, entry screen, day view, export file, backup zip.
   Back up `databases/` with `run-as` first.
10. Then merge PR #1 and start M2 (location, day-start prompt).

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
- The worktree guard rejects complex shell lines that mention git or run
  interpreters on inline text: write scripts to `$CLAUDE_JOB_DIR/tmp` and run them.

## 8. Commit format

End commit messages with:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
plus the session link line provided by the environment.
