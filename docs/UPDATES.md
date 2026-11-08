# Updates: the signed manifest

How the app (and the PC apps) learn of a new version (issue #1). The check reads a **manifest**: one
JSON file listing the releases by channel with the address and SHA-256 of every package, signed
digitally. Who may sign it depends on the policy and the pairing; the package is checked against its
SHA-256 before anything is installed.

## 1. File

A JWS ([RFC 7515](https://www.rfc-editor.org/rfc/rfc7515), JSON serialization), the signature inline:

```json
{ "payload": "<base64url of the manifest>",
  "signatures": [ { "protected": "<base64url of {\"alg\", \"x5c\", \"x5t#S256\"}>", "signature": "<base64url>" } ] }
```

- `alg`: **ES256** (raw R‖S) or **RS256** only; `none`, `HS*`, anything else is refused.
- `x5c`: the signer's certificate chain, standard base64 DER, **leaf first**; the signature is checked with
  the leaf's key. A file may carry several signatures (the server adds its own to the project's).
- The signing input is `protected "." payload`, both as in the file.

The payload:

```json
{ "format": "activitytrack-update-manifest", "v": 1, "issued": "2026-11-07T10:00:00Z",
  "releases": [
    { "version": "0.9.9", "channel": "prod", "published": "2026-11-12",
      "notes_url": "https://gitlab.com/infraconsulting/activitytrack/-/releases/v0.9.9",
      "packages": [ { "component": "android", "url": "https://…/ActivityTrack-0.9.9.apk", "sha256": "…", "size": 57407486 } ] } ] }
```

- `channel`: `prod` (a plain `X.Y.Z`), `beta`, `test`, `dev` (a tag suffix: `1.0.0-test.1` is `test`; any other suffix is
  a beta). The user follows `prod` and the channels chosen; the old switch of the betas is the `beta` channel.
- `issued` never goes back: an app refuses a manifest issued before the last one it accepted from that source (replay of
  an old file). An older **version** than the installed one is never offered.
- `url`: https, or (only in the manifest the paired server relays) a path of the server, `/api/v1/…`, fetched with the
  device's token. `sha256` is the digest the downloaded file must have; `size` is for the progress (0 = unknown).
- A release without a valid package, a package without https/sha256: left out, the others stay.
- `components`: `android`; for the PC and the server, per repository `web`, `library`, `desktop`, `cli`, `tui`:
  `<name>-deb13`, `<name>-deb14`, `<name>-ubuntu24.04`, `<name>-ubuntu26.04` (the `.deb` of that distribution),
  `<name>-tar`, `<name>-windows` (the installer or the zip).

## 2. Who may sign

| Situation | Accepted signer (leaf certificate) |
|---|---|
| No signed policy | the **app's own** signing certificate (SHA-256 of the APK's certificate), or the **paired server's** (`k=` of the pairing link) |
| A signed policy in force | only **the policy's identity**: a leaf with the CN the policy pinned, vouched for by a CA of the phone or by the server's pinned key. The app's key is *not* enough |
| PC apps | the paired server's only (the pairing is mandatory) |
| The server (accepts, polls, relays) | the app's key or a fingerprint an administrator pasted explicitly |

A certificate is trusted by its **fingerprint** (SHA-256 of the DER, lowercase hex): the date of a pinned
certificate does not matter, a CA-vouched one is checked on the date. The pairing link
`activitytrack://pair?v=1&u=…&c=…[&f=<TLS key pin>][&k=<signer fingerprint>]` carries `k`; without it the
server's manifests are not trusted. The same `k` lets a self-signed server certificate sign the **policy**.

## 3. Sources (Settings → Updates; policy `updateSource`, `updateUrl`, `updateChannels`)

1. the **project**: `https://gitlab.com/infraconsulting/activitytrack/-/raw/main/update-manifest.json` (default),
2. the **paired server**: the manifest it relays in the sync `head` answer (`"update"`), announced at the first sync
   whatever the source chosen,
3. a **custom https address**.

An app installed from a store or a package manager keeps the check optional ("Check now") but never offers the download.

## 4. Release (CI)

The job `manifest` of the pipeline started by hand on the tag ("Run pipeline", ref = the tag; no pipeline starts
on its own) runs after `release`:
`scripts/update-manifest.py assemble` takes the packages of the tag from the six repositories' GitLab releases (the
asset names give the component; the SHA-256 from the release's `SHA256SUMS`, else from the file), keeps the earlier
releases of the manifest on `main`, and `sign` signs it with the release key (RS256); the result is committed to
`main` with `[skip ci]`. The other repositories release on their own: **retry the job** once they are published (it
starts from the manifest on `main`). Variables: `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (the APK's, protected) and `MANIFEST_PUSH_TOKEN` (project access token,
Maintainer, scope `api`, protected + masked, allowed to push to `main`).

By hand (`P12_PASSWORD` names the PKCS#12 password; the keystore is turned into one with `keytool -importkeystore`):

```sh
scripts/update-manifest.py assemble --tag v0.9.9 --previous update-manifest.json --out payload.json
P12_PASSWORD=… scripts/update-manifest.py sign --payload payload.json --p12 release.p12 --out update-manifest.json
scripts/update-manifest.py verify update-manifest.json
```
