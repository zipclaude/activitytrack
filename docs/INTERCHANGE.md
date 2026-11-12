# ActivityTrack interchange format (v2)

Generic plugin of M4 (`GenericConnector`): the same model as JSON or XML.
Management-system plugins map their own APIs or files to this model
(`domain/interchange/Interchange.kt`).

Version 2 (activity model 1.0, docs/ACTIVITY-MODEL.md 6.2): the to-do is an
entry with status `TODO` and the planned fields, an absence an entry of kind
`ABSENCE`; the sections `todos` and `absences` and the entry's `todoKey`
are gone. A **version 1** file is still read: its to-dos, entries and
absences go through the same migration function as the database
(`InterchangeUpgrade`, `ActivityMigration`) and arrive as activities (a
to-do's key becomes its external key, its uid the key as a canonical UUID).

## Rules

- Every section is optional: a file may carry only `clients`, only `entries`…
- Records are identified by `key`: the management system's code, or for data
  born in the app the entry uid / the master-data name. References between
  records use keys (`companyKey`, `clientKey`, `ownerKey`). An entry with an
  `externalKey` (an activity of a management system, e.g. a ticket to do) is
  also matched by (company, `externalKey`).
- An imported entry's uid is its key as a canonical UUID (lower case, with
  dashes); a key that is not a UUID gets its name-based UUID (`SyncIds.canonical`),
  so importing the same file again still finds the entry. A running entry is
  not imported (it would run on two devices); deleted activities are not
  exported.
- `companyKey` of a client or an entry may be missing: it belongs to no
  company (app used without companies, policy `fields.company.visible: false`).
- Instants: ISO-8601 UTC (`2026-10-17T07:00:00Z`) with `zone` apart; dates
  `yyyy-mm-dd`; times `HH:MM`; amounts decimal strings in EUR (`"12.50"`).
- Location samples never travel in this format, nor in the sync. Site coordinates are
  optional master data. The only file carrying samples is the explicit GPX / KML track
  export (issue #155), which is not an interchange file and is not imported.
- Import is idempotent (same file twice = no duplicates). When a record
  already exists and differs, **the app data wins** and the import report
  lists the conflict; nothing is blocked.
- Unknown fields are ignored; a newer `version` is refused.

## JSON

```json
{
  "format": "activitytrack-interchange",
  "version": 2,
  "companies": [ { "key": "ACME", "name": "Acme S.p.A." } ],
  "clients": [ { "key": "C-1", "companyKey": "ACME", "name": "Bank" } ],
  "sites": [ { "ownerKind": "client", "ownerKey": "C-1", "label": "HQ", "address": "Via Roma 1", "radiusM": 150 } ],
  "roles": [ { "name": "Sysadmin" } ],
  "presets": [ { "text": "Backup check", "companyKey": "ACME", "roleName": "Sysadmin" } ],
  "entries": [
    { "key": "0d9c2f7e-…", "companyKey": "ACME", "clientKey": "C-1", "externalKey": "T-9",
      "activity": "Patch servers", "status": "TODO", "zone": "Europe/Rome", "priority": 4,
      "refs": [ "INC0012" ], "dueDate": "2026-10-20", "dueTime": "09:00",
      "plannedDate": "2026-10-19", "plannedTime": "14:30", "durationMin": 90,
      "segments": [ { "kind": "WORK", "startUtc": "2026-10-19T12:30:00Z", "endUtc": "2026-10-19T14:00:00Z", "planned": true } ] },
    { "key": "6f1c0f4e-…", "companyKey": "ACME", "clientKey": "C-1",
      "activity": "Patched 3 servers", "status": "CLOSED", "closedUtc": "2026-10-17T11:00:00Z", "zone": "Europe/Rome",
      "segments": [ { "kind": "WORK", "startUtc": "2026-10-17T07:00:00Z", "endUtc": "2026-10-17T11:00:00Z" } ],
      "expenses": [ { "amount": "12.50", "description": "Parking", "createdUtc": "2026-10-17T07:01:00Z", "receipt": true } ],
      "refs": [ "INC0012" ], "tags": [ "remoto" ],
      "materials": [ { "description": "Cavo di rete", "quantity": "2.5", "unit": "m", "unitCost": "1.50" } ] },
    { "key": "9b1e…", "kind": "ABSENCE", "absenceKind": "LEAVE", "status": "CLOSED", "zone": "Europe/Rome",
      "segments": [ { "kind": "ABSENCE", "startUtc": "2026-10-19T07:00:00Z", "endUtc": "2026-10-19T08:30:00Z", "source": "MANUAL" } ] }
  ],
  "daysOff": [ { "date": "2026-12-07", "note": "Sant'Ambrogio" } ]
}
```

## XML

Derived mechanically from the JSON tree: objects become child elements, a
list becomes a plural container with one singular element per item, values
are text. Root element `<activitytrack>`; DTDs are refused.

```xml
<activitytrack>
  <format>activitytrack-interchange</format>
  <version>2</version>
  <clients>
    <client><key>C-1</key><companyKey>ACME</companyKey><name>Bank</name></client>
  </clients>
  <entries>
    <entry>
      <key>0d9c2f7e-…</key><companyKey>ACME</companyKey><clientKey>C-1</clientKey><externalKey>T-9</externalKey>
      <activity>Patch servers</activity><status>TODO</status><zone>Europe/Rome</zone><priority>4</priority>
      <refs><ref>INC0012</ref></refs>
      <dueDate>2026-10-20</dueDate>
      <plannedDate>2026-10-19</plannedDate><plannedTime>14:30</plannedTime><durationMin>90</durationMin>
    </entry>
  </entries>
</activitytrack>
```

List containers and items: companies/company, clients/client, sites/site,
roles/role, presets/preset, entries/entry, segments/segment,
expenses/expense, refs/ref, daysOff/dayOff, tags/tag (version 1 also
todos/todo, absences/absence),
materials/material (issue #41: consumables left with an entry; description
required, quantity, unit and unitCost in EUR optional).

## Values

| Field | Values |
|---|---|
| `status` | TODO, RUNNING, CLOSED (version 1: ACTIVE, SUSPENDED, TO_COMPLETE, CLOSED, read through the migration) |
| `kind` | ACTIVITY (default), ABSENCE (status always CLOSED, segments of kind ABSENCE only) |
| `absenceKind` | ABSENCE, HOLIDAY, LEAVE, SICK; only with kind ABSENCE |
| `priority` | 0 none (default), 1 low, 2 medium, 3 high, 4 urgent, 5 emergency (version 1 to-dos: 1 the highest, converted P1→5 … P5→1) |
| `deletedUtc` / `closedUtc` | ISO instants: in the bin (restorable) / when it was closed |
| `externalKey` | key in the management system; with the company, the match of an imported activity |
| `dueDate` / `dueTime` | the deadline: local date, optional hh:mm |
| `plannedDate` / `plannedTime` / `durationMin` | the plan: local date, optional hh:mm, optional 1..1440 (needs the time) |
| `segments.kind` | WORK, TRAVEL, PAUSE, ABSENCE |
| `segments.source` | TRACKED, MANUAL |
| `segments.planned` | true: a planned segment of a TODO entry, never counted (default false) |
| `entries.tags` | free tags without `#`, lower case, blanks as `-`, letters, digits, `-` and `_` only, at most 30 characters each |
| `locationKind` | COMPANY, CLIENT, HOME, OTHER |
| `sites.ownerKind` | company, client |
| version 1 `todos.*`, `absences.kind` FULL_DAY/LATE_ENTRY/EARLY_EXIT | read only, through the migration (ACTIVITY-MODEL.md 6) |
