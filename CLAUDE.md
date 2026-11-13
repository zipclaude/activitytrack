# CLAUDE.md — notes for the assistant

Technical notes to set up the environment and work on the code. Read
`SPEC.md`, `docs/INSTRUCTIONS.md` and `docs/DESIGN.md` for what the app does
and why. Project history and work in progress live in the GitLab issues and
merge requests (on GitHub until 0.9.7-beta1: see the first merge request).

## 0. Branches and releases (read first)

The whole workflow is in
[activitytrack-doc/agents/workflow.md](https://gitlab.com/activitytrack/activitytrack-doc/-/blob/main/agents/workflow.md)
(usually cloned next to this repository as `../activitytrack-doc`). In short:

- `main` is the **last released version**, `next` is the development branch. Work happens
  in `feature/<name>` or `issue/<n>-<name>`, cut from `next` and merged back into it with a
  merge request. Nobody pushes to `main` or `next`, except the CI job `manifest` (§3), which
  commits `update-manifest.json` to `main` after every tag, beta or stable.
- Stable release: first merge `main` into `next` (it brings in the manifest commits of §3; a
  no-op otherwise), then merge request `next` → `main`, merged fast-forward only; tag `vX.Y.Z`
  on `main`; then merge `main` into `next` again.
- Beta: tag `vX.Y.Z-beta-N` on `next` (X.Y.Z is the *next* version); Debian version
  `X.Y.Z~beta-N-1` (the CI turns the tag's first hyphen into a tilde,
  the changelog adds the revision). Needs the unit tests and the local Wine jobs, not the paid Windows pipeline.
- Security patch: `security/<name>` cut from `main`, version `X.Y.Z.1`, released at once with
  the security report, then merged into `next`.
- Test locally first. Pipelines on GitLab's shared runners start only when the owner says so;
  jobs with `tags: [local]` run on the owner's runner (see `agents/local-ci.md` in the
  documentation repository).
- The Android application id, the AppStream ids and the package names keep the
  `it.infraconsulting` names; they do not follow the GitLab group.

## 1. Working rules

- English for code, comments, commits and docs; Italian with the maintainer.
  The UI is Italian first (strings en/it/de/es).
- Indentation: tabs (`.editorconfig`).
- **Never use npm**, not even to fetch tools.
- Ask before guessing on ambiguous requirements; answers come as numbered
  lists matching numbered questions. The work queue is the GitLab issues.
- Before changing `gradle/libs.versions.toml`, check versions on the
  official pages.
- Cross-check algorithms with an independent Python script (`python3 -I`).

## 2. Build environment

| Tool | Version |
|---|---|
| JDK | 21 (`JAVA_HOME` set) |
| Android SDK | platform 37, build-tools 36.0.0, platform-tools (adb); `ANDROID_HOME` set |
| Gradle | the wrapper (`./gradlew`) |

```sh
./gradlew :core:domain:test :core:data:testDebugUnitTest assembleDebug   # unit tests + debug APK
./gradlew :app:assembleRelease                 # release APK (signed when configured, §3)
```

- Merge into `next` only after a green local build (see §0); `main` moves at a release and when CI commits the update manifest (§3).
- Build outputs copied from another path break dexing ("located outside the
  root directory"): `./gradlew clean` first.
- CI failures: read the job log of the pipeline (project → Build → Pipelines),
  or `curl -H "PRIVATE-TOKEN: $TOKEN" "https://gitlab.com/api/v4/projects/88636043/jobs?per_page=10"`
  and fetch `.../jobs/<id>/trace` for the output.

## 3. Signing and release

- One fixed release key, kept **outside the repository** (losing it means no
  more updates without reinstalling). Never commit it, never print its
  passwords.
- Local signed builds: a properties file (`storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`) named by the Gradle property
  `activitytrack.signing` (e.g. in `~/.gradle/gradle.properties`) or the
  `ACTIVITYTRACK_SIGNING` variable; without it the release is unsigned.
- CI: protected CI/CD variables `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (available to the protected tags `v*` only).
- Release: tag `vX.Y.Z` on `main`, push the tag and start a pipeline on it by hand (no
  pipeline starts on its own) → the jobs `apk` and `release` of `.gitlab-ci.yml` sign the APK and publish the GitLab Release
  (versionCode = pipeline number); a tag with a suffix
  (`v0.9.10-beta-1`) is a beta (see §0). The manuals, the brochure and their checks are in
  the `activitytrack-doc` repository (same version, released after this one).
- Update manifest (`docs/UPDATES.md`, issue #1): after the release job, the job `manifest` assembles the
  packages of the tag from the six repositories' releases, signs `update-manifest.json` with the same
  key and commits it to `main` (`[skip ci]`). Needs the variable `MANIFEST_PUSH_TOKEN` (project access
  token, Maintainer, scope api, protected + masked) besides the keystore ones. The other repositories release
  on their own: retry the job once they are out. By hand: `scripts/update-manifest.py` (see its help).
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
  `scripts/screenshots.py` takes the manual's screenshots (into `../activitytrack-doc`).
- Sync against a development server (ActivityTrack Web `runserver` on the
  computer): `adb reverse tcp:8765 tcp:8765`, server `base_url =
  http://localhost:8765`; debug builds allow plain http to localhost only
  (`app/src/debug/res/xml/network_security_debug.xml`). A pairing link can
  be opened with `adb shell am start -a android.intent.action.VIEW -n
  <debug package>/it.infraconsulting.activitytrack.MainActivity -d '<link>'`.
- `SyncServerTest` runs the sync engine against a real ActivityTrack Web:
  `ATWEB_URL=http://localhost:8765 ATWEB_CODES=<code>,<code> ./gradlew
  :core:data:testDebugUnitTest --tests '*SyncServerTest*'` (two fresh pairing
  codes of one user, from `PairingCode.issue` in `manage.py shell`); skipped
  without them, so CI does not need a server.
- `docs/samples/` holds interchange and policy samples for device tests, and
  `sync-schema.json`: the output of ActivityTrack Web's `manage.py sync_schema`.
  `SyncMappingTest` compares it with `SyncMapping`; refresh it whenever the
  server's models change, and change both sides together.
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
(manuals and brochure: repository activitytrack-doc, manual/app and brochure/)
scripts/                        sample-db.py, screenshots.py (writes into ../activitytrack-doc), sign-policy.sh,
                                update-manifest.py
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
plus the session link line provided by the environment. OpenCode/GLM
sessions (owner, 2026-10-23) use instead:
```
Co-Authored-By: GLM-5.3 <spam@zipman.it>
```

## 8. GitHub backup

GitLab is the primary; github.com/zipclaude/activitytrack is a backup that can test, package and
release on its own if GitLab is unavailable. `.github/workflows/ci.yml` mirrors `.gitlab-ci.yml`:
**keep them in step**. Nothing runs on its own there either: "Run workflow" on a branch or tag, or
a push to `ghtest/*` to try a change. A tag run needs the input `version_code` (GitLab's is
10000 + the pipeline number, a GitHub run number does not order with it). The jobs `manifest`
(commits to GitLab's `main`) and `testvm` (the owner's VM) stay GitLab-only.
