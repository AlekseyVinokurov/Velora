#!/usr/bin/env python3
"""Verify and stage a Velora release locally. Never signs, uploads or publishes anything."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'io.github.alekseyvinokurov.velora'
REPOSITORY = 'AlekseyVinokurov/Velora'
MAX_APK = 512 * 1024 * 1024
ALLOWED_DOWNLOAD_HOSTS = {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com', 'github-releases.githubusercontent.com'}


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip()
    return result


def validate_metadata(metadata, pin):
    required = {'schemaVersion', 'repository', 'channel', 'applicationId', 'versionName', 'versionCode', 'tag', 'sourceCommit', 'minSdk', 'apk'}
    if not isinstance(metadata, dict) or set(metadata) != required:
        raise ValueError('Unknown or missing release metadata fields')
    version = metadata['versionName']
    code = metadata['versionCode']
    minimum = metadata['minSdk']
    if (type(metadata['schemaVersion']) is not int or metadata['schemaVersion'] != 1
            or metadata['repository'] != REPOSITORY or metadata['channel'] != 'stable' or metadata['applicationId'] != PACKAGE
            or not isinstance(version, str) or len(version) > 40
            or not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version)
            or type(code) is not int or not 0 < code <= 2100000000
            or metadata['tag'] != 'v' + version or not isinstance(metadata['sourceCommit'], str)
            or not re.fullmatch('[0-9a-f]{40}', metadata['sourceCommit'])
            or type(minimum) is not int or not 24 <= minimum <= 1000):
        raise ValueError('Invalid stable release identity/version metadata')
    apk = metadata['apk']
    if (not isinstance(apk, dict) or set(apk) != {'name', 'size', 'sha256', 'certificateSha256'}
            or apk['name'] != 'Velora-' + version + '-' + str(code) + '.apk'
            or type(apk['size']) is not int or not 0 < apk['size'] <= MAX_APK
            or not isinstance(apk['sha256'], str) or not re.fullmatch('[0-9a-f]{64}', apk['sha256'])
            or not re.fullmatch('[0-9a-f]{64}', pin) or apk['certificateSha256'] != pin):
        raise ValueError('Invalid or untrusted APK metadata')
    return metadata


def fingerprint():
    lines = [line.strip() for line in (ROOT / 'release/certificate.sha256').read_text().splitlines()
             if line.strip() and not line.lstrip().startswith('#')]
    if len(lines) != 1 or not re.fullmatch('[0-9a-f]{64}', lines[0]):
        raise ValueError('No real release certificate is pinned. Run create-release-key.sh interactively first.')
    return lines[0]


def clean_commit():
    if subprocess.check_output(['git', 'status', '--porcelain=v1', '--untracked-files=all'], cwd=ROOT, text=True):
        raise ValueError('Source tree is not clean. Commit reviewed changes before a stable release.')
    return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def sdk_directory():
    configured = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    local = ROOT / 'local.properties'
    if not configured and local.is_file():
        configured = properties(local).get('sdk.dir')
    return Path(configured) if configured else ROOT / '.local-build/android-sdk'


def command_output(command):
    completed = subprocess.run([str(part) for part in command], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, check=False)
    if completed.returncode:
        raise ValueError('Android verification tool failed: ' + completed.stdout[-4000:])
    return completed.stdout


def verify_apk(apk, pin=None, expected=None):
    apk = Path(apk).resolve()
    pin = fingerprint() if pin is None else pin
    expected = properties(ROOT / 'gradle.properties') if expected is None else expected
    if not apk.is_file() or not 0 < apk.stat().st_size <= MAX_APK:
        raise ValueError('APK missing or exceeds the 512 MiB limit')
    tools = sdk_directory() / 'build-tools/36.0.0'
    badging = command_output([tools / 'aapt2', 'dump', 'badging', apk])
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", badging, re.M)
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging, re.M)
    if not package or not minimum:
        raise ValueError('Cannot read Android package identity')
    application_id, code, version = package.groups()
    if application_id != PACKAGE or code != expected['APP_VERSION_CODE'] or version != expected['APP_VERSION_NAME']:
        raise ValueError('APK applicationId/version differs from the fixed stable project version')
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('Invalid stable versionName')
    if not 0 < int(code) <= 2100000000 or int(minimum[1]) != 24:
        raise ValueError('Unexpected stable versionCode or minSdk')
    verification = command_output([tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk])
    signers = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', verification, re.M)
    if len(signers) != 1 or signers[0].lower() != pin:
        raise ValueError('APK does not have the single trusted Velora signer')
    if not re.search(r'Verified using v[23] scheme[^\n]*: true', verification):
        raise ValueError('A verified APK v2 or v3 signature is required')
    if 'Signer #1 key algorithm: RSA' not in verification and 'Signer #1 public key algorithm: RSA' not in verification:
        raise ValueError('Expected the permanent RSA release certificate')
    key_size = re.search(r'Signer #1 (?:public )?key size \(bits\): ([0-9]+)', verification)
    if not key_size:
        raise ValueError('Cannot read the RSA signing key size from apksigner output')
    if int(key_size[1]) < 4096:
        raise ValueError('RSA release key is smaller than 4096 bits')
    # A keystore/password file is never an allowed distributable APK entry.
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('APK contains duplicate ZIP entries')
        for name in names:
            lower = name.lower()
            if re.search(r'\.(?:jks|keystore|p12|pfx)(?:\.|$)', lower) or Path(lower).name in {
                'private.properties', 'keystore.properties', '.env'
            }:
                raise ValueError('Signing material or private configuration found inside APK')
    return {'applicationId': application_id, 'versionCode': int(code), 'versionName': version,
            'minSdk': int(minimum[1]), 'sha256': digest(apk), 'size': apk.stat().st_size,
            'certificateSha256': pin}


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        parsed = urllib.parse.urlsplit(url)
        if (parsed.scheme != 'https' or parsed.hostname not in ALLOWED_DOWNLOAD_HOSTS or parsed.username or parsed.password
                or parsed.port not in {None, 443} or parsed.fragment):
            raise ValueError('Unexpected release asset redirect')
        return super().redirect_request(request, response, code, message, headers, url)


def public_json(url, limit, api=False):
    headers = {'User-Agent': 'Velora-local-release-verifier'}
    if api:
        headers.update({'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.build_opener(SafeRedirect()).open(request, timeout=30) as response:
        body = response.read(limit + 1)
    if len(body) > limit:
        raise ValueError('Oversized release response')
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON field in release response')
            result[key] = value
        return result
    return json.loads(body, object_pairs_hook=unique_object)


def check_previous(version_code, version_name):
    try:
        release = public_json('https://api.github.com/repos/' + REPOSITORY + '/releases/latest', 512 * 1024, api=True)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise
    if release.get('draft') is not False or release.get('prerelease') is not False:
        raise ValueError('Unexpected draft/prerelease from the stable endpoint')
    matches = [asset for asset in release.get('assets', []) if asset.get('name') == 'velora-release.json']
    if len(matches) != 1:
        raise ValueError('Published release has missing/ambiguous metadata; review it manually before preparing a successor')
    tag = release.get('tag_name', '')
    if not re.fullmatch(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', tag):
        raise ValueError('Unexpected published release tag')
    url = 'https://github.com/' + REPOSITORY + '/releases/download/' + tag + '/velora-release.json'
    if matches[0].get('browser_download_url') != url:
        raise ValueError('Unexpected published metadata URL')
    metadata = public_json(url, 64 * 1024)
    validate_metadata(metadata, fingerprint())
    previous = metadata['versionCode']
    if metadata['tag'] != tag:
        raise ValueError('Published release tag and metadata differ')
    if version_code <= previous:
        raise ValueError('versionCode must be greater than the latest published stable APK')
    if tuple(map(int, version_name.split('.'))) <= tuple(map(int, tag[1:].split('.'))):
        raise ValueError('Use a new, greater versionName/tag. Published release versions are never replaced.')
    return previous


def prepare(apk, receipt_path, notes_path, output):
    commit = clean_commit()
    verified = verify_apk(apk)
    receipt = json.loads(Path(receipt_path).read_text())
    if receipt.get('sourceCommit') != commit or any(receipt.get(key) != value for key, value in verified.items()):
        raise ValueError('Build receipt does not match this APK and source commit. Rebuild with scripts/build-stable.sh.')
    check_previous(verified['versionCode'], verified['versionName'])  # Fails closed on network/auth/metadata errors, except a genuine HTTP 404.
    tag = 'v' + verified['versionName']
    name = 'Velora-' + verified['versionName'] + '-' + str(verified['versionCode']) + '.apk'
    metadata = {'schemaVersion': 1, 'repository': REPOSITORY, 'channel': 'stable', 'applicationId': PACKAGE,
                'versionName': verified['versionName'], 'versionCode': verified['versionCode'], 'tag': tag,
                'sourceCommit': commit, 'minSdk': verified['minSdk'],
                'apk': {key: verified[key] for key in ['size', 'sha256', 'certificateSha256']}}
    metadata['apk']['name'] = name
    validate_metadata(metadata, fingerprint())
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    target = output / tag
    if target.exists():
        raise ValueError('Release directory already exists. Do not silently replace a prepared/published version.')
    notes = Path(notes_path).read_text()
    submodules = []
    for line in subprocess.check_output(['git', 'ls-tree', '-r', 'HEAD'], cwd=ROOT, text=True).splitlines():
        info, path = line.split('\t', 1)
        mode, _, sha = info.split()
        if mode == '160000':
            submodules.append({'path': path, 'commit': sha})
    with tempfile.TemporaryDirectory(prefix='prepare-', dir=output) as temporary:
        staging = Path(temporary) / tag
        staging.mkdir()
        shutil.copyfile(apk, staging / name)
        if digest(staging / name) != verified['sha256']:
            raise ValueError('APK changed during release preparation')
        (staging / 'velora-release.json').write_text(json.dumps(metadata, indent=2) + '\n')
        (staging / 'SOURCE.json').write_text(json.dumps({'repository': REPOSITORY, 'commit': commit, 'submodules': submodules}, indent=2) + '\n')
        (staging / 'RELEASE_NOTES.md').write_text(notes)
        files = [name, 'velora-release.json', 'SOURCE.json', 'RELEASE_NOTES.md']
        (staging / 'SHA256SUMS').write_text(''.join(digest(staging / item) + '  ' + item + '\n' for item in files))
        # Only explicitly enumerated public artifacts are staged. No keystore/configuration directory is copied.
        if clean_commit() != commit:
            raise ValueError('Source changed during release preparation')
        staging.rename(target)
    print('Prepared locally:', target)
    print('Tag:', tag, '| source:', commit, '| versionCode:', verified['versionCode'])
    print('Nothing was uploaded or published. Publish only after reviewing the APK, notes, source and checksums.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    verify = commands.add_parser('verify')
    verify.add_argument('apk', type=Path)
    verify.add_argument('--receipt', type=Path)
    stage = commands.add_parser('prepare')
    stage.add_argument('apk', type=Path)
    stage.add_argument('--receipt', type=Path, default=ROOT / '.local-build/stable-build.json')
    stage.add_argument('--notes', type=Path, required=True)
    stage.add_argument('--output', type=Path, default=ROOT / '.local-build/releases')
    args = parser.parse_args()
    if args.command == 'verify':
        result = verify_apk(args.apk)
        if args.receipt:
            result['sourceCommit'] = clean_commit()
            args.receipt.parent.mkdir(parents=True, exist_ok=True)
            args.receipt.write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    else:
        prepare(args.apk, args.receipt, args.notes, args.output)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print('Release verification failed:', error, file=sys.stderr)
        sys.exit(1)
