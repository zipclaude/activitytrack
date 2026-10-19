Version 0.6.0 (October 2026). Please report any difference between this
manual and the app. The *Quick start* sums up daily use in a few pages; this
manual covers everything: **Part I** how to use the app, **Part II** the
reference (settings, entities, states, flows).

**New in 0.6.0**
- ✕ in text fields to empty them (§3).
- Home cards: band with the company's chosen colour, the "Closed" state is
  neutral, times read "from–to (length)" (§3).
- Activity screen: "+ Travel" and "+ Pause" at the top; with the compact
  display the optional details fold away (§6).
- Start with an **End**: to record a past activity or to set the planned end
  (§4).
- On Stop, an activity with client and description is **closed** at once (§6).
- To-dos: quick filters company, ignored, period (24 h, 7 days, 30 days, all)
  and advanced filters (§8).
- Manual and quick start also as PDF, in Italian and English.

Names in quotes are the labels of the English interface; the app also speaks
Italian, German and Spanish.

---

# Part I — Using the app

## 1. Concepts

**Activity** — a piece of work for a client (or for a company, when
travelling without a known client). It has client, company, role,
description, locality, notes, external references (tickets, URLs, codes),
tags, expenses and attachments.

**Segments** — the time of an activity is made of segments:
- **Work** (green), **Travel** (blue), **Pause** (yellow).
- They are recorded to the minute; rounding and day attribution apply only to
  reports and exports.

**States of an activity**

| State | Meaning |
|---|---|
| Running (work / travel / pause) | time is running |
| Suspended (orange) | interrupted, to be resumed later |
| To complete (red) | stopped, details missing |
| Closed | completed |

Several activities can run at the same time (e.g. a travel and a work call
for another client).

**Solar and reported time**
- *Solar*: the clock time covered by work or travel of any activity
  (overlaps count once).
- *Reported*: the sum of rounded work and travel of all activities.

**Report rules** (Settings → "Reports")
- Work: rounded up to the quarter hour with a 5-minute tolerance (e.g.
  1 h 04 → 1 h; 1 h 05 → 1 h 15); short work counts at least 15 min.
- Travel: rounded up to 5 minutes.
- Pause: rounded down to 5 minutes, at least 5.
- Night: an activity ending by 06:00 the next day stays on its start day;
  later than that, it is split at midnight.

---

## 2. First start

**Installation** — the app in use comes from the repository's GitHub
*Releases* (file `ActivityTrack-<version>.apk`); new versions install over the
old one and keep the data. The phone may also hold **ActivityTrackDbg**, the
test build installed from the PC: a separate app with its own data and folder
(`ActivityTrackDbg`).

1. **Data folder** — the app asks for a folder (`ActivityTrack`) for
   attachments, backups and exports. The files stay even if the app is
   uninstalled. If the folder is moved or renamed the app says so ("Access to
   the data folder was lost") and asks for it again.
2. **Permissions** — notifications and location are asked on first start. In
   Settings → "Location":
   - "Location access" → **"Allow all the time"**: tracking can then restart
     after a reboot or from a notification;
   - "Battery optimisation" → **"Allow"**: on OxygenOS this keeps the system
     from stopping tracking.
3. **Minimum master data** ("More" → "Master data"): at least one company and
   one client. Also useful: "Working hours and lunch break" (for the day-start
   prompt and the evening summary) and a site for frequent clients (the client
   is then proposed automatically).

---

## 3. The bottom bar

| Tab | Content |
|---|---|
| **Home** | the running activity's card (or ▶ Start / 🚗 Travel), small cards of the others, latest completed, "To do · next 24 h" |
| **Agenda** | consultation: list, day, week, month |
| **Report** | one day's report: solar, reported, one line per activity, export |
| **More** | "To-dos", "Import / export", "Master data", "Settings" (with "Gestures") |

From a tab other than Home, Back returns to Home.

### Home cards
- At the top the **big card**: the most recent open activity, with time,
  state and commands. With no open activity it shows "No activity running"
  with **▶ Start** (opens "New activity") and **🚗 Travel** (the travel starts
  at once; the destination is set later or comes with the arrival); dragging
  it left starts, dragging it right starts the travel.
- With open activities, ▶ and 🚗 sit in the top bar (🚗 opens "New activity"
  with Travel selected, because it asks what to do with the others).
- Below, a **small card** for every other open activity (paused, suspended,
  to complete): state and times as "from–to (length)" (e.g.
  "11:00–11:45 (0:45)"), and a button to resume it (or pause it, or complete
  it). Tapping it brings the activity to the big card.
- The **latest completed activities** stay in view (2, or 3 with the compact
  display), dimmed, with the "Closed" state in grey: tapping one opens it.
  Open background activities take their places first.
- Under the **"To do · next 24 h"** separator, one card for every to-do
  planned or due in the next 24 hours (overdue ones included); at the bottom,
  struck through, those ignored today.

![Home: a running activity, the others below, the latest completed and the to-dos](img/home.png)

### Gestures (dragging the cards)
Every card can be dragged left or right (Home, Agenda list, "To-dos"
screen). A short drag reveals the buttons to tap; a long drag runs the first
one at once.

| Card | Left | Right |
|---|---|---|
| Running activity | Pause, or Resume if paused or suspended | Stop (with the same questions as the Stop button) or Suspend |
| To-do | Start | Postpone (if planned) or Ignore |
| Ignored to-do | Restore | Restore |

They are changed in "Settings" → **"Gestures"**: what each direction offers
(also nothing), and whether a long drag runs the first action.

### Company and client colours
In a company or client screen ("More" → "Master data") pick a **colour** out
of twelve, or "No colour". Activity and to-do cards have a thin **vertical
band** on the left with the company colour; when a list shows a single
company, the band has the **client** colour. Without a chosen colour there is
no band; in the Agenda the blocks of those companies are grey.

Next to "To do · next 24 h" (and in the "To-dos" screen) the **"All
companies"** button filters to-dos by company: the filter stays until you
remove it with ✕. Running activities are not filtered. In the "To-dos" screen
the period filter (**24 h**, **7 days**, **30 days**, **All**) shows the
to-dos planned or due within that time (overdue ones always),
**"Ignored"** shows or hides ignored to-dos. **"Filters"** opens the
advanced filters: client, priority, state (open, planned, overdue), planned
yes/no; "Clear" removes them.

### Compact display
Settings → "Display" → **Compact**: slightly smaller text, tighter cards and
buttons, to see more activities and to-dos at once.

### New company or client on the fly
In "New activity" the **+** next to Company, Client, Preset and Role creates
the new item without leaving (in the to-do editor, Company and Client); the
new item is already selected (a preset fills the description).

### Required and optional fields
The field border tells how much it matters: **red** when required and still
empty, **green** once filled (company, client, activity or preset, to-do
title, expense amount and description); **yellow** when important but not
required (role, planned date and time); **blue** when accessory (notes,
references, locality, duration, due date, priority). Drop-down menus show the
colour of companies and clients. In "New activity" the company only filters
the clients: with "All companies" the border is neutral, and it turns green
once a company is picked or comes from the client.

### Dates and times
Dates are picked on the Android calendar. Times on a **5-minute grid**: one
row per hour, 12 cells per row, 12 hours at a time (**▲ Earlier** /
**Later ▼** move the window by 3 hours).
- single time: tap a cell; **"Exact time"** opens the Android clock for
  minutes off the grid (e.g. 09:37); OK without a tap keeps the time as it
  was;
- segments (Edit segment, + Travel, + Pause): tapping Start or End, the grid
  picks both: touch the first cell and drag to the last, or tap the first and
  then the last (also after moving the window). The header shows the range
  and its length; **hatched** cells are taken by other segments of that day.

Optional fields have an ✕ to empty them.

![The 5-minute grid: range 09:15–10:40, the other segments hatched](img/time-grid.png)

### Emptying a field
Text fields (description, notes, names, tags, references, agenda search…)
show an **✕** as soon as they hold text: tap it and the field is emptied. In
the client search boxes the ✕ empties the search and lists everything again;
the chosen client stays until you pick another.

---

## 4. Starting an activity

On Home: **▶ Start** in the "No activity running" card (or drag it left), or
**▶** in the top bar when other activities are open.

In the "New activity" screen:
- **Work / Travel** at the top: a travel may start without a client.
- **Company** filters the clients; **Client** is a box where you type to
  search (case and accents are ignored) or pick from the list, in the order
  chosen in Master data (by default the most recent first).
- Within the radius of a site, client and company are already proposed;
  otherwise the app proposes the company of today's last activity.
- **Description** and **role** are proposed from the last activity on the
  same client in the last 30 days. The microphone in the field dictates.
- **Start** (with "Times freely editable", on by default): tapped while empty
  it opens the range grid, which sets start and end together; when already set,
  the time picker; empty = now; a
  time already passed starts the activity from there (a time that has not
  come yet today means yesterday).
- **End (optional)**: tapping it, the grid picks start and end together.
  - End already passed (to record a past activity): the app asks **"Is the
    activity over?"**. "Yes" records it stopped at that time, as Stop does,
    without asking about the running activities; "No" starts it open.
  - End in the future: the **planned end**, shown on the card. At that time
    the **"… finished?"** notification comes: "Yes, stop at HH:MM" stops it
    there, "No, go on" (or no answer) lets it run.
- The green button at the bottom starts (blue with the car for a travel).

![New activity with start and end](img/start.png)

**If another activity is running**, the app asks what to do with it:
- working: close, suspend or leave it running;
- travelling: "is the travel over?" (yes = stop, no = leave, or suspend);
- paused: close or leave it.

**Other ways to start**
- from the day-start notification ("Start activity");
- from a **to-do** (client, description and references already filled);
- from the 4×1 **widget** or the icon **shortcuts** (recent clients): the
  start screen opens with the client selected.

---

## 5. While working

### Commands
On the Home card and in the notification:

| Icon | Command |
|---|---|
| ▶ green | Work / Resume |
| ⏸ yellow | Pause |
| 🚗 blue | Travel |
| 🌙 orange | Suspend |
| ⏹ red | Stop |

The card shows the time of the current segment in large type and the state
in a coloured label. "Stop" while working asks **"Stop now"** or **"Start
return travel"** (the return travel belongs to the same activity). With
**"Times freely editable"** the question always comes, also when travelling,
with an **End** field: empty = now, otherwise the activity stops (or the
return travel starts) at that time; it cannot be in the future nor before the
start of the current segment. Stop from the notification, the widget or the
tile always stops at once.

### Ongoing notification
One notification per running activity, with a chronometer and commands.
While location is active it cannot be swiped away; otherwise it comes back
when you reopen the app.

### Reminders
- **Confirm** every 30 minutes of work or travel: "Still on …?" with "Yes",
  "Pause" or "Stop at hh:mm" (stops at the last confirmation, if you forgot).
  Without an answer the activity goes on.
- **Pause** every 15 minutes ("Still on pause?"), muted during the lunch
  break.
- **Summary** at the end of working hours: the day's solar and reported time.

### Location (automatic)
- One point every 5 minutes, plus one when the phone moves; never exported.
- **"Away from …?"** — while working, beyond 100 m: "Travel since hh:mm"
  (the travel starts when you left), "Ignore", "Stop at hh:mm".
- **"Arrived?"** — while travelling, after 15 minutes standing within 100 m:
  at a client's site it proposes "Work for … since hh:mm" (it assigns that
  client to the travel if it had none); at home it proposes to stop;
  otherwise "Work since hh:mm" or "Still travelling".
- Travel km are road distances (OSRM) and the locality comes from the address
  service (Nominatim), when the network is available.

Tap the notification **buttons**, not the text: the text opens the app and
closes the notification without running the action. If the buttons are not
visible, drag the notification down to expand it.

### Expenses
In the activity screen, "Expenses" → "Add expense": amount in euro,
description, optional "Receipt photo". Quick expense: the **€** button of the
widget or the **"Expense"** icon shortcut (goes to the most recent running
activity).

### Attachments
"Attachments": "Photo" (camera), "Voice note" (records until "Stop and
save"), "File" (any document). Tapping an attachment: "Open" or "Delete".
Files live in the data folder, under `attachments/<year>/<activity>/`.

### Calls with clients
With recognition on (Settings → **Calls**, incoming and outgoing
separately), at the end of every call with a number listed in Master data →
**Contacts** the app reads the call log and:
- records the call (shown in the client screen, **"Calls"** section, with its
  length);
- while you are working for **the same client**, does nothing else: the call
  is part of the activity;
- while you are working for **another client**, **pauses** that activity for
  the length of the call, with the description "Phone call CLIENT", and
  resumes it at the end ("Pause …" option); a call under a minute makes no
  pause;
- except for the same client, proposes with a notification to **record the
  call as an activity** ("Propose …" option): "Create activity" opens "New
  activity" with the client, the description "Phone call with NAME" and the
  times rounded to 5 minutes (start down, end up).

It works only within working hours (not on holidays and days off), unless
**"Also outside working hours (on call)"** is on: then it always works.
Calls with unknown numbers are ignored.

---

## 6. Stopping and completing

Stopping from the app an activity that lasted **less than a minute**
(without attachments or expenses) is taken as a mistake: the app asks whether
to **discard** it or keep it and stop it as usual.

After Stop, when client and description are already filled the activity is
**closed** at once; otherwise it is **to complete** (persistent notification
and red card). Activities started from a to-do always go through "to
complete", for the question about the to-do. In the activity screen:
- "+ Travel" and "+ Pause" at the top add forgotten segments (also with km;
  at least one minute);
- check client, description (can be dictated), role, locality, place, notes;
  with the compact display locality, place and notes fold under "More details
  ▾" while empty;
- "Times": with "Times freely editable" on, tapping a closed segment opens
  **"Edit segment"**: date, start, end and, for travels, km; **"Delete"**
  removes it (not the running one nor the only one left). The new values
  replace the recorded ones;
- **pauses** have an optional **description** (e.g. "Phone call CLIENT" for
  those made by a call; empty for manual ones): tap a pause to edit or clear
  it, also with "Times freely editable" off;
- **Tags**: free labels (e.g. `#remote`, `#urgent`); type one or more and tap
  "+", or tap a suggestion among those already used; the ✕ on a tag removes
  it. They can be added after closing too;
- attachments (**Photo**, **Voice note**, **File**) and **Expense** have
  coloured icon buttons;
- "External references": tickets, URLs or codes (recognised automatically);
- **"Complete"** (green).

![An activity](img/entry.png)

Without a description the app asks: **"Leave empty"**, **"Copy the last:
…"** (the last description on the same client) or **"Back to editing"**. If
the activity started from a to-do, it asks **"Is the to-do done?"** ("Yes"
marks it done, "No, I'll come back" leaves it open).

"Reopen" brings a closed or to-complete activity back to suspended.

---

## 7. Consulting: Agenda and Report

### Agenda
At the top the modes and the period ("‹ ›" to scroll, "Today" to come back;
on the right the period's reported total).
- **List** — the month, day by day, with a search on client, description or
  tag (`remote` or `#remote`); tags follow the description; planned or due
  to-dos show with ☐.
- **Day** — a timeline: every activity is a block from start to end;
  concurrent activities side by side; planned to-dos are blocks with a
  coloured border.
- **Week** — seven columns from Monday; tapping a day opens the Day view.
- In Day and Week the timeline covers the working hours and opens on the
  running activity (or the current time); **"Fit"** fits all working hours on
  one screen, **"Scroll"** goes back to the normal scale.
- **Month** — a grid with reported hours per day, one dot per company and one
  for to-dos; tapping a day opens the Day view.

![Agenda: Day view](img/agenda-day.png)

**Tag filter**: the "All tags ▾" chip next to the modes (shown when the
period has tagged activities) shows only the activities with that tag;
to-dos are hidden and totals count only what is shown.

Every company has its colour. Tapping an activity opens it; tapping a to-do
opens its actions (start, postpone, done, re-plan).

**Planning from the agenda**: in Day and Week tap a free slot: a new to-do
planned at that time opens (half-hour steps). "Add" at the top does the same
for the day shown (today: the next half hour; other days: 09:00).

### Report
![The day's report](img/report.png)

One day's report: solar and reported totals, one line per activity with
exact → rounded work, travel and pause; export starts from here too. With the
tag filter only the activities with that tag remain: reported is their sum,
"solar" the sum of their exact work and travel. Export has a **Tag** field
too (only the activities with that tag; the other interchange sections are
not filtered).

---

## 8. To-dos

To-dos, imported from a management system or created by hand ("More" →
"To-dos" → "Add", or from the Agenda): title, client (or company), **planned
date and time**, **duration**, due date and time, priority (1 = highest),
notes, references (one per line).

![To-dos with the quick filters](img/todos.png)

States: **open** (no plan) → **planned** (date and time) → **done**. The plan
is separate from the due date: re-planning does not change the management
system's due date.
- **Duration**: when given, the block lasts that long; left empty it lasts an
  hour and, while the to-do stays open, grows hour by hour (up to the end of
  the day).
- **Reminder**: 15 minutes before the planned time the "Title at HH:MM"
  notification comes, with "Start activity", "Postpone 30 min" (moves the
  plan 30 minutes from the planned time or, if that has passed, from now; the
  reminder comes again before the new time) and "Re-plan" (opens the to-do).
  It also comes outside working hours and on days off; it goes away when the
  to-do is started, done, re-planned or its block is over.
- On Home, **"To do · next 24 h"** shows those due or planned in the next 24
  hours, overdue ones included; "overdue" marks those still open after their
  planned block or after the due date (after the due time, or from the next
  day when the due date has no time).
- **Ignore** a to-do (gesture or "Ignore") instead of deleting it: it stays at
  the bottom of the "To-dos" list, struck through, without reminders or
  calendar event; on Home it shows only on the day you ignored it. Another
  drag restores it. An import never restores it.
- Tapping a to-do: "Start activity", "Postpone", "Mark as done" / "Mark as to
  do", "Re-plan" (or "Edit" when not planned).
- A to-do may have several activities (e.g. a job over several days).

---

## 9. Day start, absences, days off

- 15 minutes after working hours begin, if nothing was started, **"No
  activity today"** comes: "Absent today", "Late entry…" (asks the time) or
  "Start activity".
- It does not come on **national holidays** (Easter Monday included, 4
  October from 2026) nor on **days off** ("Master data" → "Days off": date and
  note, e.g. the patron saint or holidays).

---

## 10. Master data

- **Companies** — in the company screen: **"Management system"** (code in the
  management system, connector; for now the generic file one) and **"This
  company's calendar"**; below, the company's **sites**.
- **Clients** — belong to a company (it cannot change after the first
  activity); they have their own sites and, at the bottom of the screen, the
  **calls** recorded with their contacts.
- **Sites** — label, coordinates, radius, address. Coordinates from "Current
  position" or "Map" (move the map under the crosshair and "Use this point").
- **Roles**, **Presets** (recurring descriptions, with a proposed role).
- **List order** — at the top of Companies, Clients, Roles and Presets pick
  **A–Z**, **Last used** (most recently used first; for presets, the last
  time you picked them) or **Manual**: in manual order every row has a ≡
  handle on the right; hold it and drag the row where you want it (clients
  move within their company). New items go to the end. The chosen order also
  applies to the drop-down menus (e.g. "New activity"); archived items always
  stay last. By default: clients by last use, the rest alphabetical.

![Manual order: the ≡ handle drags the row](img/order.png)

- **Contacts** — the clients' people: name, client and one or more numbers
  (one per line). **"Import from the phone book"** picks a number from the
  phone's contacts and proposes it as a new contact. They drive call
  recognition (§5); numbers match with or without the international prefix.
- **Places** — home and other places (home ends a return travel).
- **Working hours and lunch break** — per weekday.
- **Days off**.

---

## 11. Import / export, backup

"More" → "Import / export".

**Export**
- **CSV**: one line per activity and day, rounded; needs a period (at most a
  year).
- **JSON / XML** (generic format, `docs/INTERCHANGE.md`): pick the content
  (master data, to-dos, activities, absences and days off), the period (empty
  = everything), company and client; "Site coordinates" only when needed.
- **Tag** (when there are tagged activities): only the activities with that
  tag, in CSV and in the interchange (master data and the other sections stay
  whole).
- "Export" saves into the `export` folder; "Export and share" also opens the
  Share menu (email, Drive…).

**Import** — a generic JSON or XML file (also partial, e.g. only clients or
only to-dos). The report shows what was added, what was already there, the
**conflicts** (the app's data stays unchanged) and what was not imported,
with the reason. Importing the same file again creates no duplicates.

**Backup** — every day the app saves the database into the `backup` folder
(14 kept, adjustable); "Back up now" in Settings. **"Restore backup"**
replaces all data with a chosen backup: first it saves the current data as
`…-before-restore.zip`, then it restarts the app.

---

## 12. Calendar

Settings → "Calendar":
1. allow calendar access;
2. "Activities calendar": a phone calendar (Google, or CalDAV through DAVx5)
   or "New ActivityTrack calendar on the phone";
3. a company can have its own calendar (company screen), which replaces the
   generic one.

Every activity becomes one event per day, from its start: title "Client –
Description", the description holds rounded times, km, references and notes.
Planned open to-dos become "tentative" events (Client – Title) up to a year
ahead; once done they leave the calendar. The description's last line carries
hashtags per kind of time: `#lavoro`, `#viaggio`, `#pausa`, `#pianificata`,
always in Italian whatever the phone language (search them in the calendar
app), followed by the activity's tags. Events are **private**: people sharing
the calendar (e.g. a shared Google calendar) see only "busy", without title or
details. Sync goes one way, from the app to the calendar: changes made in the
calendar are overwritten. The last 14 days update by themselves; "Sync 90
days" goes further back.

---

## 13. Widgets, tile, shortcuts

- **"Activity status" widget (4×1)** — the running activity with commands
  and "€"; with none, the recent clients and the start button.
- **"Pause / resume" widget (1×1)** — pauses or resumes the most recent
  activity; with none, opens the start screen.
- **Quick Settings tile** — same as the 1×1 widget. On OxygenOS add it from
  Settings → "Quick Settings tile" → "Add".
- **Shortcuts** (long press on the icon) — the 4 most recent clients and
  "Expense".

---

## 14. Settings

Settings are split into sections, each with its own page and "Save";
**"Gestures"** is at the end of the list. Every item is explained in Part II,
§17.

![Settings](img/settings.png)

| Section | Item | Default |
|---|---|---|
| Display | Normal / Compact | Normal |
| Times | Times freely editable: start and end chosen when starting and stopping, segments editable and deletable (off: times as recorded; "+ Travel" and "+ Pause" stay) | on |
| Calls | Recognise incoming / outgoing calls | off |
| | Pause the activity of another client / Propose an activity | on |
| | Also outside working hours (on call) | off |
| Reminders | Confirm interval | 30 min |
| | Maximum confirmations | 0 (unlimited) |
| | Pause reminder interval | 15 min |
| | Day-start prompt delay | 15 min |
| Notifications | for every kind (confirm, pause, planned, movement, arrival, day start, summary, to complete, running) opens the Android page for banner, sound and vibration | — |
| Location | access, battery, quick tile | — |
| | Movement alert threshold | 100 m |
| | Arrival radius / time | 100 m / 15 min |
| | Road distance factor (without OSRM) | 1.25 |
| Calendar | activities calendar, sync 90 days | none |
| Dictation | Dictation language | Italian |
| Reports | night activities until | 06:00 |
| | work / travel / pause rounding | §1 |
| Online services | Nominatim, OSRM, OpenStreetMap tiles (switchable, with endpoint) | on |
| Storage | data folder, backups kept, back up now | 14 |
| Advanced | Planned to-do reminder (min before) | 15 |
| | Postpone (min) | 30 |

Out-of-range values are adjusted when saving.

---

## 15. Data and privacy

- Everything stays on the phone and in the data folder you chose.
- The **coordinates** of your movements are never exported nor sent to the
  calendar; only the locality (e.g. "Milano").
- Calendar events are private: people sharing the calendar see only "busy".
- Online services (addresses, distances, maps) receive only the coordinates
  of single points; they can be switched off or pointed to your own servers.
- Management-system credentials are encrypted with the phone's keystore.
- With call recognition the app reads the call log on the phone and keeps
  only the calls with your contacts; it does not read the phone book (the
  import uses the number you pick).

---

## 16. Troubleshooting

| Problem | Solution |
|---|---|
| Tracking stops | Settings → Location: "Allow all the time" and battery "Unrestricted" |
| The activity notification is gone | reopen the app: it comes back |
| "Access to the data folder was lost" | the folder was moved or renamed: pick it again |
| Dictation: "language not available" | Settings → Dictation: pick the language; for offline use download the language pack in the phone's voice settings |
| The quick tile is not in the list | Settings → "Quick Settings tile" → "Add" |
| A notification action does nothing | tap the button, not the text; expand the notification if the buttons are hidden |
| Import: "Not imported" | the report gives the reason (e.g. unknown company, activity running on another phone) |

# Part II — Reference

## 17. Settings in detail

Every section has its own "Save". Values outside the allowed range are moved
to the nearest limit.

### Display

| Item | Effect | Default |
|---|---|---|
| Normal / Compact | Compact: text at 88 %, tighter cards and buttons; in the activity screen the empty optional details (locality, place, notes) fold under "More details ▾". | Normal |

### Times

| Item | Effect | Default |
|---|---|---|
| Times freely editable | On: **Start** and **End** fields when starting, an **End** field in the Stop question, segments editable and deletable (new values replace the recorded ones). Off: times stay as recorded, segments cannot be touched; "+ Travel" and "+ Pause" stay available. It may later be set by a policy. | on |

### Calls

| Item | Effect | Default |
|---|---|---|
| Recognise incoming calls | calls received from contacts are recorded and handled (§5); switching it on asks for access to the phone state and the call log | off |
| Recognise outgoing calls | the same for calls you make | off |
| Pause the running activity of another client | during the call, another client's running activity is paused (at least a minute) | on |
| Propose an activity after a call | "Create activity" notification with client, description and rounded times | on |
| Also outside working hours (on call) | off: within working hours only, not on holidays and days off; on: always | off |

### Reminders

| Item | Effect | Range | Default |
|---|---|---|---|
| Confirm interval | how many minutes of work or travel between "Still on …?" | 10–240 min | 30 |
| Maximum confirmations | after how many unanswered confirmations the app stops asking (0 = never) | 0–100 | 0 |
| Pause reminder interval | how many minutes of pause between "Still on pause?"; muted during lunch | 10–120 min | 15 |
| Day-start prompt delay | minutes after working hours begin before "No activity today" | 0–240 min | 15 |

### Notifications
For every kind of notification (confirm, pause, planned, movement, arrival,
day start, summary, to complete, running) it opens the Android page where you
choose banner, sound and vibration.

### Location

| Item | Effect | Range | Default |
|---|---|---|---|
| Location access | "Allow all the time" lets tracking restart by itself | — | — |
| Battery optimisation | "Unrestricted" keeps OxygenOS from stopping the service | — | — |
| Quick Settings tile | adds the Pause / resume tile | — | — |
| Movement alert threshold | distance beyond which, while working, "Away from …?" comes | 50–5000 m | 100 |
| Arrival radius | within how many metres you count as standing | 25–1000 m | 100 |
| Arrival time | after how many minutes standing, while travelling, "Arrived?" comes | 5–120 min | 15 |
| Road distance factor | road km = straight line × factor, when OSRM is not available | 1.0–3.0 | 1.25 |

### Calendar
Calendar access, the activities calendar (Google or CalDAV through DAVx5, or
"New ActivityTrack calendar on the phone") and "Sync 90 days". A company's
calendar is chosen in its screen and wins over the generic one.

### Dictation
Speech recognition language (Italian, English, German, Spanish), independent
of the phone language. Default: Italian.

### Reports

| Item | Effect | Default |
|---|---|---|
| Night activities until | an activity ending by this time the next day stays on its start day (0:00–12:00) | 06:00 |
| Work rounding | mode, quantum (1, 5, 15, 30 or 60 min) and minimum | up with tolerance, 15 min |
| Travel rounding | same | up, 5 min |
| Pause rounding | same | down, 5 min, minimum 5 |

Rounding modes:
- **up with tolerance**: rounds up only beyond a third of the quantum (15
  min: up to 5 minutes past the quarter it rounds down);
- **up**: to the next quantum;
- **down**: to the previous quantum.

### Online services
Nominatim (locality from addresses), OSRM (road km) and OpenStreetMap tiles:
each can be switched on or off and pointed to your own server. Default: on,
public servers.

### Storage
Data folder, daily backups kept (1–365, default 14), "Back up now".

### Advanced

| Item | Range | Default |
|---|---|---|
| Planned to-do reminder (minutes before) | 0–240 | 15 |
| Postpone (minutes) | 10–240 | 30 |

### Gestures
For activities and to-dos, what a left and a right drag offer (also nothing)
and whether a long drag runs the first action at once. Defaults: activities
left Pause / Resume, right Stop or Suspend; to-dos left Start, right Postpone
or Ignore; long drag on.

### Settings kept by the screens
The to-do company filter, the period (24 h, 7 days, 30 days, all), "Ignored"
and the order of every master-data list are set in the screens themselves and
stay until you change them.

## 18. Entities and relations

![Entities and relations](diagrams/entities.svg)

| Entity | Content | Relations |
|---|---|---|
| Company | name, colour, management-system code, connector, calendar | has clients, sites, to-dos, presets |
| Client | name, colour, code | belongs to a company (fixed after the first activity); has sites |
| Site | label, coordinates, radius, address | of a company or a client; used to propose client and company and to detect arrival |
| Role | name | proposed by presets, chosen on the activity |
| Preset | text, proposed role, company (optional) | fills the description |
| Activity | client, company, role, description, locality, place, notes, state, planned end | has segments, expenses, attachments, references, tags; may come from a to-do |
| Segment | kind (work, travel, pause), start, end, km, source (tracked or by hand), description (pauses) | of an activity; location samples belong to segments |
| Expense | amount, description, receipt | of an activity |
| Attachment | photo, voice note, file | of an activity, in the data folder |
| External reference | ticket, URL, code | of an activity |
| Tag | free word | of an activity |
| Contact | name, numbers | of a client; recognises calls |
| Call | contact, direction, start, end | of a contact and its client |
| To-do | title, client, plan, duration, due date, priority, notes, references, state | of a company (and a client); gives activities |
| Place | home or other, coordinates | home ends return travels |
| Working hours, lunch break, days off, absences | per day | drive reminders and summaries |

## 19. Activity states

![Activity states](diagrams/entry-states.svg)

| State | Entered with | Left with |
|---|---|---|
| Running | start; Work or Travel from suspended | Suspend, Stop, mode change |
| Suspended | Suspend (not from pause); Reopen | Work / Travel (resumes), Stop |
| To complete | Stop (details missing, or started from a to-do) | Complete, Reopen |
| Closed | Complete; Stop with client and description | Reopen |

While running an activity has a **mode**: every change closes the open
segment and opens a new one.

![Modes of a running activity](diagrams/modes.svg)

Rules:
- an activity never starts in pause; it cannot be suspended from pause;
- several activities can run together, each with its own pauses;
- an activity under a minute, stopped from the app, can be discarded;
- a segment added or edited lasts at least a minute, lies in the past and
  does not overlap the activity's other segments.

## 20. To-do states

![To-do states](diagrams/todo-states.svg)

A to-do is **overdue** when its planned block is over or its due date has
passed (after the due time, or from the next day when the due date has no
time). An ignored to-do gets no reminders, stays out of the calendar and an
import never restores it.

## 21. Flows

### Start
1. Company and client proposed: from the site you are at (within its
   radius), otherwise the company of today's last activity; a client picked
   from the widget, a shortcut or a to-do wins.
2. Description and role: from the last activity on the same client in the
   last 30 days, until you change them.
3. With "Times freely editable": Start (empty = now) and optional End. End
   already passed → "Is the activity over?"; future end → planned end with a
   "… finished?" notification at that time.
4. Running activities: for each the app asks what to do (close, suspend,
   leave; travelling "is the travel over?"). A past activity recorded with an
   End asks nothing.

### Stop and completion

![Stop and completion](diagrams/stop-flow.svg)

Stop from the notification, the widget or the quick tile stops at once,
without questions; the same check (closed or to complete) applies there too.

### Reminders
One alarm at a time, set on the nearest reminder; confirm and pause reminders
never go below 10 minutes (Android limits alarms in Doze).

| Reminder | When | Answers |
|---|---|---|
| Confirm | every *confirm interval* of work or travel, also outside working hours | Yes, Pause, Stop at (last confirmation) |
| Pause | every *pause interval* while paused; muted at lunch | Resume, Stop |
| Planned end | at the end chosen when starting, once | Yes, stop at HH:MM; No, go on |
| Day start | *delay* after working hours begin, on working days that are not off, if nothing happened | Absent today, Late entry, Start |
| Summary | at the end of working hours | — |
| Planned to-do | *minutes before* the plan, also outside working hours | Start, Postpone, Re-plan |

### Location
- One point every 5 minutes, plus one when the phone moves, only while an
  activity is running (also paused).
- Working, beyond the *movement threshold*: "Away from …?" with "Travel since
  HH:MM" (from the first point beyond the threshold).
- Travelling, standing within the *radius* for the *arrival time*:
  "Arrived?"; at a client's site it proposes work for that client, at home to
  stop.
- Km: OSRM when on, otherwise straight line × factor; locality from
  Nominatim. Coordinates never leave the phone.

### Report and export
- *Solar*: the time covered by work or travel of any activity (overlaps once);
  *reported*: the sum of rounded work and travel.
- An activity across midnight stays on its start day if it ends by the
  *night activities until* time, otherwise it is split at midnight.
- CSV: one line per activity and day, with start, end, rounded work, pause and
  travel hours, company, client, role, description, km, expenses, notes,
  references, locality, tags; km and expenses on the first day.

### Calendar
One event per activity and day, from its start (a running activity ends
"now"). Every change re-syncs the last 14 days; "Sync 90 days" goes further
back. Planned open to-dos are tentative events up to a year ahead. All events
are private. Sync goes only from the app to the calendar.

### Import and export
The generic format (JSON or XML) holds master data, to-dos, activities with
segments, expenses, references and tags, absences and days off. Import
recognises what it already has (codes and identifiers), creates no duplicates
and on a conflict keeps the app's data, listing the conflict in the report.
Activities running on another phone are not imported.

### Backup and restore
Every day a copy of the database goes into the `backup` folder (zip). Restore
first saves the current data as `…-before-restore.zip`, then replaces the
database and restarts the app.
