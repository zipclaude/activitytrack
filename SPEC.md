# ActivityTrack — functional specification

Status: v0.6 (2026-10-17). Application ID `it.infraconsulting.activitytrack`.

Personal Android app for a freelancer who works for several companies, on their
clients, in different roles. Goal: capture time with minimal interaction and
produce reports without having to remember anything afterwards.

## 1. Platform and constraints

| Item | Decision |
|---|---|
| License | GPL-3.0-or-later |
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| SDK | minSdk 33 (Android 13), targetSdk / compileSdk 36 (Android 16) |
| Reference device | Android 16 phone |
| Distribution | personal APK (sideload); no Google Play Services, no proprietary SDKs |
| Database | SQLite via Room, app-private storage |
| User data directory | folder chosen via SAF (persistable permission), e.g. `Documents/ActivityTrack/` with `attachments/` and `backup/` |
| Backup | daily: `VACUUM INTO` snapshot, zipped in `backup/`, retention N |
| UI languages | it, en, de, es (per-app language) |
| Online services | Nominatim (reverse geocoding), OSRM (road distance), OSM tiles — each optional, endpoint configurable |
| Speech-to-text | Android `SpeechRecognizer` (on-device when available); offline option Vosk / whisper.cpp |
| OCR | Tesseract (tesseract4android) |

## 2. Master data

- **Company**; **Client** belongs to exactly one company.
- **Company site**: label, coordinates, radius (default 100 m), address. There is
  no generic "office": work happens at a company site, remotely on a company's
  clients, at a client site, or at home.
- **Client site**: label, coordinates, radius (default 100 m), address.
- **Place**: HOME, OTHER, with coordinates and radius.
- **Role**: Teacher, Consultant, System engineer, Presales, … Chosen per entry.
- **Activity preset**: text with optional default role and company.
  The activity is free text with selectable presets. The default activity and
  role are those of the last entry on the same client in the last 30 days.

### 2.1 Start pre-fill

Priority order:
1. position inside a site (nearest wins): client site → company and client;
   company site → company, client picker limited to that company's clients;
2. otherwise the company of the most recent entry started **today** (work is
   normally for one company per day); client picker limited to its clients;
3. otherwise no pre-fill.

All pre-filled fields stay editable. Location kind is COMPANY / CLIENT from the
matched site, HOME from the home place, OTHER otherwise.

## 3. Entries

### 3.1 State machine

```
status : ACTIVE | SUSPENDED | TO_COMPLETE | CLOSED
mode   : WORK | TRAVEL | PAUSE      (only when ACTIVE)
```

| From | Command | To | Segment ops |
|---|---|---|---|
| (new) | start WORK / TRAVEL | ACTIVE | open |
| ACTIVE(m) | WORK / TRAVEL / PAUSE (≠ m) | ACTIVE(new) | close + open |
| ACTIVE(WORK/TRAVEL) | SUSPEND | SUSPENDED | close |
| SUSPENDED | WORK / TRAVEL | ACTIVE | open |
| ACTIVE / SUSPENDED | STOP | TO_COMPLETE | close if open |
| TO_COMPLETE | COMPLETE | CLOSED | — |
| TO_COMPLETE / CLOSED | REOPEN | SUSPENDED | — |

- Minimal start: client only. A travel may start with the client unknown; the
  client is resolved on arrival (site proximity proposal).
- Several entries may be ACTIVE at the same time.
- Starting an entry while another is ACTIVE asks: *close / suspend / leave
  unchanged*. If the other one is in TRAVEL, it asks whether the travel ended.
- Closing an entry, when suspended entries exist, proposes to resume the most
  recent one.
- Pause affects only the entry it is started on.
- STOP offers *close* or *start return travel*; the return travel belongs to
  the same entry and the entry is closed on arrival.
- Completion requires at least one of: activity text, dictation, voice note,
  attachment.
- TRAVEL and PAUSE segments can be added manually afterwards (time and km).

### 3.2 Other entry data

Locality (text, exported), location kind (COMPANY / CLIENT / HOME / OTHER),
notes, external references (n per entry; kind TICKET / URL / UUID / OTHER
auto-detected), expenses (n per entry, EUR, amount + description + optional
attachment), attachments (photo, audio, receipt, file).

Coordinates are stored and shown on a map but **never exported**; exports and
integrations carry only the locality text.

## 4. Reporting rules

### 4.1 Day attribution

If an entry ends no later than **06:00** (configurable) of the day after its
start day, all its time is attributed to the start day. Otherwise every segment
is split at local midnight and each part goes to its own day. Elapsed time is
real (DST transitions are handled).

### 4.2 Per entry and day

- `work = Σ WORK`, `travel = Σ TRAVEL`
- `pause = (last end − first start) − work − travel` — includes explicit pauses
  and suspension gaps; single pauses remain available in the detail view.
- Start and end times are exported exact.

### 4.3 Rounding (configurable per export profile)

| Quantity | Mode | Quantum | Minimum |
|---|---|---|---|
| WORK | CEIL_TOLERANT | 15 min (may become 1, 5, 30, 60) | one quantum |
| TRAVEL | CEIL | 5 min | — |
| PAUSE | FLOOR | 5 min | 5 min |

CEIL_TOLERANT: non-zero durations ≤ q count as q; above that, the remainder r is
dropped when `3·r < q`, otherwise rounded up (tolerance proportional, q/3).
With q = 15: 7 → 15, 19:59 → 15, 20 → 30.

### 4.4 Daily totals

- **Solar**: union of WORK and TRAVEL intervals of all entries attributed to
  the day (concurrent time counted once).
- **Reported**: Σ over entries of rounded WORK + rounded TRAVEL.
- Shown in the day view and in an end-of-day notification.

## 5. Reminders and automation

| Trigger | Behaviour |
|---|---|
| Entry ACTIVE (WORK/TRAVEL) | confirmation every 30 min (configurable), also outside working hours; the entry keeps running if unanswered; max number of notifications configurable |
| Entry in PAUSE | reminder every 15 min, muted during the lunch break (configurable per weekday like working hours, default 12:30–14:00) |
| Movement > 100 m (configurable) during WORK | audible alert on a dedicated high-importance channel; fires only if `distance − accuracy > threshold` on 2 consecutive samples |
| Stationary ≤ 100 m for 15 min during TRAVEL | "Arrived?"; near a client site proposes WORK, near HOME proposes closing |
| No entry 15 min after working-day start | actions: *Absence (full day) / Late entry at… / Start entry* |
| Entries TO_COMPLETE | reminder until completed |

Working hours: several intervals per weekday. Public holidays (Italian national,
Easter Monday computed) and manual days off suppress the day-start prompt.
Absences: FULL_DAY, LATE_ENTRY (time), EARLY_EXIT (time).

## 6. Location

- Foreground service of type `location` only while an entry is ACTIVE.
- One fix every 5 min; `TYPE_SIGNIFICANT_MOTION` triggers an extra fix.
- TRAVEL: fix at start, every 5 min, at stop; km estimated via OSRM routing
  between consecutive fixes, fallback straight-line distance × configurable
  factor. `km_declared` overrides `km_estimated` in exports.
- Uses: locality proposal (Nominatim), company/client proposal (site within radius),
  location kind, travel track and km, movement alerts.

## 7. Quick interaction

Ongoing notification with actions, confirmation notifications with actions,
Glance widgets (4×1 status + recent clients, 1×1 toggle), Quick Settings tile,
dynamic app shortcuts, quick expense (amount, description, photo).

## 8. Export

`Exporter` interface with in-app implementations (no dynamic code loading),
output through SAF. CSV first; XML / JSON plug-ins for management systems later.

## 9. Milestones

| | Scope |
|---|---|
| M0 | Gradle skeleton, license, domain rules with tests, Room schema |
| M1 | master data, entries and state machine UI, pauses, reminders, schedules, rounding, CSV export, SAF directory, daily backup |
| M2 | travel and location, locality, site proposal, movement alerts, day-start prompt |
| M3 | widgets, tile, shortcuts, quick expenses, attachments, dictation |
| M4 | OCR, offline speech-to-text, XML/JSON exporters |
