# Integration plugins — descriptor format (issue #48 part B)

Status: format approved by the owner (2026-10-21); implemented and in use
since 0.7.0 (Antares, `plugins/antares.json`). Extensions since: list
sources with `when` (0.7.2). A step-by-step guide to writing a plugin:
[`plugins/GUIDE.en.md`](../plugins/GUIDE.en.md) (Italian:
[`plugins/GUIDE.it.md`](../plugins/GUIDE.it.md)).

Plugins are not built into the app (owner): published in `plugins/` of
this repository, copied by the user into `plugins/` of the data folder.
With a signed policy in force, a plugin must be signed with the same CN
(`<name>.json.sig`, made by `scripts/sign-policy.sh`).

An integration is a JSON file `plugins/<name>.json` in the data folder,
enabled by the policy (`"integrations": { "<name>": { "plugin": "<name>.json" } }`)
or, without a policy, from Settings → Integrations. The file describes the
remote service; the app supplies a closed set of building blocks
(authentication adapters, paging styles, selectors, transforms,
aggregations). There are no scripts: everything is checked when the file
is loaded, and a bad descriptor is refused with path and line, like
`policy.json`. Secrets never live in the file: they are entered (or
obtained by a login) in the app and kept in the keystore-encrypted store.

The complete Antares example is `plugins/antares.json`.

## 1. Top level

| Key | Meaning |
|---|---|
| `format` | `"activitytrack-plugin"` |
| `schema` | `1` |
| `id`, `name`, `version` | identity shown in Settings; `id` = file name without `.json` |
| `options` | the settings card of the integration, per company (§2) |
| `auth` | authentication adapter (§3) |
| `http` | base URL, headers, timeouts, paging style (§4) |
| `identity` | optional: who the logged-in user is on the remote side (§5) |
| `lookups` | optional: small lists read first and used by the mappings (§5) |
| `lists` | remote lists kept for the entry form, e.g. services and activity types (§6) |
| `import` | remote records → app records: clients, contacts, to-dos (§7) |
| `entryFields` | fields the integration adds to the entry screen (§8) |
| `export` | app entries → remote records: aggregation, body, create/update/delete (§9) |
| `schedule` | sync on close and background fetch (§10) |

## 2. Options

```json
"options": [
	{ "key": "apiUrl", "type": "url", "label": { "it": "URL API", "en": "API URL" }, "required": true, "default": "https://demo.goantares.uno/public/api" },
	{ "key": "pauseSplitMin", "type": "int", "min": 1, "max": 600, "default": 60, "label": { "en": "Pause splitting the activity (min)" } },
	{ "key": "closeMode", "type": "choice", "choices": { "ask": { "it": "Chiedi" }, "always": { "it": "Sempre" }, "off": { "it": "Mai" } }, "default": "ask" },
	{ "key": "password", "type": "secret" }
]
```

Types: `string`, `url` (https only), `int` (`min`, `max`), `bool`, `choice`,
`secret` (stored encrypted, never exported or shown again). A policy can fix
or hide an option like a setting: `"integrations": { "antares": { "plugin": "antares.json", "options": { "apiUrl": { "value": "…", "locked": true } } } }`.

## 3. Authentication adapters

| `type` | Parameters |
|---|---|
| `none` | – |
| `basic` | `user`, `password` (option references) |
| `apiKey` | `header` or `query` name, `key` (a `secret` option) |
| `bearer` | `token` (a `secret` option) |
| `oauth2Pkce` | `issuer` (OpenID discovery) or `authorizeUrl` + `tokenUrl`, `clientId`, `scopes`; public client, PKCE, redirect `<applicationId>:/oauth2redirect`, refresh token kept encrypted |

## 4. HTTP and paging

```json
"http": {
	"baseUrl": "${option.apiUrl}",
	"headers": { "Accept": "application/json" },
	"timeoutSec": 60,
	"paging": { "style": "odata", "pageSize": 200 }
}
```

Paging styles: `none`, `odata` (`$top`/`$skip`), `page` (`pageParam`,
`sizeParam`, first page 0 or 1), `cursor` (`cursorParam`, `next`: selector
of the next cursor in the response), `link` (RFC 8288 `Link: rel=next`),
`jsonParam` (the page inside a JSON query parameter: `param`, the object
`inside` it, `start` and `size` fields, `firstStart` 0 or 1, `pageSize`,
`more`: a selector telling whether another page follows — only `true`
counts). A response is the list itself or `items`: a selector of the list in it.

A query value that is an object or a list goes as its JSON text. With
`"bodyForm": "input_data"` the body of create, update and the other calls
goes as that single form field (`application/x-www-form-urlencoded`)
instead of a JSON body. ServiceDesk Plus wants both:

```json
"http": {
	"baseUrl": "@option.serverUrl",
	"headers": { "Accept": "application/vnd.manageengine.sdp.v3+json" },
	"bodyForm": "input_data",
	"paging": { "style": "jsonParam", "param": "input_data", "inside": "list_info", "start": "start_index",
	            "size": "row_count", "firstStart": 1, "pageSize": 100, "more": "$.list_info.has_more_rows" }
}
```

## 5. Values: selectors and transforms

Wherever a value is expected the descriptor may write:

- a literal: `2`, `true`, `"text"`;
- a string with references `${…}` inside: `"/v3/Activities/${remote.id}"` (a whole value is written `"@remote.id"`);
- a **selector** on the remote record being read: `"$.Name"`, `"$.Customer.Code"`,
  `"$[?(@.type == 'antares:person:id')].value"` (a JSONPath subset: fields,
  indexes, filters with `==`/`!=`, `null` tests);
- a **reference** to app data or context: `@entry.activity`,
  `@entry.notes`, `@entry.client.code`, `@todo.externalKey`, `@block.from`,
  `@block.travelMinutes`, `@identity.personId`, `@option.pauseSplitMin`,
  `@lookup.ticketStates`, `@field.activityType` (§8), `@remote.Timestamp` (§9);
- a **transform** object, from this closed list:

| Transform | Result |
|---|---|
| `{"coalesce": [a, b, …]}` | the first non-empty |
| `{"concat": [a, b, …], "separator": "\n"}` | non-empty strings joined |
| `{"eq": [a, b]}`, `{"ne": [a, b]}` | boolean |
| `{"in": [value, list]}`, `{"notIn": [value, list]}` | boolean |
| `{"and": […]}`, `{"or": […]}`, `{"not": a}` | boolean |
| `{"if": cond, "then": a, "else": b}` | choice |
| `{"map": value, "cases": {"x": 1, "y": 2}, "default": 0}` | value table |
| `{"lower": a}`, `{"upper": a}`, `{"trim": a}` | text |
| `{"plainText": a}` | text of an HTML fragment: tags dropped, entities decoded |
| `{"number": a}`, `{"bool": a}`, `{"string": a}` | conversions |
| `{"date": a, "format": "isoOffset" \| "isoLocal" \| "isoDate" \| "epochMs" \| "<pattern>"}` | dates in the entry's time zone |
| `{"minutes": a, "round": "work" \| "travel" \| "pause" \| "none"}` | a length rounded by the report rule |
| `{"materials": "text" \| "lines"}` | the entry's materials, as text after the description or as a list of lines |
| `{"object": {"key": value, …}}` | a JSON object (nested bodies, a JSON query parameter); keys whose value is `null` are left out |

`date` reads milliseconds as a number or as text of digits, or an ISO date-time.

## 6. Lookups and lists

```json
"identity": { "request": { "path": "/me/claims" }, "values": { "personId": "$[?(@.type == 'antares:person:id')].value" } },
"lookups": {
	"ticketStates": { "request": { "path": "/v3/Enums", "query": { "$filter": "Type eq 'TicketState'" } }, "select": "$[?(@.EntityState != null)].Value" }
},
"lists": {
	"portfolio": {
		"sources": [
			{ "request": { "path": "/v3/ServicePortfolios", "query": { "$select": "Id,Name,CustomerId,State" }, "paged": true },
			  "map": { "id": "$.Id", "name": "$.Name", "clientCode": "$.CustomerId", "kind": "service",
			           "active": { "in": ["$.State", "@lookup.serviceStates"] },
			           "extra": { "path": "/v3/ServicePortfolios" } } }
		]
	},
	"activityType": {
		"parent": "portfolio",
		"sources": [ { "request": { "path": "${parent.extra.path}/${parent.id}/ActivityTypes" },
		               "map": { "id": "$.ActivityTypeId", "name": "$.ActivityTypeName", "extra": { "included": "$.Included" } } } ]
	}
}
```

A source may carry `"when"`: an expression on the parent item (`@parent`);
the source is read only for the parents where it is true. Antares reads
the customer premises of services only, since projects have none:

```json
"premises": {
	"parent": "portfolio",
	"sources": [ { "when": "@parent.extra.premises",
	               "request": { "path": "/v3/ServicePortfolios/${parent.id}/CustomerPremises" },
	               "map": { "id": { "coalesce": ["$.CustomerPremisId", "$.Id"] }, "name": { "coalesce": ["$.CustomerPremisName", "$.Name"] } } } ]
}
```

A list item has `id`, `name`, optional `parentId`, `clientCode`, `kind`,
`active` (inactive items are kept for old entries but not offered) and
`extra` (any values used later). Lists replace the Antares-only tables of
PR #29 with one generic table.

## 7. Import

```json
"import": {
	"todos": {
		"request": { "path": "/v3/Tickets", "query": { "$filter": "TechnicianId eq ${identity.personId}" }, "paged": true },
		"key": "$.Id",
		"skipNew": { "notIn": ["$.State", "@lookup.ticketStates"] },
		"map": {
			"title": { "coalesce": ["$.Name", "$.Code"] },
			"notes": "$.Description", "refs": "$.Code",
			"clientCode": "$.CustomerId", "priority": "$.Priority",
			"done": { "notIn": ["$.State", "@lookup.ticketStates"] },
			"fields": { "portfolio": "$.PortfolioId" }
		}
	},
	"clients": { "request": { "path": "/v3/Customers", "paged": true }, "key": "$.Id", "map": { "name": "$.Name", "code": "$.Id" } }
}
```

A target may also be a **list of sources**, each with an optional `"when"`
on the options (ServiceDesk Plus: the requests, and the tasks only when
the option asks for them); their keys must not clash (`request-…`,
`task-…`).

Targets: `clients`, `contacts`, `todos`. Matching is by `key`
(external code), idempotent as in the generic import; the app's own
changes win on conflict and are listed in the report. `fields` pre-fills
the entry fields (§8) of entries started from the to-do.

## 8. Entry fields

```json
"entryFields": [
	{ "key": "portfolio", "list": "portfolio", "label": { "it": "Servizio o progetto" }, "required": { "eq": ["@todo", null] },
	  "hidden": { "ne": ["@todo", null] }, "filter": { "clientCode": "@entry.client.code" } },
	{ "key": "activityType", "list": "activityType", "parent": "@field.portfolio", "required": true },
	{ "key": "included", "type": "bool", "default": "@list.activityType.selected.extra.included" }
]
```

Shown in the entry screen under the integration's name; values are
stored with the entry (one JSON column, replacing PR #29's three columns).
An entry missing a required field is not sent: the sync report says which.

## 9. Export

```json
"export": {
	"entries": "closed",
	"group": { "by": "dayBlock", "splitPauseMin": "@option.pauseSplitMin", "travel": "nearestBlock", "round": "work" },
	"body": {
		"RelationType": { "if": { "ne": ["@todo", null] }, "then": 2, "else": { "map": "@list.portfolio.selected.kind", "cases": { "service": 1, "project": 5 } } },
		"TicketId": { "if": { "ne": ["@todo", null] }, "then": "@todo.externalKey" },
		"PortfolioId": { "if": { "eq": ["@todo", null] }, "then": "@field.portfolio" },
		"ActivityTypeId": "@field.activityType",
		"OwnerId": "@identity.personId",
		"FromDate": { "date": "@block.from", "format": "isoOffset" },
		"ToDate": { "date": "@block.to", "format": "isoOffset" },
		"TimeFormat": 1, "Status": 1,
		"Included": "@field.included",
		"ActivityReport": "@entry.activity", "Note": "@entry.notes",
		"TravelTimeMinutes": "@block.travelMinutes", "TravelKm": "@block.travelKm"
	},
	"create": { "method": "POST", "path": "/v3/Activities", "id": "$.Id" },
	"update": { "method": "PUT", "path": "/v3/Activities/${remote.id}", "fetchFirst": true, "add": { "Id": "@remote.id", "Timestamp": "@remote.Timestamp" } },
	"delete": { "method": "DELETE", "path": "/v3/Activities/${remote.id}" }
}
```

Groupings (the aggregation library):

| `by` | One remote record per |
|---|---|
| `entry` | entry |
| `day` | entry and report day (night carry-over included) |
| `dayBlock` | entry, report day and block of work; a pause longer than `splitPauseMin` starts a new block; pauses inside a block are absorbed |
| `segment` | segment (work, travel and pause each their own record) |

`travel`: `nearestBlock` (minutes and km added to the closest block of the
day), `separate` (its own record, body from `travelBody`), `ignore`.
`round`: the report rule applied to the block's length.

A key whose value is `null` is left out of the body (`"TicketId"` of a
service activity). Each record sent keeps its remote id and the hash of its body: unchanged
→ nothing sent; changed → `update`; gone (edited times, shorter pause) →
`delete`. An entry deleted in the app keeps its remote ids (table
`remote_deleted`, with the company) until the next sync of that company
deletes them; a `404` there counts as already deleted. `fetchFirst` reads the current remote record before the update
(Antares' `Timestamp` guards against overwriting someone else's change).

The create path may use `@todo` (`"/api/v3/${todo.fields.path}/worklogs"`)
and its `id` may build the remote id with it
(`{"concat": ["@todo.fields.path", "/worklogs/", "$.worklog.id"], "separator": ""}`):
the update and the delete then need only `${remote.id}`, also for an entry
already deleted in the app.

`todoDone` (optional) closes on the remote side, once, a to-do imported by
the plugin and marked done in the app, after the entries went:
`{ "method": "PUT", "path": "/api/v3/${todo.fields.path}", "body": { … } }`,
with `@todo` (`id`, `externalKey`, `title`, `refs`, `fields`, `activities`:
the descriptions of its entries, oldest first). The to-do's plugin fields
then carry `"_closed": true`; a to-do the remote reports closed gets it
at the import, so it is never closed twice.

## 10. Schedule

```json
"schedule": {
	"onClose": { "mode": "@option.closeMode", "scope": "@option.closeScope", "sync": "@option.closeSync" },
	"fetchEveryMin": "@option.fetchMin"
}
```

As in issue #34: on closing an entry, ask / always / never send, for
to-do entries only or all, the whole sync or only that entry; background
download every 15 min to once a day (WorkManager's minimum is 15 min).
