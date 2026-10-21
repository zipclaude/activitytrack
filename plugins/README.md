# ActivityTrack integration plugins

Descriptors of management-system integrations (issue #48), in the format of
[`docs/PLUGINS.md`](../docs/PLUGINS.md). They are not built into the app:
copy the ones you need to `plugins/` in the app's data folder and enable
them per company (company screen → Management system) or through
`policy.json`.

| File | Service |
|---|---|
| `antares.json` | Antares (Saidea), Public API v3 |

With a signed policy in force, a plugin must be signed with the same CN:
`scripts/sign-policy.sh antares.json key.pem cert.pem [chain.pem]` writes
`antares.json.sig`.
