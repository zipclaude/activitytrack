#!/usr/bin/env python3
"""
The TEST keys and manifests of core/domain/src/test/resources/update (issue #1): an app key (RSA,
self-signed), a server key (EC, self-signed), a CA with two EC signers ("ACME, Inc. Updates", "Other
Signer"). Private keys are PKCS#8 DER, certificates DER; the two manifests are signed by the
python3-activitytrack implementation, so the Kotlin check is tried against an independent one.

    PYTHONPATH=/path/to/python3-activitytrack scripts/make-update-fixtures.py
"""
import datetime
import os

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.x509.oid import NameOID

from activitytrack import manifest

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core/domain/src/test/resources/update")
NOW = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)


def name(cn):
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def make(cn, key, issuer=None, issuer_key=None, ca=False):
    b = (x509.CertificateBuilder().subject_name(name(cn)).issuer_name(issuer or name(cn)).public_key(key.public_key())
         .serial_number(x509.random_serial_number()).not_valid_before(NOW).not_valid_after(NOW + datetime.timedelta(days=36500))
         .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True))
    return b.sign(issuer_key or key, hashes.SHA256())


def save(stem, key, cert):
    with open(f"{OUT}/{stem}.key", "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    with open(f"{OUT}/{stem}.crt", "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.DER))


def pem(key, certs):
    return (key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()),
            b"".join(c.public_bytes(serialization.Encoding.PEM) for c in certs))


os.makedirs(OUT, exist_ok=True)
app_key = rsa.generate_private_key(65537, 2048)
app = make("TEST ActivityTrack release", app_key)
server_key = ec.generate_private_key(ec.SECP256R1())
server = make("TEST ActivityTrack server", server_key)
ca_key = ec.generate_private_key(ec.SECP256R1())
ca = make("TEST Update CA", ca_key, ca=True)
signers = {}
for stem, cn in (("acme", "ACME, Inc. Updates"), ("other", "Other Signer")):
    k = ec.generate_private_key(ec.SECP256R1())
    signers[stem] = (k, make(cn, k, ca.subject, ca_key))
save("app", app_key, app)
save("server", server_key, server)
save("ca", ca_key, ca)
for stem, (k, c) in signers.items():
    save(stem, k, c)


def package(component, url, sha):
    return {"component": component, "url": url, "sha256": sha, "size": 1234}


def release(version, channel):
    return {"version": version, "channel": channel, "published": "2026-11-05", "notes_url": f"https://example.org/notes/{version}",
            "packages": [package("android", f"https://example.org/ActivityTrack-{version}.apk", "ab" * 32),
                         package("desktop-deb", "https://example.org/d.deb", "cd" * 32)]}


payload = {"issued": "2026-11-07T10:00:00Z",
           "releases": [release("0.9.9", "prod"), release("1.0.0-beta1", "beta"), release("0.9.8", "prod")]}
for stem, (k, certs) in (("py-app", (app_key, [app])), ("py-server", (server_key, [server]))):
    key_pem, chain_pem = pem(k, certs)
    with open(f"{OUT}/{stem}.json", "w") as f:
        f.write(manifest.sign(payload, key_pem, chain_pem))
