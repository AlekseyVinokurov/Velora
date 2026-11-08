#!/usr/bin/env python3
"""Interactive owner setup; never sends credentials through chat or command arguments."""
import getpass
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_KEY = Path.home() / '.config/velora/signing/velora-release.p12'


def main():
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        raise ValueError('Run in your own interactive terminal; do not pipe or record passwords.')
    os.umask(0o077)
    local = ROOT / 'private.properties'
    previous = {}
    if local.exists():
        for line in local.read_text().splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                name, value = line.split('=', 1)
                previous[name.strip()] = value.strip()
    key_path = Path(input(f'Existing permanent keystore or new path [{DEFAULT_KEY}]: ').strip()
                    or previous.get('RELEASE_STORE_FILE') or str(DEFAULT_KEY)).expanduser().resolve()
    if any((parent / '.git').exists() for parent in key_path.parents):
        raise ValueError('The permanent key must stay outside every Git working tree.')
    pin_file = ROOT / 'release/certificate.sha256'
    pins = [line.strip() for line in pin_file.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith('#')]
    alias = input('Key alias [velora-release]: ').strip() or previous.get('RELEASE_KEY_ALIAS', 'velora-release')
    if not key_path.exists():
        if pins:
            raise ValueError('A certificate is already pinned. Restore the existing key; do not create another.')
        print('No keystore at that path. Creating the ONE permanent RSA-4096 key (12000 days).')
        print('keytool will ask for a new passphrase and public certificate details.')
        key_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        subprocess.run(['keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', str(key_path),
                        '-alias', alias, '-keyalg', 'RSA', '-keysize', '4096', '-sigalg', 'SHA256withRSA',
                        '-validity', '12000'], check=True)
        key_path.chmod(0o600)
    print('Open the existing key for verification and local builds. Password input is hidden.')
    store_password = getpass.getpass('Keystore password: ')
    key_password = getpass.getpass('Key password [Enter = keystore password]: ') or store_password
    if not store_password:
        raise ValueError('An empty keystore password is not supported.')
    with tempfile.TemporaryDirectory(prefix='velora-public-certificate-') as temporary:
        certificate = Path(temporary) / 'public.der'
        env = dict(os.environ, VELORA_LOCAL_STORE_PASSWORD=store_password)
        result = subprocess.run(['keytool', '-exportcert', '-keystore', str(key_path), '-alias', alias,
                                 '-storepass:env', 'VELORA_LOCAL_STORE_PASSWORD', '-file', str(certificate)],
                                env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise ValueError('Cannot open the keystore/alias. Existing files and certificate pin were preserved.')
        fingerprint = hashlib.sha256(certificate.read_bytes()).hexdigest()
    if pins and pins != [fingerprint]:
        raise ValueError('Certificate differs from the permanent pin. Restore the original key; files preserved.')
    api_id = previous.get('TELEGRAM_API_ID', '')
    api_hash = previous.get('TELEGRAM_API_HASH', '')
    if not re.fullmatch('[1-9][0-9]*', api_id) or not re.fullmatch('[0-9a-fA-F]{32}', api_hash):
        print('Enter YOUR API parameters from https://my.telegram.org/apps (kept only locally).')
        api_id = input('Telegram API ID: ').strip()
        api_hash = getpass.getpass('Telegram API HASH (hidden): ').strip()
    if not re.fullmatch('[1-9][0-9]*', api_id) or int(api_id) > 2147483647 or not re.fullmatch('[0-9a-fA-F]{32}', api_hash):
        raise ValueError('Invalid API ID/HASH format. Existing configuration preserved.')
    # Escape Java Properties values, including Windows paths and arbitrary passphrases.
    def escape(value):
        result = ''
        for ch in value:
            if ch == '\\': result += '\\\\'
            elif ch == '\n': result += '\\n'
            elif ch == '\r': result += '\\r'
            elif ch == '\t': result += '\\t'
            elif ch in ' =:#!': result += '\\' + ch
            elif ord(ch) > 127:
                for i in range(0, len(ch.encode('utf-16-be')), 2):
                    result += '\\u' + ch.encode('utf-16-be')[i:i+2].hex()
            else: result += ch
        return result
    managed = dict(TELEGRAM_API_ID=api_id, TELEGRAM_API_HASH=api_hash, RELEASE_STORE_FILE=str(key_path),
                   RELEASE_KEY_ALIAS=alias, RELEASE_STORE_PASSWORD=store_password, RELEASE_KEY_PASSWORD=key_password)
    existing_lines = local.read_text().splitlines() if local.exists() else []
    lines = [line for line in existing_lines if line.split('=', 1)[0].strip() not in managed]
    lines.extend(name + '=' + escape(value) for name, value in managed.items())
    # Atomic replacement; don't follow an unexpected private.properties symlink.
    if local.is_symlink():
        raise ValueError('Refusing to overwrite a symlink for private.properties.')
    with tempfile.NamedTemporaryFile(mode='w', dir=ROOT, prefix='.velora-config-', delete=False, encoding='ascii') as out:
        temporary_config = Path(out.name)
        out.write('\n'.join(lines) + '\n')
    temporary_config.chmod(0o600)
    temporary_config.replace(local)
    pin_file.write_text(fingerprint + '\n')
    (ROOT / 'docs/SIGNING_CERTIFICATE.md').write_text(
        '# Velora release certificate\n\nPublic SHA-256 of the permanent certificate:\n\n`' + fingerprint + '`\n\n'
        'The private key remains outside Git. Keep the same key and pin for every update.\n'
        'Make two encrypted backups on separate media and keep the password separately.\n'
        'See [RELEASING.md](RELEASING.md).\n')
    print('Local private.properties saved with permissions 0600. Do not share it or commit it.')
    print('Public certificate SHA-256:', fingerprint)
    print('Make TWO encrypted backups of this exact key on separate media; store the password separately.')
    print('Setup complete. Tell the coding agent only: local setup done. Never paste the secrets.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError, KeyboardInterrupt, EOFError) as error:
        print('Setup stopped:', error, file=sys.stderr)
        sys.exit(1)
