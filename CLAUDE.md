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
- Two apps on the phone (owner, 2026-10-18): **ActivityTrackDbg** = debug build
  installed with adb for tests (`it.infraconsulting.activitytrack.debug`, data
  folder `ActivityTrackDbg`); **ActivityTrack** = the app in use,
  release from GitHub Releases (`it.infraconsulting.activitytrack`, data folder
  `ActivityTrack`). Device tests touch only the debug app.
- Release: tag `vX.Y.Z` on `main` and push the tag → CI job `release` signs the
  APK with the repository secrets `RELEASE_*` and publishes the GitHub Release
  (versionCode = CI run number). Keystore and passwords:
  `~/src/activitytrack-keys/` (never in the repo; losing it means no more
  updates without reinstalling). Local signed build: `./gradlew :app:assembleRelease`.

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
| M4 export and import | **implemented** (PR #4, on `main`), device-tested 2026-10-18 (import, idempotence, start from to-do, done prompt, JSON export) |
| M5 external calendar sync (generic / per company; Google or CalDAV) | **implemented** (PR #5 → #10, on `main`), device-tested 2026-10-18 on the local calendar |
| M6 consultation: list, day agenda, week, month | **implemented** (PR #6 → #10, on `main`), device-tested 2026-10-18 |
| M7 UI polish | first pass (PR #7 → #10, on `main`), owner review pending |
| M8 hardening | review fixes, backup restore, manual (PR #8 → #10, on `main`), restore device-tested 2026-10-18; manual read pending |
| M9 planned to-dos | **implemented** (PR #9 → #10, on `main`), device-tested 2026-10-18 (reminder tested by the owner) |
| M14 fields | **implemented** (branch `m14-fields`): field importance borders, drop-down colours (backlog 14), 24-hour to-dos, ignored filter, recent completed entries on Home; device test pending |
| M17 editable times | **implemented** (PR #20, on top of M16 PR #18): setting (default on), start/end time when starting/stopping, segments editable only when on; device-tested 2026-10-19 |
| M16 list order | **implemented** (PR #18, branch `m16-list-order`, on top of M15 PR #17): backlog 1 (schema v8); device test pending |
| M15 tags, segments | **implemented** (PR #17, branch `m15-tags-segments`, on top of M14 PR #16): backlog 11 and 13 (schema v7); device test pending |
| M13 backlog | **done** (PR #15, on `main`, v0.4.2): backlog items 2, 4, 5, 6, 7, 8, 9, 10, 12; device-tested 2026-10-18 |
| M12 colours | **done** (PR #14, on `main`, v0.4.1): company/client colours, card bands, to-do company filter, agenda colours (schema v6); device-tested 2026-10-18 |
| M11 UI | **done** (PR #13, on `main`, release v0.4.0): start/travel on the empty card and the bar, compact display, quick add; device-tested 2026-10-18 |
| M10 UI | **done** (PR #12, on `main`, release v0.3.0): pickers, Home cards, swipes, Gestures screen, ignored to-dos (schema v5); smoke-tested on the debug app 2026-10-18 |
| Future (to do) | OCR receipts, free offline STT (SPEC §10) |

Repository: GitHub `zipclaude/activitytrack` (public), branch `main`. Docs are
mirrored in the claude.ai Project: `claude/SPEC.md`, `claude/INSTRUCTIONS.md`,
`claude/DESIGN.md`, `claude/CLAUDE.md`.

## 4. File map

```
SPEC.md                         functional specification (v0.7)
docs/INSTRUCTIONS.md            owner requirements + architectural decisions A1–A16
docs/INTERCHANGE.md             generic import/export format (M4)
docs/MANUALE.md                 user manual in Italian (owner request; keep it in step with each version)
docs/samples/                   interchange samples for device tests (TEST data)
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
core/domain/…/todo/             TodoPlan.kt (planned slot: start, growing end, postpone)
core/domain/…/settings/         Gestures.kt (swipe settings and the buttons they reveal)
app/…/ui/common/                Pickers.kt (DateField, TimeField), Swipe.kt (SwipeCard, settle)
app/…/ui/home/                  EntryCommands.kt (commands + Stop/Resume dialogs shared by Home and Agenda)
app/…/ui/settings/              GesturesScreen.kt
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

## 5. Next steps (review with the owner, 2026-10-18)

Open PRs (2026-10-19), all with base `main`, merge in this order: #16 (M14) →
#17 (M15, schema v7) → #18 (M16, schema v8) → #20 (M17); #19 (private
calendar events) is independent. After each merge the next one shows only
its own commits. Instrumented tests 46/46 on the OnePlus (2026-10-19);
M15, M16 and M17 device-tested on the debug app (2026-10-19).

M4–M9 are on `main` (2026-10-18): #4 merged, then #10 brought M5–M9 (#5–#9 had
been merged into their stacked bases, not retargeted to `main`). CI green.
Branch `beta` = copy of what is under device test; CI builds it on push and
publishes the `app-debug` APK artifact (14 days).

M10 (PR #12, merged, v0.3.0): UI per the approved mockup, see decisions
2026-10-18 (pickers, Home cards, swipe gestures, ignored to-dos). Open: which
lists show ignored to-dos and for how long (proposal: Home only on the day they
were ignored; To-dos list always, at the bottom).

### Backlog (owner notes, 2026-10-18)
Done in M13: 2, 4, 5, 6, 7, 8, 9, 10, 12; in M14: 14; in M15: 11, 13; in M16: 1.
Open: 3 (mockup on the canvas, row 4: A strip / B grid, waiting for the
owner's choice); tag filters in agenda and reports later.
14. Show the company and client colour in the drop-down lists too (done in M14).
Owner notes 2026-10-19 (done in M14): to-dos in the next 24 hours, not the
current day; Home keeps the last 2/3 completed entries; field borders by
importance; filter to show or hide ignored to-dos.
Colours: done in M12 (see the decisions log).
1. Company, client, role, preset lists sortable: per list alphabetical, by last
   use / creation, or manual.
2. Settings grouped in sub-menus.
3. Time selection: an agenda-like slider / Windows "logon hours" style grid,
   5-minute steps (instead of the Material clock).
4. "+" quick add also for role and preset in the start screen.
5. Notification type per event: banner, sound (chosen), vibration, ...
6. Agenda: a more compact view fitting the whole working day; the day view
   opens on the current activity.
7. Planned date without time: show the error on the field (today only a
   generic "invalid time" at the bottom of the dialog).
8. Update the manual.
9. To-do filter also by day: planned for today only, or all.
10. A travel or an activity under one minute is an error.
11. Entry segments editable and deletable.
12. Entry screen: attachments, expenses etc. as coloured icon buttons.
13. Tags on entries, also afterwards.

### Device tests
Done on 2026-10-18 (M4, M5 local calendar, M6, M9, restore); fixes in PR #9:
agenda editor company, list time span, due-date overdue, restore time.
Cleanup done 2026-10-18 (clean DB restored and migrated to v4, TEST backups/export
deleted, local calendar empty; samples left in Download). Still open: item 7 (manual, owner reads it at leisure); share sheet and company
"Management system" not exercised; week view text wraps in narrow blocks.

1. **M4** (`docs/samples/gestionale-test.xml` → Download): More → Import /
   export → import (report: 1 company, 2 clients, 1 site, 3 to-dos);
   import again (all unchanged); Home "To do today" → start from to-do →
   stop → complete → "Is the to-do done?"; To-dos screen add/done; export
   JSON/XML with empty dates + share; company screen "Management system".
2. **M5**: Settings → Calendar: grant, pick a Google calendar or create the
   local one; import `docs/samples/agenda-test.xml` (11 closed entries over
   two weeks) → "Sync 90 days" → events in the calendar app; start an
   entry → event appears from its start; company calendar overrides.
3. **M6**: bottom bar; Agenda list (search), day, week, month with the
   sample data; taps open entries / days; back from a tab goes Home.
4. **M7**: visual review of the home card (state pill, running time), green
   start, empty state.
5. **M9**: To-dos → Add with planned date/time in 20 min, no duration →
   reminder 15 min before (Start / Postpone 30 min / Re-plan); Postpone moves
   the plan and reminds again; Agenda Day: tap a free slot → new planned
   to-do; outlined block grows hour by hour while open; calendar event
   tentative with `#pianificata`; entry events carry `#lavoro`/`#viaggio`/
   `#pausa`; Settings → Advanced (▼) lead and postpone minutes; schema v4
   migration on the installed app keeps the data.
6. **Restore**: Import / export → "Restore backup" with a daily backup zip
   (confirm dialog, "-before-restore" zip written, app restarts with the old data).
7. Read `docs/MANUALE.md` against the app.
8. Cleanup: restore the clean DB, delete TEST files in the data folder,
   remove TEST events from the calendar (or the local calendar).

### Provisional decisions to confirm
- M5: on every change re-sync the last 14 days; "Sync 90 days" on demand;
  without a target calendar nothing is created and old events are kept.
- M6: tabs Home · Agenda · Report (the existing day report) · More
  (To-dos, Import / export, Master data, Settings); agenda opens in List;
  weeks start on Monday; one colour per company from a fixed palette.
- M7: Material You dynamic colours kept; home start button icon-only.
- M4: site coordinates exported only on request; running (ACTIVE) entries
  are not imported; roles referenced by imported entries are created.
- Open: notification icons/colours (text actions kept, see 2026-10-17).
- M9: "tags per segment" read as hashtags on the last line of the calendar
  event description (#lavoro #viaggio #pausa per kind of time in the entry,
  #pianificata on planned to-dos; always Italian); planned events are TENTATIVE.
- M9: the reminder also fires outside working hours and on days off; it is dropped
  once the block is over, the to-do is done/re-planned or an entry runs from it.
- M9: no duration → 1 h block growing hour by hour while open, capped at the
  end of the planned day; done to-dos leave the calendar; events up to 365
  days ahead; "Advanced" settings section collapsed by default.
- M9: agenda taps on a to-do open its actions (start, postpone, done,
  re-plan) instead of starting at once; free-slot taps in half-hour steps.

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
| 2026-10-17 | M4: in-app connector plugins (no dynamic loading), one per company; generic XML/JSON plugin first, later API/web-service plugins per management system; interchange model covers everything, partial content allowed |
| 2026-10-17 | M4: to-do activities imported from connectors (company, client, title, notes, refs, due date/time, priority); an entry starts from a to-do; completion asks "mark as done?"; several entries per to-do; home shows "to do today" |
| 2026-10-17 | M4: import is idempotent (external codes / uids); on conflict the app data wins and the import report lists the conflict (non-blocking) |
| 2026-10-17 | M4: exports also go to the share sheet; filters period + company + client; entries linked to a to-do carry its external code and the done flag |
| 2026-10-17 | M4: connector credentials stored encrypted with the Android keystore (structure now, used by future API plugins) |
| 2026-10-17 | M5: CalendarContract (Google via the system account, CalDAV via DAVx5); generic calendar chosen by the user, local "ActivityTrack" calendar as fallback; per-company calendar replaces the generic one; one-way app → calendar; one event per entry and day, shown from the entry start |
| 2026-10-17 | M6/M7: bottom navigation bar (Home · Agenda · Reports · More) |
| 2026-10-17 | Owner away: M4–M7 implemented autonomously as stacked PRs #4–#7; provisional choices listed in §5 for review |
| 2026-10-17 | User manual in Italian (docs/MANUALE.md): it addresses the owner as user; technical docs stay in English |
| 2026-10-18 | Branch `beta` mirrors the top of the PR stack; CI builds it on push and publishes the `app-debug` APK |
| 2026-10-18 | M9 Postpone moves the plan by the step from max(planned start, now) (owner: option 2, late answers count from now) |
| 2026-10-18 | M10 UI (mockup https://claude.ai/artifact/5fmq8LyttMzhbbLbMiYm5j approved): Material date and time pickers for every date/time field; Home: the running-entry card always shown (empty state message when none), most recent entry in the big card, every other open entry (paused, suspended) in its own small card (1/3 height) below, tapping one brings it to the big card; to-do area under a separator, one card per to-do; UI language Italian (phone stays English: per-app language) |
| 2026-10-18 | M10 swipe gestures, configurable per direction (start/end, RTL-safe) and per card type, everywhere (Home, Agenda list, To-dos): to-do left = Start, right = Postpone or Ignore; entry left = Pause / Resume, right = Stop (same questions as the Stop button) or Suspend; short swipe reveals the buttons, a long swipe runs the first one (switchable; no Undo: each action is reversible or asks first, e.g. Stop) |
| 2026-10-18 | To-do "ignored" state instead of delete: struck through, at the bottom of the lists, restored with another swipe; imports never re-create or un-ignore it |
| 2026-10-18 | M11: no bottom start button; empty card: Start + Travel buttons and swipes (left = start screen, right = travel at once, no destination); with entries open, Start / Travel icons in the top bar (Travel opens the start screen in travel mode); compact display setting (text 88 %, tighter cards); "+" quick add of company and client (start screen, to-do editor; client quick add did not exist before) |
| 2026-10-18 | M12 colours (mockup row 3 approved): 12-colour palette per company and client; band at the card start = company colour, client colour when a to-do list shows one company; the to-do company filter applies to to-dos only (Home section, To-dos screen) and stays until removed; the agenda uses the chosen company colours, automatic ones otherwise |
| 2026-10-19 | M17 editable times (owner: the app is for freelancers, not employees): setting "Orari modificabili", default on, later settable by policy; on = start time at start, end time in the app's Stop question (also for travel), segments editable/deletable and the new values replace the recorded ones (no "corrected" mark, removed from M15); off = times as recorded, "+ Travel"/"+ Pause" still allowed; a time without date is the latest occurrence; closing other running entries at a backdated start happens now; notification/widget/tile stop at once |
| 2026-10-19 | Calendar events are private (ACCESS_PRIVATE), PR #19 |
| 2026-10-19 | Field importance borders: required red until filled then green (company, client, activity or preset, to-do title, expense amount and description, quick-add names), important yellow (role, planned date/time, client of a travel or to-do), accessory blue; visual only, no new blocking rule |
| 2026-10-19 | To-do window = next 24 hours, overdue included (Home always, To-dos with a chip); "Ignored" chip; Home keeps the last 2 (normal) / 3 (compact) completed entries, open background entries first |
| 2026-10-18 | Releases: v0.2.0 (M4-M9, first signed release), v0.3.0 (M10), v0.4.0 (M11); debug icon orange |
| 2026-10-18 | Debug build "ActivityTrackDbg" (adb, tests) and signed release "ActivityTrack" (GitHub Releases on `v*` tags) coexist with separate data folders; R8 off in release until a minified build is device-tested; debug data restarts from an empty folder |
| 2026-10-18 | Stacked PRs: retarget each to `main` after the previous merge (the ruleset forbids branch deletion, so GitHub does not retarget); drafts must be marked ready first |
| 2026-10-18 | Calendar tags always in Italian (#lavoro #viaggio #pausa #pianificata), not the phone language |
| 2026-10-18 | Calendar target stays the local "ActivityTrack" calendar (green, this phone only) for now |
| 2026-10-18 | "Overdue" also for a passed due date (after the due time; date only: from the next day), not only for a planned block that is over |
| 2026-10-18 | M9 planned to-dos: manual add also from the agenda; planned slot separate from the due date; optional duration, else 1 h growing hourly; in agenda and calendar, tags per kind of time; states open → planned (date+time) → done, overdue highlighted on Home; reminder lead and postpone step configurable (advanced, defaults 15 / 30 min); plan in the interchange format; own PR |

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
- Backing up the device DB: copy `databases/*` and inspect only a copy; opening
  it with sqlite3 checkpoints and deletes the WAL. When restoring, delete the
  device's `-wal`/`-shm` first, or Room finds a stale WAL and recreates an
  empty DB.
- ViewModel `state()` flows are `WhileSubscribed`: reading `.value` of one no
  composable collects returns the initial value forever; collect it in the screen.
- The worktree guard rejects complex shell lines that mention git or run
  interpreters on inline text: write scripts to `$CLAUDE_JOB_DIR/tmp` and run them.

## 8. Commit format

End commit messages with:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
plus the session link line provided by the environment.
