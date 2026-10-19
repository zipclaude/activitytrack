<!-- title: Antares connector (Saidea) -->
Version 0.6.0 · guide to the link between ActivityTrack and Saidea's Antares
management system. General use of the app is in the *User manual*.

## 1. What it does

For a company that reports on Antares, the connector:

- downloads the company's **services**, **projects** and **activity types**;
- turns the **tickets assigned to you** into to-dos;
- sends the **closed activities** to Antares, one per day, and updates or
  deletes them when you change them after sending.

The exchange starts only when you tap **Sync**: nothing is sent
automatically.

## 2. Requirements

- An Antares user allowed to report time.
- From Saidea: the **API address**, the **identity provider** address and a
  *public* **Client ID** (native application, no secret), with the
  *Redirect URI* shown in the company screen registered
  (`it.infraconsulting.activitytrack:/oauth2redirect`; for the test app
  ActivityTrackDbg `it.infraconsulting.activitytrack.debug:/oauth2redirect`).
- In ActivityTrack clients, the **management-system code** equal to the
  Antares customer code (used to filter services and projects per client).

## 3. Setup

Company screen → **"Management system"** → connector **Antares (Saidea)**:

| Field | Content | Default |
|---|---|---|
| API URL | address of the public API v3 | demo environment |
| Identity provider | login address | demo environment |
| Client ID | given by Saidea | — |
| Pause splitting the activity | minutes after which a pause splits the activity in two | 60 |

For tests the app proposes the demo environment (`demo.goantares.uno`).

## 4. Login

- **Log in** opens the Antares login page in the browser; after the login the
  browser returns to the app and the screen shows **"Connected as …"**.
- The app keeps no password and no secret: only a refresh token, encrypted
  with the phone's keystore. If it expires or is revoked, just log in again.
- **Log out** deletes the token.

## 5. Sync

**Sync** (company screen) runs in order:

1. The company's active **services and projects**, with their activity
   types; those no longer active can no longer be chosen.
2. Open **tickets** assigned to you → to-dos (title, client, priority, ticket
   code as reference, description in the notes); those closed in Antares are marked done.
3. The company's **closed activities** not sent yet or changed since →
   sent, updated or deleted in Antares.

At the end a **report** shows how many services and projects, tickets and
activities (sent, updated, deleted) and the list of those **not sent**, with
the reason (activity type missing, service or project missing). At the first
API error Sync stops; what was done stays recorded and the next Sync goes on
from there.

## 6. In the activity

In the screen of an activity of an Antares company, the **Antares** section:

- **Service or project** (filtered on the client, when the client has its
  code);
- **Activity type** (those enabled on that service or project);
- **Included in the contract** (proposed by the activity type).

An activity started from a ticket is reported on the ticket: the type is
enough. Service, project and type of the last activity on the same client are
proposed. The section also shows whether and when the activity was sent.

## 7. How an activity is sent

- **One Antares activity per report day** (with the manual's overnight rule):
  start = start of the day's first work segment, end = start plus the length
  **rounded** by the work rule; pauses shorter than the threshold stay inside.
- A **pause longer than the threshold** splits the activity in two blocks.
- **Travel** is no activity of its own: travel minutes and km go to the
  nearest block.
- Antares receives the activity's **description** (report) and **notes**.
- When you change an activity already sent, the next Sync updates it; when a
  block disappears (e.g. a long pause removed), it is deleted in Antares.

## 8. Troubleshooting

| Problem | What to do |
|---|---|
| "Antares login failed" | check Client ID and addresses; Saidea must have registered the Redirect URI |
| The browser does not return to the app after login | Redirect URI not registered or spelled differently |
| No service or project for a client | the client's management-system code does not match the Antares code |
| Activities "not sent" in the report | fill in the activity's Antares section and Sync again |
