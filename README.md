# ActivityTrack

[![pipeline status](https://gitlab.com/activitytrack/activitytrack/badges/main/pipeline.svg)](https://gitlab.com/activitytrack/activitytrack/-/pipelines)

Time tracking for freelancers: companies, clients, roles, travel, pauses,
expenses and attachments, with reminders designed for people who forget to
press *stop*.

Documentation: [SPEC.md](SPEC.md) (functional rules),
[docs/INSTRUCTIONS.md](docs/INSTRUCTIONS.md) (requirements and decisions),
[docs/DESIGN.md](docs/DESIGN.md) (technical design).

License: GPL-3.0-or-later (see [LICENSE](LICENSE) and [License](#license) below).

## Layout

| Module | Content |
|---|---|
| `:core:domain` | pure Kotlin/JVM business rules (state machine, rounding, day attribution, totals) |
| `:core:data` | Room database (SQLite), entities, DAOs |
| `:app` | Android application, Compose UI, notifications |

## Build (CLI, Debian)

Requirements: JDK 17 or newer, Android SDK with platform 37 and build-tools.

```sh
sudo apt install openjdk-21-jdk-headless
# Android command-line tools: https://developer.android.com/studio#command-line-tools-only
export ANDROID_HOME="$HOME/Android/Sdk"
sdkmanager "platforms;android-37" "build-tools;37.0.0" "platform-tools"
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :core:domain:test		# host JVM unit tests
./gradlew assembleDebug			# app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Conventions

- Indentation: tabs (see `.editorconfig`); English for code, comments and docs.
- Timestamps stored as UTC epoch milliseconds plus the IANA zone id;
  rounding is applied only in reports and exports.
- Every file that can carry a comment starts with `SPDX-License-Identifier` and `SPDX-FileCopyrightText`
  (see [License](#license)).

## License

Copyright © 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it>.

ActivityTrack is free software under the GNU General Public License, version 3 or (at your option) any later version (`GPL-3.0-or-later`, text in [LICENSE](LICENSE)), without any warranty. Every file that can carry a comment starts with its `SPDX-License-Identifier` and `SPDX-FileCopyrightText` lines; the others (JSON, test data, images) are under the same licence.

What comes from other authors keeps its licence:

- the icons in `app/src/main/res/drawable` marked `Apache-2.0` have the shape of Material Icons (Google);
- `gradlew`, `gradlew.bat` and `gradle/wrapper` are Gradle's (Apache-2.0);
- the Noto Sans glyphs in `app/src/main/assets/fonts` are under the SIL Open Font License 1.1 (`OFL.txt`).

The APK also holds the libraries the application is built with (AndroidX, Kotlin, ZXing, MapLibre Native): `app/src/main/assets/licenses/THIRD-PARTY.txt` lists them, the licence texts are beside it, and the screen **More → About and licences** shows them. A new library goes into that list (`LicenceNoticesTest` checks it).
