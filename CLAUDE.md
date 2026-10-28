# CLAUDE.md — notes for the assistant

Technical notes to set up the environment and work on the code. Read
`SPEC.md`, `docs/INSTRUCTIONS.md` and `docs/DESIGN.md` for what the app does
and why. Project history and work in progress live in the GitHub issues and
pull requests.

## 1. Working rules

- English for code, comments, commits and docs; Italian with the maintainer.
  The UI is Italian first (strings en/it/de/es).
- Indentation: tabs (`.editorconfig`).
- **Never use npm**, not even to fetch tools.
- Ask before guessing on ambiguous requirements; answers come as numbered
  lists matching numbered questions. The work queue is the GitHub issues.
- Before changing `gradle/libs.versions.toml`, check versions on the
  official pages.
- Cross-check algorithms with an independent Python script (`python3 -I`).

## 2. Build environment

| Tool | Version |
|---|---|
| JDK | 21 (`JAVA_HOME` set) |
| Android SDK | platform 37, build-tools 36.0.0, platform-tools (adb); `ANDROID_HOME` set |
| Gradle | the wrapper (`./gradlew`) |
| Manual / brochure | pandoc, graphviz (`dot`), Chromium (`chromium` on `PATH`) |

```sh
./gradlew :core:domain:test :core:data:testDebugUnitTest assembleDebug   # unit tests + debug APK
./gradlew :app:assembleRelease                 # release APK (signed when configured, §3)
scripts/build-manual.sh                        # manual PDFs into build/manual
scripts/build-brochure.sh                      # brochure PDF (Google Fonts need the network)
```

- Push to `main` only after a green local build; CI must stay green.
- Build outputs copied from another path break dexing ("located outside the
  root directory"): `./gradlew clean` first.
- Where only `chromium-headless-shell` exists, put a `chromium` symlink to it
  on `PATH` for the manual scripts.
- Graphviz releases differ in layout: a newer `dot` rewrites every committed
  SVG with noise only; `git checkout -- docs/manual` unless a diagram changed.
- CI errors are exposed as annotations:
  `gh api repos/zipclaude/activitytrack/check-runs/<job-id>/annotations`.

## 3. Signing and release

- One fixed release key, kept **outside the repository** (losing it means no
  more updates without reinstalling). Never commit it, never print its
  passwords.
- Local signed builds: a properties file (`storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`) named by the Gradle property
  `activitytrack.signing` (e.g. in `~/.gradle/gradle.properties`) or the
  `ACTIVITYTRACK_SIGNING` variable; without it the release is unsigned.
- CI: repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
- Release: tag `vX.Y.Z` on `main` and push the tag → CI job `release` signs
  the APK and publishes the GitHub Release with the manual PDFs
  (versionCode = CI run number). `scripts/check-manual.sh` checks the manual
  version line and images; versions released with the previous manual are
  listed in `scripts/manual-unchanged`.
- The debug APK is signed with the local `~/.android/debug.keystore`; CI
  artifacts use another key (reinstall needed when switching).

## 4. Device tests

- Two apps side by side: debug `it.infraconsulting.activitytrack.debug`
  (data folder `ActivityTrackDbg`, installed with adb, used for tests) and
  release `it.infraconsulting.activitytrack` (data folder `ActivityTrack`).
  Tests touch only the debug app.
- `./gradlew installDebug`, then
  `adb shell am start -n it.infraconsulting.activitytrack.debug/it.infraconsulting.activitytrack.MainActivity`.
- Before tests that create data, back up `databases/*` with `run-as` and
  restore afterwards. Inspect only a copy: opening the DB with sqlite3
  checkpoints and deletes the WAL. When restoring, delete the device's
  `-wal`/`-shm` first, or Room finds a stale WAL and recreates an empty DB.
- `scripts/sample-db.py` builds an invented DB (schema of
  `ActivityTrackDatabase.VERSION`, times relative to now) and
  `scripts/screenshots.py` takes the manual's screenshots.
- `docs/samples/` holds interchange and policy samples for device tests.
- Some OEM ROMs (MIUI) refuse test-only APKs over USB
  (`INSTALL_FAILED_USER_RESTRICTED`): instrumented tests cannot run there;
  check behaviour from the UI and a copy of the DB. USB adb on Debian needs
  a udev rule for the phone's vendor id.
- WorkManager ignores `cmd jobscheduler run -f` before schedule; use
  Settings → "Back up now" (`BackupWorker.runNow`).
- Never tap the notification shade by coordinates (layouts shift and a miss
  hits "clear all"); `uiautomator` cannot read the shade.

## 5. Technical pitfalls

- ViewModels live in the back-stack entry store (`ScopedEntry`), not the
  Activity, or a reopened screen shows stale state.
- ViewModel `state()` flows are `WhileSubscribed`: `.value` of one nothing
  collects stays the initial value; collect it in the screen.
- Local wall-clock times: `LocalDate.atTime(...)` / `atMinute`, never
  `atStartOfDay().plusMinutes()` (wrong on DST days).
- Regexes run on ICU on Android: escape both braces (`\\{`, `\\}`); a bare `}`
  passes JVM tests and crashes on device.
- Coordinates in URLs: format with `Locale.ROOT`.
- API 34+: users can dismiss `setOngoing` notifications; `MainActivity.onStart`
  re-posts them.
- A location FGS started from the background without "Allow all the time"
  throws `SecurityException`: `LocationService.update` catches it and stops.
- `NotificationManager.cancel` is ignored for the FGS notification: switch
  with `startForeground(newId)` first, then cancel the old id.
- LazyColumn composes only visible items: effects or launchers that must run
  on request live outside the lazy list.
- Compose `pointerInput` blocks capture variables stale across
  recompositions: read changing values through `rememberUpdatedState`.
  Material3 tabs take events in the Main pass: a drag detector over them
  watches the Initial pass and consumes nothing.
- Glance: never `ColorProvider(Int)` with a raw ARGB value (serialised as a
  resource id, the launcher crashes); use a Compose `Color` or a theme
  ColorProvider. A `fillMaxSize()` child in a Column clips its siblings: use
  `defaultWeight().fillMaxWidth()`. 48 dp circles overflow a 4×2 at large
  font densities: 40 dp. Lists of rows: LazyColumn.
- Glance: `update()` reaching a session still open recomposes without
  calling `provideGlance`, so data loaded before `provideContent` stays
  stale; read widget data from flows inside the composition.
- Some ROMs defer widget and alarm updates of a cached app (app-standby
  buckets, up to minutes): launching the app forces the redraw.
- Room AutoMigrations that rebuild a table drop its triggers: the sync
  triggers (`SyncSchema`) are re-created with IF NOT EXISTS at every open,
  but a migration that needs them during its own run must create them.
  `SyncSchemaTest` (JVM, sqlite-jdbc) checks a migration against the
  exported schema the way Room does; extend it with every new migration of
  a synced table.
- Synced rows carry a `uid` defaulting to a fresh random one: an entity
  rebuilt with the old id and saved would change it; the `keep_uid` trigger
  puts the old one back. Prefer `copy()` of the loaded row anyway.
- `git add core` after switching branches may stage files another branch
  left untracked (e.g. a Room schema JSON of a newer version): check
  `git status` before committing.

## 6. File map

```
SPEC.md                         functional specification
docs/INSTRUCTIONS.md            requirements + architectural decisions A1–A16
docs/DESIGN.md                  technical design, toolchain, risks
docs/INTERCHANGE.md             import/export format
docs/POLICY.md                  policy.json reference (PolicyCatalogDocTest keeps it complete)
docs/PLUGINS.md                 plugin descriptor format; plugins/ holds the plugins and their guides
docs/manual/{it,en}/            quickstart.md + manual.md, diagrams/diagrams.py (Graphviz), img/, style.css
docs/brochure/                  brochure.html + committed PDF
scripts/                        build-manual.sh, check-manual.sh, manual-unchanged, build-brochure.sh,
                                sample-db.py, screenshots.py, sign-policy.sh
gradle/libs.versions.toml       dependency versions
core/domain/                    pure Kotlin rules: model, state machine, rounding, reports, geo, reminders,
                                export, settings (AppSettings, Gestures), policy, plugin engine; unit tests
core/data/                      Room (Entities.kt, DAOs, migrations), repositories, DataStore settings,
                                storage (SAF data folder, backup), interchange import, plugin sync
app/                            Compose UI (ui/home, start, entry, todo, agenda, report, settings,
                                masterdata, common), notifications, reminders, backup, location,
                                widgets and tile (quick/), calls, calendar, policy loader, plugins (connect/)
app/src/debug/                  MockLocationReceiver, debug shortcuts
```

## 7. Commit format

End commit messages with:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
