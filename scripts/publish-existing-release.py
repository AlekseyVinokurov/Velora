#!/usr/bin/env python3
"""Manually promote one verified Android CI artifact on a GitHub-hosted runner.

No Android toolchain, build, signing key or local APK download. The APK hash binds
its identity and verified signer to the immutable, successful CI verification receipt.
"""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = 'AlekseyVinokurov/Velora'
PIN = 'a32f7e8239e47cc061ebabf226a88ab7dc3cf6224625f98ac6166b2966ea1842'
API = 'https://api.github.com/repos/' + REPO
LIMIT = 512 * 1024 * 1024
PUBLICATION_FILES = frozenset({
    '.github/workflows/publish-existing-release.yml',
    'scripts/publish-existing-release.py',
    'tests/publish/test_publish.py',
    'tests/publish/UpdaterDiscoveryCheck.java',
    'docs/PUBLISHING.ru.md',
})
spec = importlib.util.spec_from_file_location('velora_release_protocol', ROOT / 'scripts/release.py')
protocol = importlib.util.module_from_spec(spec)
spec.loader.exec_module(protocol)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key: ' + key)
        result[key] = value
    return result


def parse(data):
    return json.loads(data, object_pairs_hook=unique)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        parsed = urllib.parse.urlsplit(url)
        host = parsed.hostname or ''
        require(parsed.scheme == 'https' and not parsed.username and not parsed.password
                and parsed.port in (None, 443) and (host in protocol.ALLOWED_DOWNLOAD_HOSTS
                or host.endswith('.blob.core.windows.net') or host.endswith('.actions.githubusercontent.com')),
                'Unexpected GitHub artifact redirect')
        redirected = super().redirect_request(request, response, code, message, headers, url)
        if host != urllib.parse.urlsplit(request.full_url).hostname:
            redirected.remove_header('Authorization')
        return redirected


class GitHubAPIError(ValueError):
    def __init__(self, code, method, url, body):
        self.code = code
        endpoint = urllib.parse.urlsplit(url).path
        try:
            message = json.loads(body).get('message', '')
        except (ValueError, AttributeError, UnicodeError):
            message = ''
        if not isinstance(message, str):
            message = ''
        for name, value in os.environ.items():
            if value and re.search(r'TOKEN|SECRET|PASSWORD|AUTHORIZATION|KEY|API_HASH', name, re.I):
                message = message.replace(value, '[redacted]')
        message = re.sub(r'https?://\S+', '[url]', message)
        message = re.sub(r'(?i)(?:authorization|bearer|token|password|secret)\s*[:= ]+[^\s,;]+',
                         '[redacted]', message)
        message = re.sub(r'(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+)', '[redacted]', message)
        message = ''.join(c if c.isprintable() else ' ' for c in message)[:400]
        super().__init__(f'GitHub API HTTP {code}: {method} {endpoint}: '
                         + (message or 'error details unavailable'))


def request(url, method='GET', body=None, authenticated=True, limit=2 * 1024 * 1024, content_type=None):
    headers = {'User-Agent': 'Velora-authorized-artifact-promotion', 'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': '2022-11-28'}
    if authenticated:
        require(urllib.parse.urlsplit(url).hostname in ('api.github.com', 'uploads.github.com'), 'Token host refused')
        headers['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        content_type = 'application/json'
    if content_type:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.build_opener(Redirect()).open(req, timeout=120) as response:
            data = response.read(limit + 1)
    except urllib.error.HTTPError as error:
        with error:
            details = error.read(64 * 1024)
        raise GitHubAPIError(error.code, method, url, details) from None
    require(len(data) <= limit, 'Oversized response')
    return data


def api(path, method='GET', body=None):
    return parse(request(API + path, method, body))


def optional(path):
    try:
        return api(path)
    except GitHubAPIError as error:
        if error.code == 404:
            return None
        raise


def find_version(tag):
    # Unpublished drafts are omitted by /releases/tags/{tag}; enumerate them explicitly.
    releases = pages('/releases')
    matches = [release for release in releases if release.get('tag_name') == tag]
    by_tag = optional('/releases/tags/' + tag)
    if by_tag is not None and all(release['id'] != by_tag['id'] for release in matches):
        matches.append(by_tag)
    require(len(matches) <= 1, 'Multiple matching releases/drafts; no automatic changes allowed')
    return matches[0] if matches else None


def check_current_main(target):
    require(api('')['default_branch'] == 'main', 'Default branch is no longer main')
    require(api('/branches/main')['commit']['sha'] == target,
            'Main advanced after dispatch; publication refused')


def publication_target(source):
    target = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    require(re.fullmatch(r'[0-9a-f]{40}', target) is not None
            and os.environ.get('GITHUB_SHA') == target, 'Checkout does not match dispatched commit')
    subprocess.run(['git', 'merge-base', '--is-ancestor', source, target], cwd=ROOT, check=True)
    require(not subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'],
                                        cwd=ROOT, text=True), 'Tracked publication files are modified')
    # Inspect every intervening commit, including changes later reverted and both
    # sides of renames. A final tree diff alone would miss those changes.
    paths = subprocess.check_output(['git', 'log', '--format=', '--name-only', '-z', '--no-renames',
                                     '--diff-merges=first-parent',
                                     source + '..' + target], cwd=ROOT, text=True)
    changed = {path for path in paths.split('\0') if path}
    require(all(path in PUBLICATION_FILES or re.fullmatch(r'release/notes-v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.md', path)
                for path in changed), 'Non-publication changes after APK source; publication refused')
    check_current_main(target)
    return target


def run_id(value):
    require(isinstance(value, str) and re.fullmatch(r'[1-9][0-9]{0,18}', value) is not None,
            'run_id must be a positive decimal Android build run ID')
    return int(value)


def pages(path, key=None):
    for page in range(1, 101):
        result = api(path + ('&' if '?' in path else '?') + f'per_page=100&page={page}')
        items = result[key] if key else result
        require(isinstance(items, list), 'Invalid GitHub collection')
        yield from items
        if len(items) < 100:
            return
    raise ValueError('GitHub collection exceeds safe pagination limit')


def check_run(run, requested):
    require(type(run['id']) is int and run['id'] == requested and run['status'] == 'completed'
            and run['conclusion'] == 'success' and run['event'] == 'workflow_dispatch'
            and run['path'] == '.github/workflows/android-release.yml' and run['head_branch'] == 'main'
            and run['head_repository']['full_name'] == REPO and run['repository']['full_name'] == REPO
            and isinstance(run['head_sha'], str) and re.fullmatch(r'[0-9a-f]{40}', run['head_sha'])
            and type(run['run_attempt']) is int and run['run_attempt'] > 0,
            'Expected a successful official Android release workflow run from main')


def select_artifact(run, artifacts):
    pattern = rf"Velora-release-(arm64|universal)-{run['id']}-{run['run_attempt']}"
    matches = [artifact for artifact in artifacts if re.fullmatch(pattern, artifact['name'])]
    require(len(matches) == 1, 'Expected exactly one release Artifact for this build attempt')
    artifact = matches[0]
    require(type(artifact['id']) is int and artifact['id'] > 0 and artifact['expired'] is False
            and isinstance(artifact.get('digest'), str)
            and re.fullmatch(r'sha256:[0-9a-f]{64}', artifact['digest'])
            and artifact['workflow_run']['id'] == run['id']
            and artifact['workflow_run']['head_sha'] == run['head_sha']
            and artifact['workflow_run']['head_branch'] == 'main', 'Artifact provenance/expiry/digest mismatch')
    return artifact


def validate_receipt(receipt, source):
    fields = {'applicationId', 'versionCode', 'versionName', 'minSdk', 'sha256',
              'certificateSha256', 'sourceCommit', 'size'}
    require(isinstance(receipt, dict) and set(receipt) == fields, 'Unexpected CI receipt fields')
    require(receipt['sourceCommit'] == source and type(receipt['minSdk']) is int
            and receipt['minSdk'] == 24, 'CI receipt source/minSdk mismatch')
    # The authenticated Artifact digest binds these bytes to the successful CI
    # signature/package verification. No Android SDK or signing key is used here.
    version, code = receipt['versionName'], receipt['versionCode']
    metadata = {'schemaVersion': 1, 'repository': REPO, 'channel': 'stable',
                'applicationId': receipt['applicationId'], 'versionName': version,
                'versionCode': code, 'tag': 'v' + str(version), 'sourceCommit': source, 'minSdk': receipt['minSdk'],
                'apk': {'name': f'Velora-{version}-{code}.apk', 'size': receipt['size'],
                        'sha256': receipt['sha256'], 'certificateSha256': receipt['certificateSha256']}}
    return protocol.validate_metadata(metadata, PIN)


def check_build_jobs(run, jobs):
    require(len(jobs) == 1, 'Unexpected build jobs')
    job = jobs[0]
    require(job['run_id'] == run['id'] and job['run_attempt'] == run['run_attempt']
            and job['conclusion'] == 'success' and job['runner_group_name'] == 'GitHub Actions',
            'Build did not pass on a GitHub-hosted runner')
    for name in ('Build and verify with the existing permanent release key',
                 'Validate and install Firebase client config from protected environment',
                 'Verify Firebase configuration in the signed APK'):
        matches = [step for step in job['steps'] if step['name'] == name]
        require(len(matches) == 1 and matches[0]['conclusion'] == 'success',
                'Required CI verification did not pass: ' + name)


def unpack(data, destination, run, artifact):
    require(sha(data) == artifact['digest'][7:], 'Artifact archive digest mismatch')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        require(len(entries) == 2 and len(set(archive.namelist())) == 2, 'Unexpected artifact entries')
        for entry in entries:
            require(not entry.is_dir() and not entry.flag_bits & 1 and 0 < entry.file_size <= LIMIT,
                    'Invalid artifact entry')
        receipt_info = archive.getinfo('stable-build.json')
        require(receipt_info.file_size <= 64 * 1024, 'Oversized receipt')
        metadata = validate_receipt(parse(archive.read(receipt_info)), run['head_sha'])
        name = metadata['apk']['name']
        require(sorted(archive.namelist()) == sorted([name, 'stable-build.json']), 'Unexpected artifact entries')
        digest = hashlib.sha256()
        size = 0
        with archive.open(name) as source, (destination / name).open('xb') as out:
            while chunk := source.read(1024 * 1024):
                size += len(chunk)
                require(size <= LIMIT, 'Oversized APK')
                digest.update(chunk)
                out.write(chunk)
    require(size == metadata['apk']['size'], 'CI receipt APK size mismatch')
    require(digest.hexdigest() == metadata['apk']['sha256'], 'APK SHA-256 mismatch')
    (destination / 'velora-release.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return metadata


def prepare_updater_check(directory):
    # Same versioned JARs/pins as tests/release/run.py; neither is an Android SDK.
    jars = [
        ('gson.jar', 'https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar',
         '57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b'),
        ('json.jar', 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',
         '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'),
    ]
    for name, url, expected in jars:
        data = request(url, authenticated=False, limit=1024 * 1024)
        require(sha(data) == expected, 'Updater test dependency digest mismatch')
        (directory / name).write_bytes(data)
    classpath = os.pathsep.join(str(directory / name) for name, _, _ in jars)
    sources = ROOT / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/updater'
    subprocess.run(['javac', '--release', '17', '-cp', classpath, '-d', str(directory),
                    str(sources / 'VeloraRelease.java'), str(sources / 'GitHubReleaseClient.java'),
                    str(ROOT / 'tests/publish/UpdaterDiscoveryCheck.java')], check=True)
    return ['java', '-cp', str(directory) + os.pathsep + classpath, 'UpdaterDiscoveryCheck', PIN]


def check_assets(release, files, target, metadata):
    tag = metadata['tag']
    require(release['tag_name'] == tag and release['target_commitish'] == target
            and release['prerelease'] is False, 'Release identity mismatch')
    assets = release['assets']
    require(len(assets) == len(files), 'Incomplete or unexpected release assets')
    for name, data in files.items():
        matches = [asset for asset in assets if asset['name'] == name]
        require(len(matches) == 1, 'Missing or duplicate release asset: ' + name)
        asset = matches[0]
        require(asset['state'] == 'uploaded' and asset['size'] == len(data)
                and asset['digest'] == 'sha256:' + sha(data),
                'Release asset verification failed: ' + name)
        url = asset['browser_download_url']
        canonical = f'https://github.com/{REPO}/releases/download/{tag}/{name}'
        if release.get('draft') is True:
            # GitHub assigns temporary untagged-* URLs until a draft is published.
            prefix = f'https://github.com/{REPO}/releases/download/untagged-'
            require(url == canonical or (url.startswith(prefix) and url.endswith('/' + name)
                    and '/' not in url[len(prefix):-(len(name)+1)]), 'Unexpected draft asset URL')
        else:
            require(url == canonical, 'Unexpected public release asset URL')


def release_notes(metadata, run, artifact, target):
    path = ROOT / 'release' / ('notes-' + metadata['tag'] + '.md')
    text = path.read_text() if path.is_file() else (
        f"Velora {metadata['versionName']} ({metadata['versionCode']}).\n\n"
        'Готовый проверенный APK из GitHub Actions, без пересборки. '
        'Постоянная подпись и applicationId сохранены. '
        'Устанавливайте поверх существующей Velora, не удаляя приложение.\n')
    require(len(text.encode()) <= 32 * 1024, 'Release notes exceed safe limit')
    marker = {'run': run['id'], 'attempt': run['run_attempt'], 'artifact': artifact['id'],
              'archive': artifact['digest'], 'source': metadata['sourceCommit'], 'target': target}
    return text.rstrip() + '\n\n<!-- velora-promotion:' + json.dumps(marker, sort_keys=True) + ' -->\n'


def check_existing(existing, files, target, metadata, notes):
    if existing is None:
        return
    require(existing['draft'] is True, 'Version is already published; no overwrite permitted')
    require(existing['author']['login'] == 'github-actions[bot]' and existing['body'] == notes
            and existing['name'] == 'Velora ' + metadata['versionName']
            and existing.get('immutable') is not True, 'Unrecognized draft; manual inspection required')
    # Only a complete, identical draft from this exact build and publication
    # commit may resume. Never replace, delete or re-upload any existing asset.
    check_assets(existing, files, target, metadata)


def previous_version_code(metadata):
    try:
        return protocol.check_previous(metadata['versionCode'], metadata['versionName']) or 0
    except urllib.error.HTTPError as error:
        # The shared release protocol also fetches public metadata with GET.
        # Give its failures the same bounded, redacted diagnostics as our API.
        with error:
            details = error.read(64 * 1024)
        raise GitHubAPIError(error.code, 'GET', error.url, details) from None


def candidate_release(metadata, files, target):
    tag = metadata['tag']
    return {'tag_name': tag, 'target_commitish': target, 'draft': False, 'prerelease': False,
            'assets': [{'name': name, 'size': len(data), 'state': 'uploaded',
                        'digest': 'sha256:' + sha(data),
                        'browser_download_url': f'https://github.com/{REPO}/releases/download/{tag}/{name}'}
                       for name, data in files.items()]}


def main():
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and os.environ.get('GITHUB_REPOSITORY') == REPO and os.environ.get('GITHUB_REF') == 'refs/heads/main'
            and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'GitHub-hosted manual main run required')
    requested = run_id(os.environ.get('RUN_ID'))
    require(protocol.fingerprint() == PIN, 'Repository certificate pin changed')
    run = api(f'/actions/runs/{requested}')
    check_run(run, requested)
    target = publication_target(run['head_sha'])
    check_build_jobs(run, list(pages(f'/actions/runs/{requested}/jobs?filter=latest', 'jobs')))
    artifact = select_artifact(run, list(pages(f'/actions/runs/{requested}/artifacts', 'artifacts')))
    directory = Path(os.environ['RUNNER_TEMP']) / f'velora-promotion-{requested}'
    directory.mkdir(mode=0o700)
    # Sole APK download: reachable only after the hosted-runner guard and provenance checks.
    data = request(API + f"/actions/artifacts/{artifact['id']}/zip", limit=LIMIT + 1024 * 1024)
    metadata = unpack(data, directory, run, artifact)
    del data
    tag, name = metadata['tag'], metadata['apk']['name']
    files = {asset: (directory / asset).read_bytes() for asset in (name, 'velora-release.json')}
    notes = release_notes(metadata, run, artifact, target)
    existing = find_version(tag)
    check_existing(existing, files, target, metadata, notes)
    require(optional('/git/ref/tags/' + tag) is None, 'Tag already exists; no overwrite permitted')
    previous_code = previous_version_code(metadata)
    updater_check = prepare_updater_check(directory) + [str(directory / 'velora-release.json'), str(previous_code)]
    candidate_path = directory / 'candidate.json'
    candidate_path.write_text(json.dumps(candidate_release(metadata, files, target)))
    subprocess.run(updater_check + [str(candidate_path)], check=True)
    check_current_main(target)
    if existing is None:
        require(find_version(tag) is None and optional('/git/ref/tags/' + tag) is None,
                'Version appeared during verification; publication refused')
        draft = api('/releases', 'POST', {'tag_name': tag, 'target_commitish': target,
                                        'name': 'Velora ' + metadata['versionName'],
                                        'body': notes, 'draft': True, 'prerelease': False})
        upload_base = draft['upload_url'].split('{', 1)[0]
        require(upload_base == f"https://uploads.github.com/repos/{REPO}/releases/{draft['id']}/assets",
                'Unexpected upload URL')
        for asset, content in files.items():
            request(upload_base + '?name=' + urllib.parse.quote(asset), 'POST', content,
                    content_type='application/octet-stream')
    else:
        draft = existing
    release_id = draft['id']
    print(f'Verifying private draft {release_id}; failures leave it unpublished.', flush=True)
    complete = api(f'/releases/{release_id}')
    check_existing(complete, files, target, metadata, notes)
    candidate_path.write_text(json.dumps({**complete, 'draft': False,
        'assets': [{**asset, 'browser_download_url': f'https://github.com/{REPO}/releases/download/{tag}/{asset["name"]}'}
                   for asset in complete['assets']]}))
    subprocess.run(updater_check + [str(candidate_path)], check=True)
    check_current_main(target)
    require(optional('/git/ref/tags/' + tag) is None, 'Tag appeared during staging; publication refused')
    published = api(f'/releases/{release_id}', 'PATCH', {'draft': False, 'prerelease': False, 'make_latest': 'true'})
    require(published['draft'] is False and published.get('immutable') is True,
            'Release publication/immutability verification failed')
    check_assets(published, files, target, metadata)
    # Confirm the published resource, not just the PATCH response.
    confirmed = api(f'/releases/{release_id}')
    require(confirmed['draft'] is False and confirmed.get('immutable') is True,
            'GitHub has not confirmed the immutable published release')
    check_assets(confirmed, files, target, metadata)
    ref = api('/git/ref/tags/' + tag)['object']
    require(ref['type'] == 'commit' and ref['sha'] == target, 'Published tag target mismatch')
    # Read-only checks allow bounded CDN propagation; publication is never retried.
    for attempt in range(5):
        try:
            public = parse(request(API + '/releases/latest', authenticated=False))
            require(public['draft'] is False and public['tag_name'] == tag, 'Public latest endpoint has not propagated')
            check_assets(public, files, target, metadata)
            subprocess.run(updater_check, check=True)
            break
        except (ValueError, urllib.error.URLError, subprocess.CalledProcessError):
            if attempt == 4:
                raise
            time.sleep(10)
    release_url = f'https://github.com/{REPO}/releases/tag/{tag}'
    url = f'https://github.com/{REPO}/releases/download/{tag}/{name}'
    print('PUBLISHED AND DISCOVERED: ' + url)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(f'## Velora {metadata["versionName"]} published\n\n[Release]({release_url}) · [APK]({url})\n\n'
                      f'- Build: `{requested}`, Artifact: `{artifact["id"]}`\n'
                      f'- APK source: `{metadata["sourceCommit"]}`\n- Release/tag target: `{target}`\n'
                      f'- APK SHA-256: `{metadata["apk"]["sha256"]}`\n'
                      '- Existing CI receipt and archive verified; no Android build or SDK used.\n'
                      '- Both assets verified before leaving draft. Public production updater discovery: PASS.\n')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Publication stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
