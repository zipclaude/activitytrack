#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Signs an ActivityTrack policy (issue #48): writes <policy>.sig next to it.
#
#   scripts/sign-policy.sh policy.json signer-key.pem signer-cert.pem [chain.pem]
#
# The signer certificate (and the intermediates in chain.pem, if any) must
# lead to a CA the phone trusts (system or user/MDM-installed). The first
# signed policy a phone applies pins the certificate's CN: later policies
# must be signed with the same CN. Keys: EC (SHA256withECDSA) or RSA
# (SHA256withRSA). Sign the final file: any later change, even a space,
# breaks the signature.
set -eu

[ $# -ge 3 ] || { echo "usage: $0 policy.json key.pem cert.pem [chain.pem]" >&2; exit 2; }
policy=$1 key=$2 cert=$3 chain=${4:-}

if openssl pkey -in "$key" -noout -text 2>/dev/null | grep -q 'ASN1 OID'; then
	alg=SHA256withECDSA
else
	alg=SHA256withRSA
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

openssl dgst -sha256 -sign "$key" -out "$tmp/sig" "$policy"
openssl x509 -in "$cert" -noout -subject
cat "$cert" ${chain:+"$chain"} > "$tmp/chain"

# PEM certificates as JSON strings, newlines escaped
pems=$(awk '
	/-----BEGIN CERTIFICATE-----/ { s = ""; on = 1 }
	on { s = s $0 "\\n" }
	/-----END CERTIFICATE-----/ { printf "%s\t\t\"%s\"", sep, s; sep = ",\n"; on = 0 }
' "$tmp/chain")

{
	printf '{\n\t"format": "activitytrack-policy-signature",\n'
	printf '\t"algorithm": "%s",\n' "$alg"
	printf '\t"chain": [\n%s\n\t],\n' "$pems"
	printf '\t"signature": "%s"\n}\n' "$(base64 -w0 "$tmp/sig")"
} > "$policy.sig"
echo "written $policy.sig ($alg)"
