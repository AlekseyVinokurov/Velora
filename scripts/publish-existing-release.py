#!/usr/bin/env python3
"""Promote only the explicitly authorized v0.1.2 CI artifact on a GitHub-hosted runner.

No Android toolchain, build, signing key or local APK download. The APK hash binds
its identity and verified signer to the immutable, successful CI verification receipt.
"""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = 'AlekseyVinokurov/Velora'
TAG = 'v0.1.2'
SOURCE = 'b2bc4a5781ccbe117c8ba083a06ff9ac9d748898'
RUN = 42797176789
ARTIFACT = 13755532358
ARCHIVE_SHA = '30e4bcaeb85a270d65e2b4775b0f879a204e932f8d37f04fd5059093fac5ca5a'
APK_NAME = 'Velora-0.1.2-100002.apk'
APK_SHA = 'c63ae85cf165168d062cafbf7365c4958357179e56e1d8d3481aa5d6e893f389'
PIN = 'a32f7e8239e47cc061ebabf226a88ab7dc3cf6224625f98ac6166b2966ea1842'
API = 'https://api.github.com/repos/' + REPO
LIMIT = 512 * 1024 * 1024
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
    with urllib.request.build_opener(Redirect()).open(req, timeout=120) as response:
        data = response.read(limit + 1)
    require(len(data) <= limit, 'Oversized response')
    return data


def api(path, method='GET', body=None):
    return parse(request(API + path, method, body))


def optional(path):
    try:
        return api(path)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def find_version():
    # Unpublished drafts are omitted by /releases/tags/{tag}; enumerate them explicitly.
    releases = api('/releases?per_page=100')
    matches = [release for release in releases if release.get('tag_name') == TAG]
    by_tag = optional('/releases/tags/' + TAG)
    if by_tag is not None and all(release['id'] != by_tag['id'] for release in matches):
        matches.append(by_tag)
    require(len(matches) <= 1, 'Multiple matching releases/drafts; no automatic changes allowed')
    return matches[0] if matches else None


def check_provenance(run, artifact):
    require(run['id'] == RUN and run['status'] == 'completed' and run['conclusion'] == 'success'
            and run['event'] == 'workflow_dispatch' and run['path'] == '.github/workflows/android-release.yml'
            and run['head_sha'] == SOURCE and run['head_branch'] == 'main' and run['run_attempt'] == 1
            and run['head_repository']['full_name'] == REPO, 'Build run provenance mismatch')
    require(artifact['id'] == ARTIFACT and artifact['expired'] is False
            and artifact['name'] == 'Velora-release-arm64-42797176789-1'
            and artifact['digest'] == 'sha256:' + ARCHIVE_SHA
            and artifact['workflow_run']['id'] == RUN and artifact['workflow_run']['head_sha'] == SOURCE
            and artifact['workflow_run']['head_branch'] == 'main', 'Artifact provenance mismatch')


def validate_receipt(receipt, actual_hash, actual_size):
    expected = {'applicationId': protocol.PACKAGE, 'versionCode': 100002, 'versionName': '0.1.2',
                'minSdk': 24, 'sha256': APK_SHA, 'certificateSha256': PIN, 'sourceCommit': SOURCE}
    require(set(receipt) == set(expected) | {'size'}, 'Unexpected CI receipt fields')
    for key, value in expected.items():
        require(type(receipt[key]) is type(value) and receipt[key] == value, 'CI receipt mismatch: ' + key)
    require(type(receipt['size']) is int and receipt['size'] == actual_size and 0 < actual_size <= LIMIT,
            'CI receipt APK size mismatch')
    require(actual_hash == APK_SHA, 'APK SHA-256 mismatch')
    metadata = {'schemaVersion': 1, 'repository': REPO, 'channel': 'stable',
                'applicationId': receipt['applicationId'], 'versionName': receipt['versionName'],
                'versionCode': receipt['versionCode'], 'tag': TAG, 'sourceCommit': SOURCE, 'minSdk': receipt['minSdk'],
                'apk': {'name': APK_NAME, 'size': actual_size, 'sha256': APK_SHA, 'certificateSha256': PIN}}
    return protocol.validate_metadata(metadata, PIN)


def check_build_jobs(response):
    require(response['total_count'] == 1 and len(response['jobs']) == 1, 'Unexpected build jobs')
    job = response['jobs'][0]
    require(job['run_id'] == RUN and job['run_attempt'] == 1 and job['conclusion'] == 'success'
            and job['runner_group_name'] == 'GitHub Actions', 'Build did not pass on a GitHub-hosted runner')
    for name in ('Build and verify with the existing permanent release key',
                 'Validate and install Firebase client config from protected environment',
                 'Verify Firebase configuration in the signed APK'):
        matches = [step for step in job['steps'] if step['name'] == name]
        require(len(matches) == 1 and matches[0]['conclusion'] == 'success',
                'Required CI verification did not pass: ' + name)


def unpack(data, destination):
    require(sha(data) == ARCHIVE_SHA, 'Artifact archive digest mismatch')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(sorted(archive.namelist()) == sorted([APK_NAME, 'stable-build.json']), 'Unexpected artifact entries')
        for entry in archive.infolist():
            require(not entry.is_dir() and not entry.flag_bits & 1 and 0 < entry.file_size <= LIMIT,
                    'Invalid artifact entry')
        receipt_info = archive.getinfo('stable-build.json')
        require(receipt_info.file_size <= 64 * 1024, 'Oversized receipt')
        receipt = parse(archive.read(receipt_info))
        digest = hashlib.sha256()
        size = 0
        target = destination / APK_NAME
        with archive.open(APK_NAME) as source, target.open('xb') as out:
            while chunk := source.read(1024 * 1024):
                size += len(chunk)
                require(size <= LIMIT, 'Oversized APK')
                digest.update(chunk)
                out.write(chunk)
    metadata = validate_receipt(receipt, digest.hexdigest(), size)
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


def check_assets(release, files):
    require(release['tag_name'] == TAG and release['target_commitish'] == SOURCE
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
        canonical = f'https://github.com/{REPO}/releases/download/{TAG}/{name}'
        if release.get('draft') is True:
            # GitHub assigns temporary untagged-* URLs until a draft is published.
            prefix = f'https://github.com/{REPO}/releases/download/untagged-'
            require(url == canonical or (url.startswith(prefix) and url.endswith('/' + name)
                    and '/' not in url[len(prefix):-(len(name)+1)]), 'Unexpected draft asset URL')
        else:
            require(url == canonical, 'Unexpected public release asset URL')


def main():
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and os.environ.get('GITHUB_REPOSITORY') == REPO and os.environ.get('GITHUB_REF') == 'refs/heads/main'
            and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'GitHub-hosted manual main run required')
    require(protocol.fingerprint() == PIN, 'Repository certificate pin changed')
    require(api('/immutable-releases')['enabled'] is True, 'GitHub immutable releases must be enabled')
    require(find_version() is None, 'v0.1.2 release/draft already exists; no overwrite or recreation permitted')
    require(optional('/git/ref/tags/' + TAG) is None, 'v0.1.2 tag already exists; manual inspection required')
    subprocess.run(['git', 'merge-base', '--is-ancestor', SOURCE, 'HEAD'], cwd=ROOT, check=True)
    immutable = ['.github/workflows/android-release.yml', 'gradle.properties', 'release/certificate.sha256',
                 'scripts/release.py', 'TMessagesProj/src/main/java/app/nimarkogram/messenger/updater/VeloraRelease.java',
                 'TMessagesProj/src/main/java/app/nimarkogram/messenger/updater/GitHubReleaseClient.java']
    subprocess.run(['git', 'diff', '--exit-code', SOURCE, 'HEAD', '--', *immutable], cwd=ROOT, check=True)
    previous = api('/releases/latest')
    require(previous['tag_name'] == 'v0.1.1' and previous['draft'] is False and previous['prerelease'] is False,
            'Expected previous stable v0.1.1')
    protocol.check_previous(100002, '0.1.2')
    check_provenance(api(f'/actions/runs/{RUN}'), api(f'/actions/artifacts/{ARTIFACT}'))
    check_build_jobs(api(f'/actions/runs/{RUN}/jobs?filter=latest&per_page=100'))
    directory = Path(os.environ['RUNNER_TEMP']) / 'velora-v0.1.2-promotion'
    directory.mkdir(mode=0o700)
    # This is the sole APK download, and the environment guard above requires a hosted runner.
    data = request(API + f'/actions/artifacts/{ARTIFACT}/zip', limit=LIMIT + 1024 * 1024)
    metadata = unpack(data, directory)
    del data
    updater_check = prepare_updater_check(directory)
    # Prove compatibility with the *installed client's production parser* before creating a draft.
    candidate = {'tag_name': TAG, 'target_commitish': SOURCE, 'draft': False, 'prerelease': False,
                 'assets': [{'name': name, 'size': path.stat().st_size, 'state': 'uploaded',
                             'digest': 'sha256:' + protocol.digest(path),
                             'browser_download_url': f'https://github.com/{REPO}/releases/download/{TAG}/{name}'}
                            for name, path in [(APK_NAME, directory / APK_NAME),
                                               ('velora-release.json', directory / 'velora-release.json')]]}
    candidate_path = directory / 'candidate.json'
    candidate_path.write_text(json.dumps(candidate))
    subprocess.run(updater_check + [str(directory / 'velora-release.json'), str(candidate_path)], check=True)
    notes = (ROOT / 'release/notes-v0.1.2.md').read_text()
    require(find_version() is None and optional('/git/ref/tags/' + TAG) is None,
            'Version appeared during verification; publication refused')
    draft = api('/releases', 'POST', {'tag_name': TAG, 'target_commitish': SOURCE, 'name': 'Velora 0.1.2',
                                    'body': notes, 'draft': True, 'prerelease': False})
    release_id = draft['id']
    print(f'Verifying private draft {release_id}; failures leave it unpublished.', flush=True)
    files = {name: (directory / name).read_bytes() for name in (APK_NAME, 'velora-release.json')}
    upload_base = draft['upload_url'].split('{', 1)[0]
    require(upload_base == f'https://uploads.github.com/repos/{REPO}/releases/{release_id}/assets', 'Unexpected upload URL')
    for name, content in files.items():
        request(upload_base + '?name=' + urllib.parse.quote(name), 'POST', content,
                content_type='application/octet-stream')
    complete = api(f'/releases/{release_id}')
    require(complete['draft'] is True, 'Release ceased to be a draft during staging')
    check_assets(complete, files)
    candidate_path.write_text(json.dumps({**complete, 'draft': False,
        'assets': [{**asset, 'browser_download_url': f'https://github.com/{REPO}/releases/download/{TAG}/{asset["name"]}'}
                   for asset in complete['assets']]}))
    subprocess.run(updater_check + [str(directory / 'velora-release.json'), str(candidate_path)], check=True)
    published = api(f'/releases/{release_id}', 'PATCH', {'draft': False, 'prerelease': False, 'make_latest': 'true'})
    require(published['draft'] is False and published.get('immutable') is True,
            'Release publication/immutability verification failed')
    check_assets(published, files)
    ref = api('/git/ref/tags/' + TAG)['object']
    require(ref['type'] == 'commit' and ref['sha'] == SOURCE, 'Published tag source mismatch')
    # Public API and actual production discovery (no token), allowing bounded CDN propagation.
    for attempt in range(5):
        try:
            public = parse(request(API + '/releases/latest', authenticated=False))
            require(public['draft'] is False and public['tag_name'] == TAG, 'Public latest endpoint has not propagated')
            check_assets(public, files)
            subprocess.run(updater_check, check=True)
            break
        except (ValueError, urllib.error.URLError, subprocess.CalledProcessError):
            if attempt == 4:
                raise
            time.sleep(10)
    url = f'https://github.com/{REPO}/releases/download/{TAG}/{APK_NAME}'
    print('PUBLISHED AND DISCOVERED: ' + url)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(f'## Velora 0.1.2 published\n\n[APK]({url})\n\n'
                      f'- Source: `{SOURCE}`\n- APK SHA-256: `{APK_SHA}`\n'
                      '- Existing CI receipt and archive verified; no Android build or SDK used.\n'
                      '- Both assets verified before leaving draft. Public production updater discovery: PASS.\n')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Publication stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
