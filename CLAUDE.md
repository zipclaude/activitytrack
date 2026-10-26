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
| M14 fields | **done** (PR #16, on `main`, v0.5.0): field importance borders, drop-down colours (backlog 14), 24-hour to-dos, ignored filter, recent completed entries on Home |
| M19 tag filters | **done** (PR #22, on `main`, v0.5.0): agenda, day report, export; device-tested 2026-10-19 |
| 0.6.0 batch | **done** (PR #23-#26, on `main`, v0.6.0): owner notes 2026-10-19, user docs it/en + PDF; device-tested 2026-10-19 |
| Issue #59 Home tabs | **implemented** (PR #67, open): top unchanged, bottom Pianificate/Chiuse with swipe-on-header switch, remembered tab, windows 24/72/168/720 h + all (def. 24 h) and today/24 h/7/30 d (def. today, 06:00 boundary), Chiuse = closed/to-complete entries + done to-dos, no-date section; device-tested 2026-10-23 on the Xiaomi (upgrade with data, tabs, windows, swipe, restart memory) |
| Issue #62 widgets | **done** (PR #68): 4×2 current-activity widget (latest open entry as the Home big card, elapsed per minute, stop at once, Avvia → start screen, Travel headless, resizable) and 4×2 planned to-dos (direct Start from a to-do with a client, else the start screen; Pause/Stop on the started row; done rows leave; window setting def. 24 h + overdue, policy `widgetTodoWindowHours`); WidgetTicker minute alarm re-armed by BootReceiver; device-tested 2026-10-24 on the Xiaomi (render, ticker, stop, direct start, row pause/stop; fixes: launcher crash from ColorProvider(Int), button clipping, 40dp circles, LazyColumn rows) |
| Issues #71/#72 | **done** (PR #73, #74, 2026-10-25, device-tested on the Xiaomi): #71 saving a to-do planned after its due asks to align the due date ("Allinea la scadenza"/"Mantieni"; #70 closed wont-fix — overdue correctly follows a passed due date); #72 the Home header's text titles became a Material 3 TabRow (count beside the active label), swipe kept through an Initial-pass tracker + rememberUpdatedState |
| Usability batch #76/#78/#80 | **done** (PR #81, #82, #83, 2026-10-25, from the usability review; build green, NOT device-verified — adb went offline): #80 one term for to-dos (tab "Da fare", widget "Da fare 4×2", settings labels; "planned" stays an adjective), #78 settings sections show a dot when dirty and ask before leaving (arrow/Back/gesture), #76 live red hint under Scadenza when plan > due with inline "Allinea la scadenza" |
| Issue #28 calls | **done** (PR #32, #33, on `main`): contacts, call recognition, call pauses, client calls, on-call option (schema v10); instrumented 53/53 and device-tested 2026-10-20 (real incoming call: pause, proposal, client calls, pause note with editable times off) |
| M18 time grid | **done** (PR #21, on `main`, v0.5.0): backlog 3, grid mockup B; device-tested 2026-10-19 |
| M17 editable times | **done** (PR #20, on `main`, v0.5.0): setting (default on), start/end time when starting/stopping, segments editable only when on; device-tested 2026-10-19 |
| M16 list order | **done** (PR #18, on `main`, v0.5.0): backlog 1 (schema v8); device-tested 2026-10-19 |
| M15 tags, segments | **done** (PR #17, on `main`, v0.5.0): backlog 11 and 13 (schema v7); device-tested 2026-10-19 |
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
docs/manual/{it,en}/           quickstart.md + manual.md (owner: it/en, PDF with screenshots and diagrams);
                                diagrams/diagrams.py (Graphviz), img/ screenshots, style.css
scripts/build-manual.sh         PDFs into build/manual (pandoc + dot + headless Chromium)
scripts/check-manual.sh         release step: version line, docs changed since the last tag, images present
scripts/manual-unchanged        versions released with the previous manual (owner's choice; skips check-manual)
docs/brochure/                  presentation brochure (it, 4 A4 pages): brochure.html + committed PDF
scripts/build-brochure.sh       brochure PDF (headless Chromium, Google Fonts need the network)
scripts/sample-db.py            invented sample DB for the screenshots
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

## 5. Next steps (review with the owner, 2026-10-25)

Released v0.7.5 (2026-10-25): #71 (align the due date when the plan
passes it) and #72 (Material TabRow on the Home header), both
device-tested on the Xiaomi; manual 0.7.5 with retaken Home screenshots
(the new tabs). #70 closed wont-fix (overdue correctly follows a passed
due date). Usability review 2026-10-25 → issues #76–#80; #76, #78, #80
done (PR #81–#83) but **not device-verified yet** (adb went offline
mid-session): pending check — tab "Da fare", the settings dot and leave
confirm, the live overdue hint; then the next release retakes the Home
screenshots (tab renamed) and documents #76/#78 in the manual. Open
issues: #77 (gesture discoverability hints), #79 ("Stop rapido").
Review 2026-10-26 of the work since v0.7.3 (branch `review-fixes-0-7-6`):
the to-dos widget did not follow the to-dos (QuickWidgets.refresh ran only
on open-entry changes, boot and the ticker) → QuickWidgets.start observes
to-dos, clients and the widget window; "Chiuse · Oggi" before 06:00 put its
boundary in the future (test codified it) → yesterday 06:00. Noted, not
changed: the align-due dialog saves on an outside tap; same-day plan after
the due time gets no warning; the widget Start leaves other running entries
running; local `lintDebug` crashes in ExperimentalDetector (CI runs no lint).
Manual 0.7.6 text done; home.png still shows the old tab label (needs the
phone).
Owner 2026-10-26 ("sistema i bug, lavora alle due mancanti"), branch
`usability-77-79`: the align-due dialog's outside tap goes back to the
editor; startsAfterDue/alignedDue (TodoPlan.kt) also catch a same-day start
after the due time (aligning drops that due time); the to-dos widget's
Start suspends the running (not paused) entries; #79 "Stop rapido" in both
Stop dialogs (EntryCommander.quickStop: no end time, no resume prompt);
#77 one-off Home tip (gestureHintSeen) + faint ‹ › on SwipeCard until the
first swipe (swipeLearned, LocalSwipeHints from MainActivity), reset in
Gestures. Manual 0.7.6 documents all of it. NOT device-tested; the
release waits for the phone (checks + screenshots, home.png tab label).
Device test 2026-10-26 on the Xiaomi (all of #76/#77/#78/#79/#80 and the
review fixes): found and fixed the tip sitting above the visible list, the
overdue hint's squeezed button, the stale Glance sessions (both widgets
now read flows) and the missing space after the widget time;
screenshots.py taps the "Da fare" tab first and finds the widgets' launcher
page. The launcher now shows the to-dos 4×2 and the 4×1 status widget, not
the current-activity 4×2 the caption names.
Remaining untried on a device: policy lock of `widgetTodoWindowHours`;
done to-dos in Chiuse (sample DB has none), 3-day window chip, tag
filter on Chiuse. The debug phone (Xiaomi) keeps the two 4×2 widgets on
its home screen and my test to-dos in the debug DB ("Report trimestrale"
now planned and due 27/10 from the #71 test; backups of earlier states
in ~/activitytrack-device-backups/); a stray Firefox shortcut from a
drag attempt may sit on its second page.

Issue #48 (policy.json + declarative plugins), branch `issue-48-policy`
(no PR yet, 2026-10-21): order A1–A2 → B1–B6 (0.7.0 = Antares as the first
plugin, PR #29 to be closed and reused) → A3–A6. Done: A1 (domain/policy:
catalogue, parser, validator), A2 (PolicyLoader at start/onStart, extra
`-policy.zip` backup never expired, policy_old.json, policy_record table
schema **v12**, settings enforcement, optional signature policy.json.sig by
scripts/sign-policy.sh: chain to phone CAs incl. user, no revocation, first
signed policy pins the CN, unsigned refused afterwards). Owner answers:
hidden settings hidden, fixed ones locked with padlock; essential fields
client (company if client off), times, description; labels fall back to
the policy's English. Descriptor format approved
(docs/PLUGINS.md); B1 done (domain/plugin: Plugin model, PluginParser with
reference checks per section, JsonPath subset, Expr + eval; tests on
plugins/antares.json). Owner: plugins live outside the app (`plugins/` in
the repo, copied to the data folder's plugins/); with a signed policy,
plugins signed with the same CN; plugin assigned per company (whole app
without companies); PR #29 to be closed, code reused. Owner: `plugins/` folder of this repo
(no separate repo). B2 done (PluginClient: https only, paging, retries,
401 renewal; StaticAuthorizer; PluginEnv; app HttpTransport, OAuthPkce +
OAuthRedirectActivity generic, PluginRegistry loads data-folder plugins/
with the policy check, signature rule). B3 done (PluginImport; PluginSync
.download: remote_item lists, clients, contacts, to-dos with
plugin_fields). B4 done (PluginExport groupings = AntaresPlan generalized,
bodies, hash plan; remote_record; PluginSync.sync sends). Schema v12 (not
released) holds policy_record, remote_item, remote_record, time_entry and
todo plugin_fields, contact external_key. B5 done (ConnectorSection plugin card from
options, Log in/out, Sync now + report; PluginFieldsSection in the entry
screen; PluginWork: close prompt/notification, periodic fetch, REMOTE
channel). Release v0.6.7 (owner 2026-10-21: no Antares, just to see it
work; NOT device-tested; manual §11 "Policy e integrazioni"). A3-A6 done (branch `issue-48-ui`, 2026-10-21):
LocalPolicy + PolicyField/PolicySetting (ui/common/PolicyUi.kt), fields
hidden/read-only/renamed/coloured/required (POLICY_REQUIRED checked in the
start, entry and to-do view models), features gated on every screen and in
receivers/widgets/tile, locked settings with padlock, behaviours wired
(EntryRepository min length and close on Stop, travel question, export
range), TODOS_ONLY, importOnly, forced language; company-less use (schema
**v13**: client and todo company_id nullable; interchange companyKey
optional); docs/POLICY.md + PolicyCatalogDocTest. Device-tested 2026-10-21
on the new debug phone (Xiaomi 22031116BG, Android 13, adb `--user 0`;
MIUI needs "Install via USB"): policy apply, -policy.zip, policy_old.json,
plugin card, labels/colours, required role, locked/hidden settings,
plugin fields in the entry; fixes: ICU regex in Expr (crash), plugin
fields item loaded outside the lazy list, field spacing. B6 done 2026-10-22 on the demo (client "activitytrack", PascalCase
claims, tickets where the person is technician OR applicant, ticket
CustomerId + CustomerPremisId kept as to-do fields and sent, else HTTP 500;
POST and PUT 200); PR #29 closed. Issue #52 done in 0.7.1 (not device-tested). Issue #57 (branch `issue-57-segments`): segment kind badges (work green, pause yellow, travel blue) and the running segment's start editable (EntryRepository.moveRunningStart, overlap confirm); device-tested 2026-10-23 (badge, running start, future start refused). Device tests 2026-10-23 on the Xiaomi: #34 done (in-app question, background notification with Send, offline queue sent on reconnect, scope All, periodic fetch, new-ticket notification); #48 signed policy (applied, CN pinned, unsigned and other-CN refused, plugin refused until signed with the same CN). Bugs fixed on branch `issue-48-remote-delete` (schema **v14**: remote_deleted keeps the remote ids of a deleted entry for the next sync; Antares list `premises` + entry field Sede, list sources `when`, else HTTP 500 on service activities); B7 guide plugins/GUIDE.{it,en}.md with docs/samples/plugins/timesheet-example.json checked by a test. Not tested: close modes Always/Never. Not yet tested: deleting a sent activity, sending an
activity on a service (premises?).
Released v0.6.6 (2026-10-21, NOT device-tested): #44 done (Home filter on
the entries, Date in New activity: future day plans, past day records with
its end; backup every 6/12/24 h or weekly at a preferred hour). Released v0.6.5 (2026-10-20, NOT device-tested, like 0.6.4): consumables
(#41), contact e-mail/optional numbers and entry contact (#44), compact
fields, Description / Quick pick, planning editor per mockup
https://claude.ai/artifact/QvVjq5Fn5a57s2smRFN2dg (#47); schema v11.
Next (0.6.6, owner 2026-10-20): device test of 0.6.4/0.6.5, manual updated
with screenshots (0.6.4/0.6.5 only bumped the version line; agenda-day shot
captioned "old"), the owner's new requests. Antares PR #29 moves to schema
v12 (main took v11). Released v0.6.3 (2026-10-20): #40 segment overlap resolved on confirm
(SegmentOverlap), delete an entry (attachments first; ticket to-do reopens),
delete hand-made to-dos, menu icons. Before it, v0.6.2 (2026-10-20): #37 start/end row, future start → plan,
add row in the entry screen, to-do start/end, Home to-do window, quick add
everywhere, smaller manual screenshots. Before it, v0.6.1 (2026-10-20): calls with clients (#28, #33), no automatic
colours (#27), empty Start opens the range grid (#30). Next: v0.7.0 with
Antares (PR #29, #34) once Saidea gives a public client and a demo user. To
check on the release app: READ_CALL_LOG may be a restricted setting for an
APK installed from a file (App info → ⋮ → Allow restricted settings). Work queue = GitHub issues
(owner answers questions in issue comments, numbered).

**Open (2026-10-19 evening):**
- PR #29 `m24-antares-auth` (CI green, NOT to merge before a test on
  demo.goantares.uno): Antares Public API v3 connector. Public OAuth client
  (PKCE, no secret, redirect `<applicationId>:/oauth2redirect`), refresh
  token in SecretStore; AntaresPlan (one activity per report day, from first
  work start, length rounded by work rule with pauses inside, pause >
  `pause_split_min` (default 60) splits, travel minutes/km to nearest block);
  schema v10 (remote_portfolio, remote_activity_type, remote_activity, entry
  remote_* fields, todo remote_portfolio_id); AntaresSync (services/projects
  active via /v3/Enums EntityState, activity types, tickets with TechnicianId
  = me → to-dos, POST/PUT(Timestamp)/DELETE with payload hash); entry
  "Antares" section; company screen Log in / Sync. Waiting: Saidea must
  register a public client (owner asks); customer list endpoint unknown
  (clients matched by externalCode = CustomerId). Saidea's API doc is NOT
  to be committed (public repo). Instrumented tests for v10 not run yet.
- Issue #28 calls: **implemented** (PR #32, merged 2026-10-19, schema v10;
  closed). CallReceiver (PHONE_STATE, OFFHOOK → IDLE) + CallMonitor reads
  the latest call-log row; Contacts master data (import via ACTION_PICK,
  no READ_CONTACTS); client screen "Calls"; Settings → Calls (incoming /
  outgoing off by default, auto pause and proposal on); pause notes
  editable also with editable times off. Device-tested 2026-10-20; the
  owner's PBX presents +39 0461 042222, not the published 0461042200. Future: contacts may receive activity reports.
- PR #29 must move its schema to **v11** (AutoMigration 10→11) before merging.
- PR #33 merged: calls also outside working hours (setting `calls_always`, default off; owner: on-call duty).
- Issue #34 (Antares: offer Sync when an entry closes + scheduled fetch, 5 min–1 day, default 1 h): questions asked, waiting for answers; WorkManager periodic minimum is 15 min.

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
Done in M18: 3 (grid, mockup B, owner 2026-10-19); in M19: tag filters in agenda
and reports. Backlog empty.
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
7. Read `docs/manual/it/manual.md` against the app.
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
| 2026-10-19 | User docs: quick start + full manual (usage, every setting, entities, state diagrams, flows) in Italian and English, PDF with screenshots in the matching UI language; the release job fails unless the manual carries the tag version, changed since the previous tag and has its images; PDFs attached to the GitHub Release |
| 2026-10-19 | The assistant may merge its PRs once green (owner); GitHub issues as the work queue (see §5) |
| 2026-10-18 | Branch `beta` mirrors the top of the PR stack; CI builds it on push and publishes the `app-debug` APK |
| 2026-10-18 | M9 Postpone moves the plan by the step from max(planned start, now) (owner: option 2, late answers count from now) |
| 2026-10-18 | M10 UI (mockup https://claude.ai/artifact/5fmq8LyttMzhbbLbMiYm5j approved): Material date and time pickers for every date/time field; Home: the running-entry card always shown (empty state message when none), most recent entry in the big card, every other open entry (paused, suspended) in its own small card (1/3 height) below, tapping one brings it to the big card; to-do area under a separator, one card per to-do; UI language Italian (phone stays English: per-app language) |
| 2026-10-18 | M10 swipe gestures, configurable per direction (start/end, RTL-safe) and per card type, everywhere (Home, Agenda list, To-dos): to-do left = Start, right = Postpone or Ignore; entry left = Pause / Resume, right = Stop (same questions as the Stop button) or Suspend; short swipe reveals the buttons, a long swipe runs the first one (switchable; no Undo: each action is reversible or asks first, e.g. Stop) |
| 2026-10-18 | To-do "ignored" state instead of delete: struck through, at the bottom of the lists, restored with another swipe; imports never re-create or un-ignore it |
| 2026-10-18 | M11: no bottom start button; empty card: Start + Travel buttons and swipes (left = start screen, right = travel at once, no destination); with entries open, Start / Travel icons in the top bar (Travel opens the start screen in travel mode); compact display setting (text 88 %, tighter cards); "+" quick add of company and client (start screen, to-do editor; client quick add did not exist before) |
| 2026-10-18 | M12 colours (mockup row 3 approved): 12-colour palette per company and client; band at the card start = company colour, client colour when a to-do list shows one company; the to-do company filter applies to to-dos only (Home section, To-dos screen) and stays until removed; the agenda uses the chosen company colours, automatic ones otherwise |
| 2026-10-19 | M17 editable times (owner: the app is for freelancers, not employees): setting "Orari modificabili", default on, later settable by policy; on = start time at start, end time in the app's Stop question (also for travel), segments editable/deletable and the new values replace the recorded ones (no "corrected" mark, removed from M15); off = times as recorded, "+ Travel"/"+ Pause" still allowed; a time without date is the latest occurrence; closing other running entries at a backdated start happens now; notification/widget/tile stop at once |
| 2026-10-19 | Time selection = grid of 5-minute cells (mockup B): single times by tap with "Exact time" falling back to the clock; segment dialogs pick start and end together (drag or two taps), other segments of the day hatched; 12-hour window moved by 3 h |
| 2026-10-19 | Calendar events are private (ACCESS_PRIVATE), PR #19 |
| 2026-10-19 | Field importance borders: required red until filled then green (company, client, activity or preset, to-do title, expense amount and description, quick-add names), important yellow (role, planned date/time, client of a travel or to-do), accessory blue; visual only, no new blocking rule |
| 2026-10-19 | To-do window = next 24 hours, overdue included (Home always, To-dos with a chip); "Ignored" chip; Home keeps the last 2 (normal) / 3 (compact) completed entries, open background entries first |
| 2026-10-18 | Releases: v0.2.0 (M4-M9, first signed release), v0.3.0 (M10), v0.4.0 (M11), v0.4.1 (M12), v0.4.2 (M13), v0.5.0 (M14–M19 + private calendar events, 2026-10-19), v0.6.0 (2026-10-19), v0.6.1 (calls #28/#33, #27, #30; 2026-10-20); v0.6.2 (#37 display and usability fixes; 2026-10-20); v0.6.3 (#40 overlapping segments, delete entry/to-do, menu icons; 2026-10-20); v0.6.4 (#44 part, untested; 2026-10-20); v0.6.5 (#41, #47, #44 contacts, schema v11, untested; 2026-10-20); v0.6.6 (#44 completed, untested; 2026-10-21); v0.6.7 (#48 policy + plugin engine, untested; 2026-10-21); v0.6.8 (#48 policy on the screens, company-less, schema v13; tested on the Xiaomi debug app; 2026-10-21); v0.6.8.1 (manual screenshots refreshed on the Xiaomi, app unchanged; 2026-10-22); v0.7.0 (Antares as the first plugin, tested on demo.goantares.uno; dialog label and drop-down above keyboard fixes; Antares guide PDF; 2026-10-22); v0.7.1 (#52 planning by date / point / range, end-of-day re-plan notice; #54 colour in quick add; planning not device-tested; 2026-10-22); v0.7.2 (#57 segment colours and running start, remote delete of deleted entries, Antares premises, plugin guide; #34/#48 device-tested on the Xiaomi; schema v14; 2026-10-23); v0.7.3 (#61 calendar events replaced on synced accounts, Outlook drops updates; manual left at 0.7.2 by the owner; 2026-10-23); v0.7.4 (#59 Home tabs Pianificate/Chiuse, #62 widgets 4×2 current activity and planned to-dos; both device-tested on the Xiaomi; manual updated with new screenshots; 2026-10-24); v0.7.5 (#71 the due-date alignment question when the plan passes it, #72 Material TabRow on the Home header; device-tested; manual 0.7.5 with retaken Home screenshots; 2026-10-25); v0.7.0 = Antares (PR #29) once tested on the demo; debug icon orange |
| 2026-10-18 | Debug build "ActivityTrackDbg" (adb, tests) and signed release "ActivityTrack" (GitHub Releases on `v*` tags) coexist with separate data folders; R8 off in release until a minified build is device-tested; debug data restarts from an empty folder |
| 2026-10-18 | Stacked PRs: retarget each to `main` after the previous merge (the ruleset forbids branch deletion, so GitHub does not retarget); drafts must be marked ready first |
| 2026-10-18 | Calendar tags always in Italian (#lavoro #viaggio #pausa #pianificata), not the phone language |
| 2026-10-18 | Calendar target stays the local "ActivityTrack" calendar (green, this phone only) for now |
| 2026-10-18 | "Overdue" also for a passed due date (after the due time; date only: from the next day), not only for a planned block that is over |
| 2026-10-18 | M9 planned to-dos: manual add also from the agenda; planned slot separate from the due date; optional duration, else 1 h growing hourly; in agenda and calendar, tags per kind of time; states open → planned (date+time) → done, overdue highlighted on Home; reminder lead and postpone step configurable (advanced, defaults 15 / 30 min); plan in the interchange format; own PR |
| 2026-10-23 | #59 Home tabs (owner answers in the issue): top of the Home unchanged; bottom = Pianificate/Chiuse switched by tap or a swipe on the header (card swipes untouched); planned windows 24 h/3 d/7 d/30 d/all (def. 24 h, overdue first, no-date to-dos at the bottom under a separator, ignored-today last); Chiuse windows today/24 h/7 d/30 d (def. today, from the 06:00 carry-over boundary as in the reports), entries closed or to complete + to-dos marked done, newest end first; company filter on the list shown (the "also the entries above" checkbox stays), tag filter only on Chiuse (to-dos carry no tags, as in the agenda); the last tab is remembered (setting `home_tab`) |
| 2026-10-23 | #62 widgets (owner answers): current-activity 4×2 — stop immediate like the notification, only the latest open entry, elapsed counter per minute, Avvia opens the start screen, resizable from 4×2; planned to-dos 4×2 — direct Start from the widget (approved exception to the 2026-10-17 "no start outside the app" rule), days visible a setting (def. 24 h + overdue), started row shows Pause/Stop, done rows leave |

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
- `git add core` on a branch also stages files another branch left untracked
  (a Room schema JSON of a newer version reached `main` this way, 2026-10-20):
  check `git status` before committing after switching branches.
- `scripts/sample-db.py` builds the schema of `ActivityTrackDatabase.VERSION`;
  today's sample times are relative to now (the Home's big card = latest).
- LazyColumn composes only visible items: effects or launchers that must run
  on request (e.g. the entry screen's add row) live outside the lazy list.
- USB adb on Debian needs a udev rule for vendor 22d9 (OnePlus/OPPO); the
  owner added it 2026-10-20, USB is steadier than wireless debugging.
- MIUI (Xiaomi debug phone) refuses test-only APKs over USB at once
  (`INSTALL_FAILED_USER_RESTRICTED`, no prompt): instrumented tests cannot
  run there; check behaviour from the UI and a copy of the DB instead.
- Glance widgets: never `ColorProvider(Int)` with a raw ARGB/`android.graphics.Color`
  value — RemoteViews serialises it as a **resource id** and the launcher
  crashes at inflation (`Resources$NotFoundException`, crash loop; found on
  MIUI 2026-10-24). Use a Compose `Color` or a theme ColorProvider.
- Glance widgets: a child with `fillMaxSize()` inside a Column takes the whole
  height and clips the siblings below (the 4×2 buttons vanished); give the
  stretchy child `defaultWeight().fillMaxWidth()` only. Command circles of
  48 dp overflow a 4×2 at MIUI large-font densities (~3.7 px/dp): 40 dp as
  the 4×1. Lists of rows: LazyColumn (scrolls, fixed items clip).
- Compose `pointerInput` blocks capture their variables stale across
  recompositions (the key list decides restarts): read changing values
  through `rememberUpdatedState` inside the block. Material3 tabs take
  their events in the Main pass: a drag detector over them must watch
  the Initial pass and consume nothing (HomeTabs, issue #72).
- Glance: `update()`/`updateAll()` reaching a session still open (about 45 s
  after its start) recomposes without calling `provideGlance` again, so data
  loaded before `provideContent` stays stale (a done to-do stayed on the
  widget, 2026-10-26). Read the widget data from flows inside the
  composition (`collectAsState`), with a minute-ticks flow for times.
- MIUI throttles widget/alarms updates of a cached app (app-standby buckets,
  ~45 s deferrals, up to ~3 min): a widget action applies at once but the
  widget redraws when the process next runs. Launching the app forces it.
- The worktree guard rejects complex shell lines that mention git or run
  interpreters on inline text: write scripts to `$CLAUDE_JOB_DIR/tmp` and run them.

## 8. Commit format

End commit messages with:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
plus the session link line provided by the environment. OpenCode/GLM-5.3
sessions (owner, 2026-10-23) use instead:
```
Co-Authored-By: GLM-5.3 <spam@zipman.it>
```
