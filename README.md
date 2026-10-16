# ActivityTrack

[![CI](https://github.com/zipclaude/activitytrack/actions/workflows/ci.yml/badge.svg)](https://github.com/zipclaude/activitytrack/actions/workflows/ci.yml)

Time tracking for freelancers: companies, clients, roles, travel, pauses,
expenses and attachments, with reminders designed for people who forget to
press *stop*.

Documentation: [SPEC.md](SPEC.md) (functional rules),
[docs/INSTRUCTIONS.md](docs/INSTRUCTIONS.md) (requirements and decisions),
[docs/DESIGN.md](docs/DESIGN.md) (technical design).

License: GPL-3.0-or-later (see [LICENSE](LICENSE)).

## Layout

| Module | Content |
|---|---|
| `:core:domain` | pure Kotlin/JVM business rules (state machine, rounding, day attribution, totals) |
| `:core:data` | Room database (SQLite), entities, DAOs |
| `:app` | Android application, Compose UI, notifications |

## Build (CLI, Debian)

Requirements: JDK 17 or newer, Android SDK with platform 36 and build-tools.

```sh
sudo apt install openjdk-21-jdk-headless
# Android command-line tools: https://developer.android.com/studio#command-line-tools-only
export ANDROID_HOME="$HOME/Android/Sdk"
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :core:domain:test		# host JVM unit tests
./gradlew assembleDebug			# app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Conventions

- Indentation: tabs (see `.editorconfig`); English for code, comments and docs.
- Timestamps stored as UTC epoch milliseconds plus the IANA zone id;
  rounding is applied only in reports and exports.
- Every source file starts with `SPDX-License-Identifier: GPL-3.0-or-later`.
