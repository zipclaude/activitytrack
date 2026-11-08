#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""
The update manifest of ActivityTrack (docs/UPDATES.md, issue #1): assemble it from the GitLab releases of
a tag in the six repositories, sign it with the release key, check it.

    update-manifest.py assemble --tag v0.9.9 [--previous update-manifest.json] [--apk file] --out payload.json
    update-manifest.py sign --payload payload.json --p12 release.p12 --out update-manifest.json
    update-manifest.py verify update-manifest.json [--fingerprint <sha256 of the signer's certificate> | --p12 release.p12]

`assemble` keeps the releases of the previous manifest (all channels), replaces the entry of the same
version and channel, and takes the packages of the tag from each repository's release: the asset names
decide the component (see COMPONENTS in docs/UPDATES.md). The SHA-256 comes from the release's SHA256SUMS
where there is one, else from the downloaded file. The password of the PKCS#12 file is read from the
environment variable named by --password-env (default P12_PASSWORD). `verify` with --p12 (or
--fingerprint) accepts only a signature of that key: CI checks the manifest on main it starts from, and
the one it just signed, against the leaf certificate of the release key. The signature is a JWS
(RFC 7515, JSON serialization): RS256 for an RSA key, ES256 for an EC one.
"""

import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

GITLAB = "https://gitlab.com"
GROUP = "activitytrack"
FORMAT = "activitytrack-update-manifest"
# repository -> the name of its component
REPOS = {
    "activitytrack": "android",
    "activitytrack-web": "web",
    "python3-activitytrack": "library",
    "activitytrack-desktop": "desktop",
    "activitytrack-cli": "cli",
    "activitytrack-tui": "tui",
}
DEB = re.compile(r"^[a-z0-9-]+_[0-9][^_]*-(?P<distro>deb[0-9]+|ubuntu[0-9.]+)_all\.deb$")
CHANNELS = ("prod", "beta", "test", "dev")


def get(url, binary=False):
    req = urllib.request.Request(url, headers={"User-Agent": "ActivityTrack-manifest"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    return data if binary else data.decode("utf-8")


def channel_of(version):
    """prod for X.Y.Z; a suffix names the channel (1.0.0-test.1 → test), anything else is a beta."""
    if "-" not in version:
        return "prod"
    suffix = re.split(r"[.-]", version.split("-", 1)[1])[0].lower()
    return suffix if suffix in CHANNELS else "beta"


def version_key(v):
    """Release order: X.Y.Z.W numbers (a security patch has a fourth), a suffix (beta-N, beta.N) before the release;
    a numeric part sorts before a text one, as in the app (Versions.compare) and the PC library."""
    main, _, pre = v.partition("-")
    nums = [int(x) if x.isdigit() else 0 for x in main.split(".")]
    return (nums + [0, 0, 0, 0])[:4], (1, ()) if not pre else (0, tuple((0, int(p), "") if p.isdigit() else (1, 0, p) for p in re.split(r"[.-]", pre)))


def sha256_of_url(url):
    h = hashlib.sha256()
    req = urllib.request.Request(url, headers={"User-Agent": "ActivityTrack-manifest"})
    size = 0
    with urllib.request.urlopen(req, timeout=300) as r:
        for chunk in iter(lambda: r.read(1 << 20), b""):
            h.update(chunk)
            size += len(chunk)
    return h.hexdigest(), size


def packages_of(repo, tag, local_apk):
    """The packages of [repo]'s release [tag]: [] when it is not published (yet)."""
    project = urllib.parse.quote(f"{GROUP}/{repo}", safe="")
    try:
        release = json.loads(get(f"{GITLAB}/api/v4/projects/{project}/releases/{tag}"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print(f"{repo}: no release {tag} yet", file=sys.stderr)
            return [], None
        raise
    links = {x["name"]: x["url"] for x in release.get("assets", {}).get("links", [])}
    sums = {}
    if "SHA256SUMS" in links:
        for line in get(links["SHA256SUMS"]).splitlines():
            parts = line.split()
            if len(parts) == 2:
                sums[parts[1].lstrip("*")] = parts[0].lower()
    name = REPOS[repo]
    out = []
    for fname, url in sorted(links.items()):
        m = DEB.match(fname)
        if repo == "activitytrack":
            component = "android" if fname.endswith(".apk") else None
        elif m:
            component = f"{name}-{m.group('distro')}"
        elif fname.endswith(".tar.gz"):
            component = f"{name}-tar"
        elif fname.endswith((".exe", "-windows.zip")):
            component = f"{name}-windows"
        else:
            component = None
        if component is None:
            continue
        if fname in sums:
            sha, size = sums[fname], 0
        elif local_apk and component == "android" and os.path.basename(local_apk) == fname:
            data = open(local_apk, "rb").read()
            sha, size = hashlib.sha256(data).hexdigest(), len(data)
        else:
            sha, size = sha256_of_url(url)
        out.append({"component": component, "url": url, "sha256": sha, "size": size})
    return out, release.get("released_at", "")[:10]


def unsigned(manifest_text):
    """The payload of a manifest file written by this script (its signature is not checked here)."""
    doc = json.loads(manifest_text)
    return json.loads(b64u_decode(doc["payload"]))


def b64u(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64u_decode(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def assemble(args):
    tag = args.tag
    version = tag.removeprefix("v")
    previous = {"releases": []}
    if args.previous and os.path.exists(args.previous) and os.path.getsize(args.previous):
        previous = unsigned(open(args.previous).read())
    packages, published = [], ""
    for repo in REPOS:
        found, date = packages_of(repo, tag, args.apk)
        packages += found
        published = published or (date if repo == "activitytrack" else "")
    if not any(p["component"] == "android" for p in packages):
        sys.exit(f"the release {tag} of activitytrack has no APK: nothing to announce")
    entry = {"version": version, "channel": channel_of(version), "published": published or datetime.date.today().isoformat(),
             "notes_url": f"{GITLAB}/{GROUP}/activitytrack/-/releases/{tag}", "packages": packages}
    releases = [r for r in previous.get("releases", []) if (r["version"], r.get("channel")) != (version, entry["channel"])]
    releases.append(entry)
    releases.sort(key=lambda r: version_key(r["version"]), reverse=True)
    # a clock that never goes back: the apps refuse an older "issued" than the last they saw
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    last = previous.get("issued")
    if last:
        floor = datetime.datetime.fromisoformat(last.replace("Z", "+00:00")) + datetime.timedelta(seconds=1)
        now = max(now, floor)
    payload = {"format": FORMAT, "v": 1, "issued": now.isoformat().replace("+00:00", "Z"), "releases": releases}
    with open(args.out, "w") as f:
        json.dump(payload, f, indent=1, ensure_ascii=False)
    print(f"{len(packages)} packages for {version} ({entry['channel']}), {len(releases)} releases in the manifest", file=sys.stderr)


def load_p12(path, password):
    from cryptography.hazmat.primitives.serialization import pkcs12
    key, cert, extra = pkcs12.load_key_and_certificates(open(path, "rb").read(), password.encode())
    if key is None or cert is None:
        sys.exit("the PKCS#12 file has no key and certificate")
    return key, [cert] + list(extra or [])


def sign(args):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa, utils
    password = os.environ.get(args.password_env)
    if password is None:
        sys.exit(f"the password is read from ${args.password_env}")
    key, certs = load_p12(args.p12, password)
    der = [c.public_bytes(serialization.Encoding.DER) for c in certs]
    if isinstance(key, rsa.RSAPrivateKey):
        alg = "RS256"
    elif isinstance(key, ec.EllipticCurvePrivateKey) and key.curve.name == "secp256r1":
        alg = "ES256"
    else:
        sys.exit("the key must be RSA or EC P-256")
    payload = open(args.payload, "rb").read()
    header = {"alg": alg, "x5c": [base64.b64encode(d).decode("ascii") for d in der],
              "x5t#S256": b64u(hashlib.sha256(der[0]).digest())}
    protected = b64u(json.dumps(header, separators=(",", ":")).encode())
    # the payload as compact JSON: what the apps read is what was signed
    body = b64u(json.dumps(json.loads(payload), separators=(",", ":"), ensure_ascii=False).encode())
    data = f"{protected}.{body}".encode("ascii")
    if alg == "RS256":
        signature = key.sign(data, padding.PKCS1v15(), hashes.SHA256())
    else:
        r, s = utils.decode_dss_signature(key.sign(data, ec.ECDSA(hashes.SHA256())))
        signature = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    with open(args.out, "w") as f:
        json.dump({"payload": body, "signatures": [{"protected": protected, "signature": b64u(signature)}]}, f)
        f.write("\n")
    print(f"signed {alg}, signer {hashlib.sha256(der[0]).hexdigest()}", file=sys.stderr)


def verify(args):
    from cryptography import x509
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec, padding, utils
    wanted = args.fingerprint
    if args.p12:
        password = os.environ.get(args.password_env)
        if password is None:
            sys.exit(f"the password is read from ${args.password_env}")
        from cryptography.hazmat.primitives import serialization
        _key, certs = load_p12(args.p12, password)
        wanted = hashlib.sha256(certs[0].public_bytes(serialization.Encoding.DER)).hexdigest()
    doc = json.loads(open(args.manifest).read())
    for s in doc["signatures"]:
        header = json.loads(b64u_decode(s["protected"]))
        der = base64.b64decode(header["x5c"][0])
        fp = hashlib.sha256(der).hexdigest()
        if wanted and fp != re.sub(r"[^0-9a-f]", "", wanted.lower()):
            continue
        key = x509.load_der_x509_certificate(der).public_key()
        data = f"{s['protected']}.{doc['payload']}".encode("ascii")
        sig = b64u_decode(s["signature"])
        try:
            if header["alg"] == "RS256":
                key.verify(sig, data, padding.PKCS1v15(), hashes.SHA256())
            elif header["alg"] == "ES256":
                key.verify(utils.encode_dss_signature(int.from_bytes(sig[:32], "big"), int.from_bytes(sig[32:], "big")),
                           data, ec.ECDSA(hashes.SHA256()))
            else:
                continue
        except InvalidSignature:
            continue
        payload = json.loads(b64u_decode(doc["payload"]))
        if payload.get("format") != FORMAT:
            sys.exit("not an update manifest")
        print(f"ok: {header['alg']} signed by {fp}; issued {payload['issued']}; " +
              ", ".join(f"{r['version']}[{r.get('channel')}]" for r in payload["releases"]))
        return
    sys.exit("no valid signature" + (" of that signer" if wanted else ""))


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="command", required=True)
    a = sub.add_parser("assemble")
    a.add_argument("--tag", required=True)
    a.add_argument("--previous")
    a.add_argument("--apk", help="the APK of this pipeline: its SHA-256 is taken from the file")
    a.add_argument("--out", required=True)
    a.set_defaults(run=assemble)
    s = sub.add_parser("sign")
    s.add_argument("--payload", required=True)
    s.add_argument("--p12", required=True)
    s.add_argument("--password-env", default="P12_PASSWORD")
    s.add_argument("--out", required=True)
    s.set_defaults(run=sign)
    v = sub.add_parser("verify")
    v.add_argument("manifest")
    v.add_argument("--fingerprint")
    v.add_argument("--p12", help="accept only a signature of this PKCS#12 file's leaf certificate")
    v.add_argument("--password-env", default="P12_PASSWORD")
    v.set_defaults(run=verify)
    args = p.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
