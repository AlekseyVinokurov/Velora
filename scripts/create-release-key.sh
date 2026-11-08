#!/usr/bin/env bash
# Interactive only. No passwords in arguments, shell history, Git or generated artifacts.
set +x
set -euo pipefail
umask 077
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
command -v keytool >/dev/null || { echo 'Install JDK 17 or 21 (keytool required).' >&2; exit 1; }
[[ -t 0 && -t 1 ]] || { echo 'Run in your own interactive terminal.' >&2; exit 1; }
if [[ -n $(sed '/^[[:space:]]*#/d; /^[[:space:]]*$/d' "$repo_dir/release/certificate.sha256") ]]; then
    echo 'A permanent certificate is already pinned. Restore its key instead of creating another.' >&2
    exit 1
fi
read -r -p "External keystore path [$HOME/.config/velora/signing/velora-release.p12]: " key_path
key_path=${key_path:-$HOME/.config/velora/signing/velora-release.p12}
key_path=$(realpath -m -- "$key_path")
parent=$(dirname -- "$key_path")
while [[ "$parent" != / ]]; do
    [[ ! -e "$parent/.git" ]] || { echo 'The key must be outside all Git working trees.' >&2; exit 1; }
    parent=$(dirname -- "$parent")
done
[[ ! -e "$key_path" ]] || { echo 'Refusing to overwrite an existing key.' >&2; exit 1; }
if [[ ! -d $(dirname -- "$key_path") ]]; then
    install -d -m 700 -- "$(dirname -- "$key_path")"
fi
printf '%s\n' 'Choose a unique long passphrase from your password manager; keytool will prompt.'
printf '%s\n' 'Enter your own certificate subject details. They are public; no identity is prefilled.'
keytool -genkeypair -storetype PKCS12 -keystore "$key_path" -alias velora-release \
    -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 12000
chmod 600 -- "$key_path"
"$repo_dir/scripts/register-release-certificate.sh" "$key_path" velora-release
printf '%s\n' 'Back up this exact keystore and its passphrase separately before releasing any APK.'
