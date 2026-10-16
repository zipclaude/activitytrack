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

## 2. Environment limits (sandbox)

- GitHub CI (`.github/workflows/ci.yml`) builds and tests on every push: use
  its results (`gh run list`, `gh run view --log-failed`) as the compiler.
- Outbound network: only PyPI, crates.io, Go proxy, npm (forbidden). Maven
  Central, `dl.google.com`, `services.gradle.org`, GitHub downloads are
  **blocked** → the project cannot be compiled or tested here.
- Local Gradle 8.14.3 available offline (used only to generate the wrapper).
  JDK 21 present. No Android SDK, no `kotlinc`.
- Verification strategy: write Kotlin code carefully, cross-check algorithms
  with an independent Python script (`python3 -I`), ask the owner to run
  `./gradlew :core:domain:test assembleDebug` and paste errors.
- Check versions with WebSearch/WebFetch on official pages before changing
  `gradle/libs.versions.toml`.

## 3. Project state

| Milestone | State |
|---|---|
| M0 skeleton, domain rules, Room schema | **done** (see `git log`), not yet built by owner |
| M1 core tracking | next |
| M2 travel & location | — |
| M3 widgets, tile, attachments, dictation | — |
| M4 OCR, offline STT, XML/JSON export | — |

Repository: GitHub `zipclaude/activitytrack` (public), branch `main`. Docs are
mirrored in the claude.ai Project: `claude/SPEC.md`, `claude/INSTRUCTIONS.md`,
`claude/DESIGN.md`, `claude/CLAUDE.md`.

## 4. File map

```
SPEC.md                         functional specification (v0.4)
docs/INSTRUCTIONS.md            owner requirements + architectural decisions A1–A16
docs/DESIGN.md                  technical design, toolchain, risks
gradle/libs.versions.toml       verified versions (2026-10-15)
core/domain/…/model/            Model.kt, EntryStateMachine.kt, ExternalRef.kt
core/domain/…/rounding/         Rounding.kt
core/domain/…/report/           DayAttribution.kt, DailyReport.kt
core/domain/…/geo/              Geo.kt (haversine)
core/domain/…/start/            StartDefaults.kt (pre-fill rules)
core/domain/src/test/…          RoundingTest, DailyReportTest, EntryStateMachineTest, StartDefaultsTest
core/data/…/db/                 Entities.kt, EntryDao.kt, ActivityTrackDatabase.kt
app/…/                          ActivityTrackApp.kt, MainActivity.kt (placeholder), notify/NotificationChannels.kt
app/src/main/res/               strings en/it/de/es, locales_config, adaptive icon
```

## 5. Next steps (M1)

1. Get owner's build result for M0; fix compile errors first.
2. `AppContainer` (manual DI), repositories with invariants (DESIGN §4).
3. Settings in DataStore; SAF root selection on first run.
4. Master data screens: companies, clients, sites, roles, presets, places,
   company sites, work schedule, lunch breaks.
5. Home: open entries list, start flow (`StartDefaults` pre-fill, client picker
   filtered by company, activity defaults from last 30 d,
   concurrent-entry dialog), commands via state machine.
6. Ongoing notification with actions; `ActionReceiver`.
7. `ReminderScheduler` + `ReminderReceiver` (confirm, pause, to-complete, day
   summary); boot/time-change re-arm.
8. Completion form; manual TRAVEL/PAUSE segment insertion; external refs.
9. Day view (solar vs reported), CSV exporter, daily backup worker.

## 6. Decisions log

| Date | Decision |
|---|---|
| 2026-10-14 | GPL-3.0-or-later; Kotlin/Compose; Room; no GMS; minSdk 33 / target 36 |
| 2026-10-17 | compileSdk 37 (Compose 1.12 AAR metadata requires it); targetSdk stays 36 |
| 2026-10-15 | Client → one company; role per entry; activity free text + presets, default from last 30 d on same client |
| 2026-10-15 | Concurrent entries allowed; pause per entry; travel is an entry mode (outbound, inter-site, return), manual add allowed |
| 2026-10-15 | Rounding: WORK CEIL_TOLERANT q=15 tol q/3; TRAVEL CEIL 5; PAUSE FLOOR 5 min 5; per-export configurable |
| 2026-10-15 | Pause in reports = complement (includes suspensions); daily solar includes travel |
| 2026-10-15 | Day attribution: end ≤ 06:00 next day → start day, else midnight split (06:00 exactly → start day, to confirm) |
| 2026-10-15 | Online services (Nominatim, OSRM, tiles) optional, endpoints configurable; coordinates never exported |
| 2026-10-15 | Absence kinds: FULL_DAY, LATE_ENTRY, EARLY_EXIT; movement threshold default 100 m |
| 2026-10-15 | Personal APK distribution (no store for now) |
| 2026-10-17 | Final name ActivityTrack, application ID `it.infraconsulting.activitytrack`; public GitHub repo `zipclaude/activitytrack` |
| 2026-10-15 | No "office": company sites (pre-fill company, propose its clients); LocationKind COMPANY/CLIENT/HOME/OTHER |
| 2026-10-15 | Lunch break per weekday (mutes pause reminders); new entry copies company of today's last entry |

## 7. Pitfalls to remember

- AGP 9 built-in Kotlin: do not apply `org.jetbrains.kotlin.android`; use
  `kotlin.compilerOptions`; kapt incompatible → KSP only.
- Gradle 9: JUnit Platform needs explicit launcher → domain tests use JUnit 4.
- Raw Actions job logs are not downloadable here (blob storage blocked); CI
  emits Gradle/compiler errors as annotations: `gh api
  repos/zipclaude/activitytrack/check-runs/<job-id>/annotations`.
- Room stores enums by name: renaming constants requires a migration.
- Exported Room schemas in `core/data/schemas/` must be committed.
- Allow-while-idle alarms are rate-limited by Doze; never schedule below ~10 min.
- Exports must use DTOs without lat/lon.

## 8. Commit format

End commit messages with:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
plus the session link line provided by the environment.
