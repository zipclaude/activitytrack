# Writing an integration plugin

A practical guide to connecting ActivityTrack to a management system with a
declarative plugin. The complete format reference is
[`docs/PLUGINS.md`](../docs/PLUGINS.md); the example used here, checked at
every build, is
[`docs/samples/plugins/timesheet-example.json`](../docs/samples/plugins/timesheet-example.json);
a real plugin is [`antares.json`](antares.json).

## 1. What a plugin is

A plugin is **a JSON file**, no code: it describes the management system's
API and how its data matches the app's. The app supplies the building
blocks (authentication, paging, selectors, a closed library of transforms,
groupings for sending) and checks the whole file when it loads it: an
error is reported with path and line.

With a plugin you can:

- **download** lists (services, projects, activity types, premises…),
  clients, contacts and to-dos (tickets, tasks);
- **add fields** to the activity screen (e.g. the project);
- **send** closed activities, update them when they change and delete them
  when they vanish or are deleted.

What you need before starting:

- the API documentation (addresses, authentication, response format,
  paging);
- a test environment of the management system: sending creates real data;
- the app installed, with its data folder chosen.

## 2. The skeleton

```json
{
	"format": "activitytrack-plugin",
	"schema": 1,
	"id": "timesheet-example",
	"name": "Timesheet (example)",
	"version": "1"
}
```

`id` is the file name without `.json`. Every other section is optional:
add them one at a time, testing each step.

## 3. Step by step

**Options** (`options`): they become the integration's card in the company
screen → "Management system". Types: `string`, `url` (https only), `int`,
`bool`, `choice`, `secret`. A `secret` is encrypted on the phone and never
appears in the file or in exports: **never credentials in the plugin**.

**Authentication** (`auth`): `none`, `basic`, `apiKey` (in a header or the
query), `bearer`, `oauth2Pkce` (login in the browser, like Antares; the app
keeps only the refresh token, encrypted). Example:
`{ "type": "apiKey", "header": "X-Api-Key", "key": "@option.apiKey" }`.

**HTTP** (`http`): base address (usually an option), headers, timeout and
paging style (`none`, `odata`, `page`, `cursor`, `link`). When the response
is not a list itself, `items` says where the list is (`"items": "$.items"`).

**Values.** Wherever a value is expected you can write:

- a literal (`2`, `true`, `"text"`);
- a **selector** on the remote record (`"$.name"`, `"$.customer.code"`);
- a **reference** to app data (`@entry.activity`, `@field.project`,
  `@option.apiUrl`, `@identity.userId`, `@block.from`);
- a **transform** from the library (`coalesce`, `concat`, `if`, `map`,
  `date`, `plainText`, …; list in `PLUGINS.md` §5).

Inside a path use `${…}`: `"/timesheets/${remote.id}"`.

**Identity and lists.**
- `identity` reads who the logged-in user is (to filter their tasks).
- `lookups` reads small tables of states.
- `lists` keeps the lists for the activity screen's menus. A list may have
  a `parent` (the activity types of each service) and its sources a `when`
  (premises for services only).

**Import** (`import`): `clients`, `contacts`, `todos`.
- `key` is the external code: downloading again creates no duplicates, and
  on a conflict the changes made in the app win.
- `skipNew` drops closed records the app does not have yet.
- `fields` pre-fills the plugin's fields of the activities started from
  that to-do.

**Activity fields** (`entryFields`): a list (`list`, with `parent` and
`filter`) or a plain value (`type: "bool"`). A field may be `required` or
`hidden`, also depending on an expression (`{ "eq": ["@todo", null] }`).
When a required field is missing the activity is not sent, and the report
says which field is missing.

**Sending** (`export`):
- `group.by` decides how many records to send: one per activity (`entry`),
  per day (`day`), per work block (`dayBlock`) or per segment (`segment`).
  Travel goes to the nearest block (`nearestBlock`), to a record of its own
  (`separate`) or is not sent (`ignore`).
- `body` is the JSON body. Keys whose value is `null` are left out.
- `create`, `update` and `delete` are the calls. The app remembers the
  remote id and the hash of the body sent: what does not change is not sent
  again.

**Schedule** (`schedule`): send on close (ask / always / never, to-dos from
the management system only or all, full sync or the activity only) and the
periodic download (15 minutes to one day).

## 4. Testing the plugin

1. Copy the file to `plugins/` in the app's data folder (beside `backup`).
   The app reads it at start and whenever it comes back to the foreground.
2. On an error a **"Plugin … refused"** notification gives path and line:
   fix the file and reopen the app.
3. Company screen → **Management system** → **Connector**: pick the plugin
   and fill in the options (those with `*` are required).
4. Start with the download: **Sync now** and read the report (lists,
   clients, to-dos).
5. Only then add `export` and test sending **on a test environment**: create
   an activity, close it, send it, then change and delete it, and check in
   the management system that the record is updated and then deleted.

## 5. Signature

With a signed policy in force every plugin must be signed with the same
name (CN) too:

```sh
scripts/sign-policy.sh antares.json key.pem cert.pem [chain.pem]
```

The script writes `antares.json.sig`, to be copied beside the plugin. Sign
the final file: any later change, even a space, breaks the signature.

## 6. Publishing

Published plugins live in the `plugins/` folder of this repository, with a
line in the table of [`README.md`](README.md). Do not include confidential
documentation of the management system (the repository is public).
