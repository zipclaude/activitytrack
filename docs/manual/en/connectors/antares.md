<!-- title: Antares (Saidea) integration -->
Version 0.7.1 · how to connect ActivityTrack with Saidea's Antares
management system. General use of the app is in the *User manual*.

## 1. What it does

For a company that reports in Antares, the integration:

- downloads **services**, **projects** and their **activity types**, and
  the **clients**;
- turns the **tickets** where you are the **technician** or the
  **applicant** into to-dos;
- sends the **closed activities** to Antares, and updates or deletes them if
  you change them after sending.

Sending starts with **Sync now** or, if you choose so, when an activity
closes; updates are also downloaded automatically (§5).

## 2. Requirements

- An Antares user allowed to report time.
- The plugin file `antares.json`, from the `plugins` folder of the
  ActivityTrack repository: copy it into the **`plugins`** folder of the
  app's data folder (beside `backup`). The app reads it at start and
  whenever it comes back to the foreground.
- From Saidea: the API and login addresses (the app proposes the demo
  environment `demo.goantares.uno`) and the public **Client ID**,
  `activitytrack`, already proposed by the app.

## 3. Setup

Company screen → **"Management system"** → **Connector**: **Antares
(Saidea)**. The plugin's options show; those with `*` are required and,
while missing, are listed in red.

| Option | Content | Default |
|---|---|---|
| API URL `*` | address of the public API v3 | demo environment |
| Identity provider `*` | login address | demo environment |
| Client ID `*` | Saidea's public client | `activitytrack` |
| Pause splitting the activity (min) | a longer pause splits the activity in two | 60 |
| Send on close | Ask / Always / Never | Ask |
| Entries sent on close | Tickets only / All | Tickets only |
| On close | Full sync / The entry only | full |
| Background download | 15 minutes to 1 day | 1 hour |

## 4. Login

- **Log in** opens the Antares login page in the browser; after the login
  the browser comes back to the app with "Logged in to Antares (Saidea)"
  and the card shows **Log out · Logged in**.
- The app keeps no password: only a refresh token, encrypted with the
  phone's keystore. If it expires or is revoked, just log in again.
- **Log out** deletes the token.

## 5. Sync

**Sync now** (company screen) runs, in order:

1. who you are in Antares (the person linked to your user);
2. active **services and projects** and their **activity types**;
3. **clients**: new ones are created in the company with their Antares
   code; existing ones stay as you keep them;
4. **tickets** where you are the technician or the applicant → to-dos
   (title, client, description as plain text in the notes, priority, ticket
   code in the references); those closed in Antares become done, and a
   to-do you marked done is never reopened;
5. **closed activities** with their Antares data → created, updated or
   deleted.

At the end a **report** shows what was downloaded and sent and the
activities **not sent**, with the missing field. At the first API error the
sync stops: what was done stays and the next one carries on. Repeating it
creates no duplicates.

### Automatic updates
With the network available, at the chosen interval (at least 15 minutes,
Android's limit) the app downloads services, clients and tickets **without
sending anything**. Every new ticket arrives as a notification (more than
three: one notification with their number).

### When an activity closes
When an activity of the company closes, as the options say:
- **Ask**: with the app open, "Send to Antares (Saidea)?"; with the app in
  the background, a notification with **Send**;
- **Always**: it is sent at once; **Never**: nothing, it goes with the sync.

Without network the send waits **queued** and leaves as soon as it is back;
on errors it retries at growing intervals and finally reports it. The
notifications use the **"Management systems"** channel.

## 6. In the activity

The activity screen of the company shows the **Antares (Saidea)** section:

- **Service or project**, filtered on the activity's client (hidden when the
  activity comes from a ticket: the ticket's one counts);
- **Activity type**, among those of the service or project (required);
- **Included in the contract**, proposed by the activity type.

## 7. How an activity is sent

- **One Antares activity per day** (with the manual's night rule): start =
  start of the day's first work segment, end = start plus the length
  **rounded** with the work rule; pauses shorter than the threshold stay
  inside.
- A **pause longer than the threshold** splits the activity in two.
- **Travel** is not an activity of its own: its minutes and km go to the
  nearest block.
- Antares receives the **description** (report), the **materials** (after
  the description) and the **notes**; for a ticket also the client and its
  premises.
- If you change an activity already sent, the next sync updates it (without
  overwriting changes made meanwhile in Antares); a block that vanishes is
  deleted in Antares.

## 8. Troubleshooting

| Problem | What to do |
|---|---|
| The Antares connector is not listed | copy `antares.json` into the data folder's `plugins` folder and reopen the app; with a signed policy the plugin must be signed too |
| "Login failed" | check the addresses and the Client ID |
| The browser does not come back to the app after the login | Saidea's client does not know the app's return address: tell Saidea |
| No tickets | tickets arrive only where you are the technician or the applicant |
| Activities "not sent" in the report | pick the activity type (and the service, unless it comes from a ticket) and sync again |
| Updates arrive late | Android defers periodic work in standby; **Sync now** downloads at once |
