# ActivityTrack interchange format (v1)

Generic plugin of M4 (`GenericConnector`): the same model as JSON or XML.
Management-system plugins map their own APIs or files to this model
(`domain/interchange/Interchange.kt`).

## Rules

- Every section is optional: a file may carry only `clients`, only `todos`…
- Records are identified by `key`: the management system's code, or for data
  born in the app the entry uid / the master-data name. References between
  records use keys (`companyKey`, `clientKey`, `todoKey`, `ownerKey`).
- Instants: ISO-8601 UTC (`2026-10-17T07:00:00Z`) with `zone` apart; dates
  `yyyy-mm-dd`; times `HH:MM`; amounts decimal strings in EUR (`"12.50"`).
- Location samples never travel. Site coordinates are optional master data.
- Import is idempotent (same file twice = no duplicates). When a record
  already exists and differs, **the app data wins** and the import report
  lists the conflict; nothing is blocked.
- Unknown fields are ignored; a newer `version` is refused.

## JSON

```json
{
  "format": "activitytrack-interchange",
  "version": 1,
  "companies": [ { "key": "ACME", "name": "Acme S.p.A." } ],
  "clients": [ { "key": "C-1", "companyKey": "ACME", "name": "Bank" } ],
  "sites": [ { "ownerKind": "client", "ownerKey": "C-1", "label": "HQ", "address": "Via Roma 1", "radiusM": 150 } ],
  "roles": [ { "name": "Sysadmin" } ],
  "presets": [ { "text": "Backup check", "companyKey": "ACME", "roleName": "Sysadmin" } ],
  "todos": [ { "key": "T-9", "companyKey": "ACME", "clientKey": "C-1", "title": "Patch servers",
               "refs": [ "INC0012" ], "dueDate": "2026-10-20", "dueTime": "09:00", "priority": 2 } ],
  "entries": [ { "key": "6f1c0f4e-…", "companyKey": "ACME", "clientKey": "C-1", "todoKey": "T-9",
                 "activity": "Patched 3 servers", "status": "CLOSED", "zone": "Europe/Rome",
                 "segments": [ { "kind": "WORK", "startUtc": "2026-10-17T07:00:00Z", "endUtc": "2026-10-17T11:00:00Z" } ],
                 "expenses": [ { "amount": "12.50", "description": "Parking", "createdUtc": "2026-10-17T07:01:00Z", "receipt": true } ],
                 "refs": [ "INC0012" ] } ],
  "absences": [ { "date": "2026-10-19", "kind": "LATE_ENTRY", "time": "10:30" } ],
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
  <version>1</version>
  <clients>
    <client><key>C-1</key><companyKey>ACME</companyKey><name>Bank</name></client>
  </clients>
  <todos>
    <todo>
      <key>T-9</key><companyKey>ACME</companyKey><clientKey>C-1</clientKey>
      <title>Patch servers</title>
      <refs><ref>INC0012</ref></refs>
      <dueDate>2026-10-20</dueDate>
    </todo>
  </todos>
</activitytrack>
```

List containers and items: companies/company, clients/client, sites/site,
roles/role, presets/preset, todos/todo, entries/entry, segments/segment,
expenses/expense, refs/ref, absences/absence, daysOff/dayOff.

## Values

| Field | Values |
|---|---|
| `status` | ACTIVE, SUSPENDED, TO_COMPLETE, CLOSED |
| `segments.kind` | WORK, TRAVEL, PAUSE |
| `segments.source` | TRACKED, MANUAL |
| `locationKind` | COMPANY, CLIENT, HOME, OTHER |
| `absences.kind` | FULL_DAY, LATE_ENTRY, EARLY_EXIT |
| `sites.ownerKind` | company, client |
