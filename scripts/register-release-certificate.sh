#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
[[ $# -ge 1 && $# -le 2 ]] || { echo 'Usage: register-release-certificate.sh /external/key.p12 [alias]' >&2; exit 1; }
[[ -t 0 ]] || { echo 'Run in an interactive terminal; keytool will request the store password.' >&2; exit 1; }
key_path=$(realpath -e -- "$1")
parent=$(dirname -- "$key_path")
while [[ "$parent" != / ]]; do
    [[ ! -e "$parent/.git" ]] || { echo 'Keystore must be outside Git.' >&2; exit 1; }
    parent=$(dirname -- "$parent")
done
certificate=$(mktemp)
trap 'rm -f -- "$certificate"' EXIT
# This exports only a public X.509 certificate. No private key leaves the keystore.
keytool -exportcert -keystore "$key_path" -alias "${2:-velora-release}" -file "$certificate"
fingerprint=$(sha256sum -- "$certificate" | cut -d ' ' -f 1)
existing=$(sed '/^[[:space:]]*#/d; /^[[:space:]]*$/d' "$repo_dir/release/certificate.sha256")
[[ -z "$existing" || "$existing" == "$fingerprint" ]] || {
    echo 'Certificate differs from the permanent pin. No files changed. Restore the original key.' >&2; exit 1;
}
printf '%s\n' "$fingerprint" > "$repo_dir/release/certificate.sha256"
cat > "$repo_dir/docs/SIGNING_CERTIFICATE.md" <<DOC
# Velora release certificate

Public SHA-256 fingerprint of the actual permanent signing certificate:

\`$fingerprint\`

The same pin is stored in \`release/certificate.sha256\`. The private key stays outside
Git. Review and commit these public files before building the first stable APK.
Do not replace this pin to release an ordinary update. Back up and restore the same
key as described in [RELEASING.md](RELEASING.md).
DOC
printf 'Public certificate SHA-256: %s\n' "$fingerprint"
