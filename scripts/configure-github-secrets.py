#!/usr/bin/env python3
"""Owner-only interactive upload of the existing Velora key to GitHub Secrets.

Never run this through a coding agent or a recorded terminal. No build is started.
"""
import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'AlekseyVinokurov/Velora'
ENVIRONMENT = 'velora-release'
PIN = 'a32f7e8239e47cc061ebabf226a88ab7dc3cf6224625f98ac6166b2966ea1842'
KEY = Path.home() / '.config/velora/signing/velora-release.p12'
SECRET_NAMES = (
    'VELORA_KEYSTORE_BASE64', 'VELORA_KEYSTORE_PASSWORD', 'VELORA_KEY_ALIAS',
    'VELORA_KEY_PASSWORD', 'TELEGRAM_API_ID', 'TELEGRAM_API_HASH',
)


class SetupError(Exception):
    """A fixed diagnostic that contains no user-entered values."""


def gh(*arguments, payload=None):
    result = subprocess.run(['gh', *arguments], input=payload, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode:
        # gh/keytool diagnostics may include private input; never relay them.
        raise SetupError('GitHub request failed. Check gh authentication and repository administrator access; retry setup.')
    return result.stdout


def api(path, payload=None):
    arguments = ['api', f'repos/{REPOSITORY}/{path}'.rstrip('/')]
    if payload is not None:
        arguments += ['--method', 'PUT', '--input', '-']
    return json.loads(gh(*arguments, payload=json.dumps(payload).encode() if payload is not None else None))


def main():
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        raise SetupError('Run in your own interactive terminal. Never send credentials through chat or agent tools.')
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        raise SetupError('Run this setup in your own terminal, outside CI.')
    os.umask(0o077)
    if not shutil.which('gh') or not shutil.which('keytool'):
        raise SetupError('Existing gh CLI and JDK keytool are required. This script installs nothing.')
    pins = [line.strip() for line in (ROOT / 'release/certificate.sha256').read_text().splitlines()
            if line.strip() and not line.lstrip().startswith('#')]
    if pins != [PIN]:
        raise SetupError('The versioned permanent certificate pin differs. No secrets were changed.')
    key = KEY.resolve(strict=True)
    if not key.is_file() or any((parent / '.git').exists() for parent in key.parents):
        raise SetupError('Restore the existing permanent keystore outside Git. No key will be created.')
    metadata = api('')
    if metadata['default_branch'] != 'main' or not metadata.get('permissions', {}).get('admin'):
        raise SetupError('This setup requires main as default branch and repository administrator access.')
    repository_names = set(gh('secret', 'list', '--repo', REPOSITORY, '--json', 'name', '--jq', '.[].name').decode().splitlines())
    if repository_names.intersection(SECRET_NAMES):
        raise SetupError('Release secret names already exist at repository scope. Move them to the main-only velora-release environment first; do not duplicate them.')
    # Environment secrets cannot be used by branches or tags other than main.
    api(f'environments/{ENVIRONMENT}', {'deployment_branch_policy': {
        'protected_branches': False, 'custom_branch_policies': True,
    }})
    policy_path = f'repos/{REPOSITORY}/environments/{ENVIRONMENT}/deployment-branch-policies'
    policies = json.loads(gh('api', policy_path))['branch_policies']
    if any(policy['name'] != 'main' or policy.get('type') != 'branch' for policy in policies):
        raise SetupError('The signing environment permits refs other than branch main. Restrict it before uploading secrets.')
    if not policies:
        gh('api', policy_path, '--method', 'POST', '--input', '-',
           payload=json.dumps({'name': 'main', 'type': 'branch'}).encode())
    print('Destination: https://github.com/' + REPOSITORY + '/settings/environments')
    print('Using the EXISTING permanent key. All credential input is hidden; nothing is saved locally.')
    alias = getpass.getpass('Key alias [Enter = velora-release]: ') or 'velora-release'
    store_password = getpass.getpass('Existing keystore password: ')
    key_password = getpass.getpass('Existing key password [Enter = keystore password]: ') or store_password
    api_id = getpass.getpass('Your Telegram API ID: ').strip()
    api_hash = getpass.getpass('Your Telegram API HASH: ').strip()
    if not store_password or not re.fullmatch('[1-9][0-9]*', api_id) or int(api_id) > 2147483647 or not re.fullmatch('[0-9a-fA-F]{32}', api_hash):
        raise SetupError('Invalid credential format. No secrets were uploaded.')
    env = dict(os.environ, VELORA_SETUP_STORE_PASSWORD=store_password, VELORA_SETUP_KEY_PASSWORD=key_password)
    export = subprocess.run(['keytool', '-exportcert', '-keystore', str(key), '-alias', alias,
                             '-storepass:env', 'VELORA_SETUP_STORE_PASSWORD'], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if export.returncode or hashlib.sha256(export.stdout).hexdigest() != PIN:
        raise SetupError('Cannot verify the existing keystore/alias against the permanent certificate. No secrets were uploaded.')
    # A certificate request exercises the private key and its password, in memory only.
    private_check = subprocess.run(['keytool', '-certreq', '-keystore', str(key), '-alias', alias,
                                    '-storepass:env', 'VELORA_SETUP_STORE_PASSWORD',
                                    '-keypass:env', 'VELORA_SETUP_KEY_PASSWORD'], env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if private_check.returncode:
        raise SetupError('Cannot open the existing private key with these passwords. No secrets were uploaded.')
    encoded = base64.b64encode(key.read_bytes())
    if len(encoded) > 48 * 1024:
        raise SetupError('Encoded keystore exceeds the GitHub secret size limit. No secrets were uploaded.')
    values = (encoded, store_password.encode(), alias.encode(), key_password.encode(), api_id.encode(), api_hash.encode())
    for name, value in zip(SECRET_NAMES, values):
        gh('secret', 'set', name, '--repo', REPOSITORY, '--env', ENVIRONMENT, payload=value)
        print('Configured:', name)
    print('Certificate SHA-256 verified:', PIN)
    print('Setup complete. Tell the agent only: GitHub Secrets настроены. Never paste secret values.')


if __name__ == '__main__':
    try:
        main()
    except SetupError as error:
        print('Setup stopped:', error, file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, KeyboardInterrupt, EOFError):
        # Avoid leaking subprocess input or exception content, including secrets.
        print('Setup stopped. Check the existing key, entered credentials, gh access, and main-only environment policy. '
              'No key was created. If upload was interrupted, rerun to replace all six secrets.', file=sys.stderr)
        sys.exit(1)
