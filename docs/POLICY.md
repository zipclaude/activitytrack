# policy.json — reference (issue #48)

A policy changes the app without separate builds and without going through
Settings: it switches features off, hides, renames, colours or requires
fields, fixes settings, changes some behaviours and enables integration
plugins. It is meant for a company handing the app to its staff, or for
another trade ("Site" instead of "Client", no companies for a freelancer).

Everything absent keeps the app's default: `{ "format": "activitytrack-policy", "schema": 1 }`
is the app as shipped. Unknown keys are errors, so a typo never passes
silently. `PolicyCatalogDocTest` fails when a key of the code is missing
here.

A complete example: [`docs/samples/policy-example.json`](samples/policy-example.json).

## 1. Where and when

- File `policy.json` in the root of the data folder (beside `backup`).
- Read at start and whenever the app comes to the foreground; a file
  already handled (same fingerprint of the policy and its signature) is
  not read again.
- A valid file: first an extra backup `backup/activitytrack-<time>-policy.zip`
  (never removed automatically), the policy in force written to
  `policy_old.json`, then the new one applied, its setting values
  written, a notification on the "Policy" channel.
- An invalid file: a notification with the errors (path and line), the
  file ignored until it changes.
- Removing the file does not remove the policy (it is in the database):
  apply an empty policy instead.

### From ActivityTrack Web

A phone paired with ActivityTrack Web gets its policy from the server at
every sync (the organisation's, then its user's profile, then its user's,
merged key by key; edited on the web page Policy). The same checks apply:
an unsigned policy is accepted until a signed one pinned a CN, then the
server must sign with a certificate of that CN (`[policy] key`, `cert`,
`chain` in its settings.ini). While the server's policy is in force
`policy.json` in the data folder is ignored (one notification says so);
after unpairing it applies again. `docs/policy-catalog.json` lists every
key for the server's editor.

## 2. Signature (optional)

`policy.json.sig` beside it, written by `scripts/sign-policy.sh policy.json key.pem cert.pem [chain.pem]`
(EC → SHA256withECDSA, RSA → SHA256withRSA). The chain must lead to a CA
the phone trusts, system or user/MDM-installed; no revocation check (the
app works offline); the certificate must be valid at the time of reading.

The first signed policy applied fixes its signer's CN: from then on only
policies signed with the same CN are accepted, and unsigned ones are
refused. With a signed policy in force, every plugin must be signed with
the same CN too (`<name>.json.sig`).

## 3. Top level

| Key | Value |
|---|---|
| `format` | `"activitytrack-policy"` (required) |
| `schema` | `1` (required) |
| `id`, `version` | free text, shown in the notification |
| `features` | §4 |
| `fields` | §5 |
| `settings` | §6 |
| `behaviours` | §7 |
| `integrations` | §8 |
| `entries` | `{ "source": "free" \| "todosOnly" }`: activities start anywhere, or only from to-dos (e.g. a management system's tickets) |
| `masterData` | `{ "source": "user" \| "importOnly" }`: companies, clients and contacts created in the app, or only by imports and plugins; with ActivityTrack Web the central master data count as imported (issue #115): `importOnly` = only central ones, imports and plugins |
| `export` | `{ "formats": ["csv", "json", "xml"] }`: the export formats offered |
| `ui` | `{ "language": "it" \| "en" \| "de" \| "es" }`: the app's language, forced |

## 4. Features

`"features": { "<key>": false }` switches a feature off; essential ones
cannot be switched off.

| Key | Switched off |
|---|---|
| `entries` | essential: starting and stopping activities |
| `dayReport` | essential: the day report |
| `autoBackup` | essential: the automatic backup |
| `todos` | the to-dos (Home section, More → To-dos) |
| `planning` | planning to-dos (planned slot, agenda free-slot taps) |
| `ignoreTodos` | ignoring to-dos (swipe, button) |
| `deleteTodos` | deleting to-dos made by hand |
| `deleteEntries` | deleting activities |
| `concurrentEntries` | leaving an activity running when another starts (it is closed or suspended) |
| `travel` | travels (start buttons, "+ Travel") |
| `expenses` | expenses |
| `materials` | consumables |
| `attachments` | photos and files |
| `voiceNotes` | voice notes |
| `dictation` | dictation (microphone in text fields; its settings) |
| `location` | location tracking (its settings) |
| `calls` | call recognition (its settings) |
| `calendar` | the external calendar (its settings, sync) |
| `import` | importing files |
| `export` | exporting |
| `restore` | restoring a backup |
| `widgets` | widgets (they show a note; a tap opens the app) |
| `quickTile` | the quick settings tile (shown unavailable) |
| `gestures` | swipe gestures (cards keep their buttons; Settings → Gestures hidden) |

## 5. Fields

```json
"fields": {
	"client": { "label": { "it": "Cantiere", "en": "Site" }, "colors": { "invalid": "#6A1B9A", "valid": "#00838F" } },
	"role": { "required": true },
	"km": { "visible": false }
}
```

Per field: `visible` (false = hidden), `enabled` (false = shown, read-only),
`required` (true = must be filled, checked when starting and saving),
`label` per language (the phone's language, else the policy's English,
else the app's own name), `colors.invalid` / `colors.valid` (`#RRGGBB`:
the border while required and empty / once filled).

| Key | Field |
|---|---|
| `company` | company; hidden = use without companies (clients and to-dos of no company) |
| `client` | client; essential, may be hidden only while the company stays (which then becomes required) |
| `role` | role |
| `quickPick` | quick pick |
| `description` | description; essential |
| `contact` | contact of the client |
| `locality` | locality |
| `place` | where (client, company, home, elsewhere) |
| `notes` | notes |
| `tags` | tags |
| `refs` | external references |
| `km` | km of a travel |
| `date` | date in New activity |
| `times` | start and end; essential |
| `todoPriority` | to-do priority |
| `todoDue` | to-do due date |
| `todoPlan` | to-do planned slot |

Essential fields cannot be hidden, disabled or made optional; a hidden or
disabled field cannot be required.

## 6. Settings

```json
"settings": {
	"editableTimes": { "value": false, "locked": true },
	"compactDisplay": { "value": true, "hidden": true },
	"roundingWorkMin": 30
}
```

`value` alone (or a bare value) is only the starting value: the user may
change it. `locked: true` shows it greyed with a padlock and "set by the
policy"; `hidden: true` removes it from Settings. Locked and hidden values
are written back at every read, so they cannot drift.

| Key | Type | Values |
|---|---|---|
| `editableTimes` | true/false | |
| `confirmIntervalMin` | minutes | 10–240 |
| `maxConfirmations` | number | 0–100 (0 = unlimited) |
| `pauseIntervalMin` | minutes | 10–120 |
| `dayStartDelayMin` | minutes | 0–240 |
| `planLeadMin` | minutes | 0–240 |
| `planSnoozeMin` | minutes | 10–240 |
| `carryOverMin` | minutes after midnight | 0–720 |
| `movementThresholdM` | metres | 50–5000 |
| `backupRetention` | backups kept | 1–365 |
| `backupEveryHours` | hours | 6, 12, 24, 168 |
| `backupHour` | hour | 0–23 |
| `todoWindowHours` | hours | 0 (all), 24, 72, 168, 720 |
| `homeTodoWindowHours` | hours | 0 (all), 24, 72, 168, 720 (before 0.8.0; the Home uses `homeWindow`) |
| `widgetTodoWindowHours` | hours | 24, 72, 168, 720 |
| `roundingWorkMin` | minutes | 1, 5, 15, 30, 60 (the quantum only) |
| `roundingTravelMin` | minutes | 1, 5, 15, 30, 60 |
| `roundingPauseMin` | minutes | 1, 5, 15, 30, 60 |
| `dictationLanguage` | text | it-IT, en-US, de-DE, es-ES |
| `compactDisplay` | true/false | |
| `callsIncoming` | true/false | |
| `callsOutgoing` | true/false | |
| `callAutoPause` | true/false | |
| `callProposeEntry` | true/false | |
| `callsAlways` | true/false | |
| `nominatimEnabled` | true/false | |
| `osrmEnabled` | true/false | |
| `tilesEnabled` | true/false | |
| `stationaryRadiusM` | metres | 25–1000 |
| `stationaryMin` | minutes | 5–120 |
| `kmFactor` | number | 1.0–3.0 (road km per straight-line km) |
| `nominatimEndpoint` | text | an `https://` URL |
| `osrmEndpoint` | text | an `https://` URL |
| `tilesEndpoint` | text | an `https://` URL with `{z}`, `{x}`, `{y}` |
| `roundingWorkMode` | text | CEIL_TOLERANT, CEIL, FLOOR |
| `roundingTravelMode` | text | CEIL_TOLERANT, CEIL, FLOOR |
| `roundingPauseMode` | text | CEIL_TOLERANT, CEIL, FLOOR |
| `roundingWorkMinimumMin` | minutes | 0–60 |
| `roundingTravelMinimumMin` | minutes | 0–60 |
| `roundingPauseMinimumMin` | minutes | 0–60 |
| `gestureTodoToStart` | text | START, START_OR_DONE, POSTPONE_OR_IGNORE, POSTPONE, IGNORE, DONE, REPLAN, NONE |
| `gestureTodoToEnd` | text | as above |
| `gestureEntryToStart` | text | PAUSE_RESUME, STOP_OR_SUSPEND, STOP, SUSPEND, NONE |
| `gestureEntryToEnd` | text | as above |
| `gesturePastToStart` | text | COMPLETE, REOPEN, NONE (stopped entries on the Home list) |
| `gesturePastToEnd` | text | as above |
| `gestureFullSwipe` | true/false | a long swipe runs the first action |
| `homeWindow` | hours | -1 (today), 24, 72, 168, 720, 0 (all) |
| `homeShow` | text | comma list of TODO, MISSING, DONE, IGNORED |
| `homeFilterEntries` | true/false | the company filter hides the entries too |
| `todoShowIgnored` | true/false | |
| `agendaView` | text | LIST, DAY, WEEK, MONTH |
| `orderCompanies` | text | ALPHABETICAL, LAST_USE, MANUAL |
| `orderClients` | text | as above |
| `orderRoles` | text | as above |
| `orderPresets` | text | as above |

Settings on screens other than Settings (the Home and To-dos filters, the
Agenda view, the list orders) follow the same rule: locked, their control is
greyed with the padlock; hidden, the control is not shown and the value
stays the policy's. A gesture locked or hidden is shown greyed, or left
out, in Settings → Gestures; `features.gestures: false` still turns all
swipes off.

## 7. Behaviours

| Key | Default | Meaning |
|---|---|---|
| `askTravelDone` | true | false: a running travel is closed when another activity starts, without asking |
| `closeCompleteOnStop` | true | false: Stop always leaves the activity "to complete" |
| `minDurationSec` | 60 | shorter activities, travels and segments are an error (0 = no limit) |
| `exportMaxDays` | 366 | longest export period in days |

## 8. Integrations

```json
"integrations": { "antares": { "plugin": "antares.json" } }
```

With integrations listed, only those plugins (`enabled`, default true)
are loaded from `plugins/` in the data folder; without, every plugin
there. The plugin format is in [`PLUGINS.md`](PLUGINS.md).
