#!/usr/bin/env python3
"""Host-only promotion tests: synthetic bytes and mocked GitHub, never real APKs."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('promotion', ROOT / 'scripts/publish-existing-release.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
SOURCE, TARGET = 'b' * 40, 'a' * 40
RUN, ARTIFACT = 123456, 654321
VERSION, CODE = '2.4.0', 100047
NAME = f'Velora-{VERSION}-{CODE}.apk'
PAYLOAD = b'Synthetic integrity fixture; this is not an Android package.'
HOSTED = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted',
          'GITHUB_REPOSITORY': p.REPO, 'GITHUB_REF': 'refs/heads/main',
          'GITHUB_EVENT_NAME': 'workflow_dispatch', 'RUN_ID': str(RUN), 'GITHUB_SHA': TARGET}


def receipt():
    return {'applicationId': p.protocol.PACKAGE, 'versionCode': CODE, 'versionName': VERSION,
            'minSdk': 24, 'sha256': p.sha(PAYLOAD), 'certificateSha256': p.PIN,
            'sourceCommit': SOURCE, 'size': len(PAYLOAD)}


def build():
    return {'id': RUN, 'status': 'completed', 'conclusion': 'success', 'event': 'workflow_dispatch',
            'path': '.github/workflows/android-release.yml', 'head_sha': SOURCE, 'head_branch': 'main',
            'run_attempt': 2, 'head_repository': {'full_name': p.REPO}, 'repository': {'full_name': p.REPO}}


def archive(record=None, payload=PAYLOAD, extra=None):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as out:
        out.writestr(NAME, payload)
        out.writestr('stable-build.json', json.dumps(receipt() if record is None else record))
        if extra: out.writestr(extra, b'unexpected')
    return buffer.getvalue()


def artifact(data=None):
    return {'id': ARTIFACT, 'expired': False, 'name': f'Velora-release-arm64-{RUN}-2',
            'digest': 'sha256:' + p.sha(archive() if data is None else data),
            'workflow_run': {'id': RUN, 'head_sha': SOURCE, 'head_branch': 'main'}}


def jobs():
    return [{'run_id': RUN, 'run_attempt': 2, 'conclusion': 'success', 'runner_group_name': 'GitHub Actions',
             'steps': [{'name': name, 'conclusion': 'success'} for name in (
                 'Build and verify with the existing permanent release key',
                 'Validate and install Firebase client config from protected environment',
                 'Verify Firebase configuration in the signed APK')]}]


def metadata():
    return p.validate_receipt(receipt(), SOURCE)


def files():
    return {NAME: PAYLOAD, 'velora-release.json': (json.dumps(metadata(), indent=2) + '\n').encode()}


def release():
    return p.candidate_release(metadata(), files(), TARGET)


class PromotionTest(unittest.TestCase):
    def test_run_id_and_successful_official_run(self):
        self.assertEqual(p.run_id(str(RUN)), RUN)
        for invalid in (None, '', '0', '-1', ' 123', '1;echo bad', '1.0', '9' * 20, True):
            with self.subTest(value=invalid), self.assertRaises(ValueError): p.run_id(invalid)
        p.check_run(build(), RUN)
        for key, value in [('id', RUN + 1), ('conclusion', 'failure'), ('status', 'in_progress'),
                           ('head_branch', 'other'), ('event', 'pull_request'), ('run_attempt', 0),
                           ('head_repository', {'full_name': 'other/repo'}),
                           ('path', '.github/workflows/other.yml'), ('head_sha', 'bad')]:
            with self.subTest(key=key), self.assertRaises(ValueError): p.check_run({**build(), key: value}, RUN)

    def test_unique_artifact_origin_expiry_and_digest(self):
        self.assertEqual(p.select_artifact(build(), [artifact()])['id'], ARTIFACT)
        for candidates in ([], [artifact(), artifact()], [{**artifact(), 'expired': True}],
                           [{**artifact(), 'digest': None}], [{**artifact(), 'name': 'untrusted'}],
                           [{**artifact(), 'workflow_run': {'id': RUN + 1, 'head_sha': SOURCE, 'head_branch': 'main'}}]):
            with self.subTest(candidates=candidates), self.assertRaises(ValueError):
                p.select_artifact(build(), candidates)
        # Older attempts and unrelated artifacts are not silently selected.
        self.assertEqual(p.select_artifact(build(), [artifact(), {**artifact(), 'name': f'Velora-release-arm64-{RUN}-1'}])['id'], ARTIFACT)

    def test_hosted_jobs_and_required_ci_gates(self):
        p.check_build_jobs(build(), jobs())
        for bad in ([], [{**jobs()[0], 'runner_group_name': 'self-hosted'}],
                    [{**jobs()[0], 'steps': []}], [{**jobs()[0], 'run_attempt': 1}],
                    [{**jobs()[0], 'conclusion': 'failure'}]):
            with self.assertRaises(ValueError): p.check_build_jobs(build(), bad)

    def test_receipt_identity_and_metadata(self):
        m = metadata()
        self.assertEqual((m['versionName'], m['versionCode'], m['sourceCommit']), (VERSION, CODE, SOURCE))
        self.assertEqual(m['apk']['name'], NAME)
        self.assertEqual(p.protocol.validate_metadata(m, p.PIN), m)
        for key, value in [('applicationId', 'foreign.package'), ('versionName', '../bad'),
                           ('versionCode', True), ('versionCode', 0), ('minSdk', 25),
                           ('certificateSha256', '0' * 64), ('sourceCommit', TARGET),
                           ('sha256', 'invalid'), ('size', False), ('unexpected', 1)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                p.validate_receipt({**receipt(), key: value}, SOURCE)

    def test_archive_and_apk_bytes_are_bound_to_receipt(self):
        for data, valid in [(archive(), True), (archive(payload=PAYLOAD + b'tamper'), False),
                            (archive(record={**receipt(), 'sha256': '0' * 64}), False),
                            (archive(extra='../unexpected'), False),
                            (archive(record={**receipt(), 'size': len(PAYLOAD) + 1}), False),
                            (archive(record={**receipt(), 'certificateSha256': '0' * 64}), False)]:
            with tempfile.TemporaryDirectory() as directory:
                a = artifact(data)
                if valid:
                    m = p.unpack(data, Path(directory), build(), a)
                    self.assertEqual((Path(directory) / NAME).read_bytes(), PAYLOAD)
                    self.assertEqual(m, metadata())
                else:
                    with self.assertRaises(ValueError): p.unpack(data, Path(directory), build(), a)
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            p.unpack(archive(), Path(directory), build(), {**artifact(), 'digest': 'sha256:' + '0' * 64})

    def test_newer_version_required_and_no_latest_bootstrap(self):
        previous = {**metadata(), 'versionName': '2.3.9', 'versionCode': CODE - 1, 'tag': 'v2.3.9',
                    'apk': {**metadata()['apk'], 'name': f'Velora-2.3.9-{CODE-1}.apk'}}
        url = f'https://github.com/{p.REPO}/releases/download/v2.3.9/velora-release.json'
        latest = {'tag_name': 'v2.3.9', 'draft': False, 'prerelease': False,
                  'assets': [{'name': 'velora-release.json', 'browser_download_url': url}]}
        with patch.object(p.protocol, 'public_json', side_effect=[latest, previous]):
            self.assertEqual(p.protocol.check_previous(CODE, VERSION), CODE - 1)
        for code, version in ((CODE - 1, VERSION), (CODE, '2.3.9'), (CODE - 2, '2.3.8')):
            with patch.object(p.protocol, 'public_json', side_effect=[latest, previous]), self.assertRaises(ValueError):
                p.protocol.check_previous(code, version)
        with patch.object(p.protocol, 'public_json', side_effect=urllib.error.HTTPError(url, 404, 'missing', {}, None)):
            self.assertIsNone(p.protocol.check_previous(CODE, VERSION))
        denied = urllib.error.HTTPError(p.API + '/releases/latest', 403, 'Forbidden', {},
                                       io.BytesIO(b'{"message":"API rate limit exceeded"}'))
        with patch.object(p.protocol, 'public_json', side_effect=denied):
            with self.assertRaises(p.GitHubAPIError) as caught:
                p.previous_version_code(metadata())
            self.assertIn('HTTP 403: GET /repos/' + p.REPO + '/releases/latest', str(caught.exception))

    def test_release_assets_and_safe_complete_draft_resume(self):
        r, f, m = release(), files(), metadata()
        p.check_assets(r, f, TARGET, m)
        notes = 'verified promotion marker'
        draft = {**r, 'id': 99, 'name': 'Velora ' + VERSION, 'draft': True, 'body': notes,
                 'author': {'login': 'github-actions[bot]'}}
        p.check_existing(draft, f, TARGET, m, notes)
        p.check_existing(None, f, TARGET, m, notes)
        for bad in ({**draft, 'draft': False}, {**draft, 'body': 'different build'},
                    {**draft, 'author': {'login': 'unknown'}}, {**draft, 'assets': []},
                    {**draft, 'target_commitish': SOURCE}, {**draft, 'immutable': True},
                    {**draft, 'name': 'unexpected'}):
            with self.assertRaises(ValueError): p.check_existing(bad, f, TARGET, m, notes)
        wrong = copy.deepcopy(r)
        wrong['assets'][0]['digest'] = 'sha256:' + '0' * 64
        with self.assertRaises(ValueError): p.check_assets(wrong, f, TARGET, m)
        for asset in draft['assets']:
            asset['browser_download_url'] = asset['browser_download_url'].replace(m['tag'], 'untagged-abc')
        p.check_existing(draft, f, TARGET, m, notes)

    def test_pagination_and_unpublished_draft_discovery(self):
        drafts = [{'id': n, 'tag_name': 'unrelated'} for n in range(100)]
        draft = {'id': 12345, 'tag_name': metadata()['tag'], 'draft': True}
        with patch.object(p, 'api', side_effect=[drafts, [draft]]), patch.object(p, 'optional', return_value=None):
            self.assertEqual(p.find_version(metadata()['tag']), draft)
        with patch.object(p, 'pages', return_value=[draft, {**draft, 'id': 2}]), patch.object(p, 'optional', return_value=None):
            with self.assertRaises(ValueError): p.find_version(metadata()['tag'])

    def test_publication_target_only_accepts_publication_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args): return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
            def commit(path, text):
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(text)
                git('add', '--', path); git('commit', '-qm', 'test fixture')
                return git('rev-parse', 'HEAD')
            git('init', '-q'); git('config', 'user.name', 'Test'); git('config', 'user.email', 'test@example.invalid')
            source = commit('gradle.properties', 'original\n')
            target = commit('release/notes-v2.4.0.md', 'notes\n')
            with patch.object(p, 'ROOT', root):
                with patch.dict(os.environ, {'GITHUB_SHA': target}), patch.object(p, 'api', side_effect=[
                        {'default_branch': 'main'}, {'commit': {'sha': target}}]):
                    self.assertEqual(p.publication_target(source), target)
                with patch.dict(os.environ, {'GITHUB_SHA': source}), self.assertRaises(ValueError): p.publication_target(source)
                commit('gradle.properties', 'forbidden\n')
                target = commit('gradle.properties', 'original\n')
                with patch.dict(os.environ, {'GITHUB_SHA': target}), patch.object(p, 'api') as api:
                    with self.assertRaises(ValueError): p.publication_target(source)
                    api.assert_not_called()

    def test_main_must_still_be_the_verified_default_head(self):
        for responses in ([{'default_branch': 'other'}], [{'default_branch': 'main'}, {'commit': {'sha': SOURCE}}]):
            with patch.object(p, 'api', side_effect=responses), self.assertRaises(ValueError): p.check_current_main(TARGET)

    def test_safe_permissions_diagnostics_and_optional_404(self):
        url = p.API + '/releases?credential=hidden-query'
        token = 'test-private-token-do-not-print'
        body = json.dumps({'message': 'Resource not accessible. Authorization: Bearer ' + token
                           + ' token=other-private https://example.invalid/?key=private-query\n' + 'x' * 1000}).encode()
        error = urllib.error.HTTPError(url, 403, 'Forbidden', {'Authorization': token}, io.BytesIO(body))
        with patch.dict(os.environ, {'GH_TOKEN': token}), patch.object(p.urllib.request, 'build_opener') as opener:
            opener.return_value.open.side_effect = error
            with self.assertRaises(p.GitHubAPIError) as caught: p.request(url, 'POST', {'tag_name': metadata()['tag']})
        text = str(caught.exception)
        self.assertIn('HTTP 403: POST /repos/' + p.REPO + '/releases:', text)
        for private in (token, 'other-private', 'private-query', 'hidden-query', 'Authorization', '\n'):
            self.assertNotIn(private, text)
        self.assertLess(len(text), 550)
        for code in (404, 403):
            with patch.object(p, 'api', side_effect=p.GitHubAPIError(code, 'GET', url, b'not JSON')):
                if code == 404: self.assertIsNone(p.optional('/missing'))
                else:
                    with self.assertRaises(p.GitHubAPIError): p.optional('/missing')

    def test_duplicate_json_and_host_only_guard(self):
        with self.assertRaises(ValueError): p.parse('{"versionCode":1,"versionCode":2}')
        for env in ({}, {**HOSTED, 'RUNNER_ENVIRONMENT': 'self-hosted'}):
            with patch.dict(os.environ, env, clear=True), patch.object(p, 'api') as api:
                with self.assertRaises(ValueError): p.main()
                api.assert_not_called()

    def test_missing_run_or_permissions_stops_before_download(self):
        for code in (404, 403):
            with patch.dict(os.environ, HOSTED), patch.object(p, 'api', side_effect=p.GitHubAPIError(
                    code, 'GET', p.API + f'/actions/runs/{RUN}', b'{}')), patch.object(p, 'request') as request:
                with self.assertRaises(p.GitHubAPIError): p.main()
                request.assert_not_called()

    def test_complete_manual_promotion_with_mocked_github(self):
        # Covers download -> receipt -> draft assets -> immutable publish -> discovery
        # without any real HTTP request, APK, Android tools, or release mutation.
        data, m = archive(), metadata()
        state, calls = {}, []
        def missing(path): raise p.GitHubAPIError(404, 'GET', p.API + path, b'{}')
        def api(path, method='GET', body=None):
            calls.append((method, path, body))
            if path == f'/actions/runs/{RUN}': return build()
            if '/jobs?' in path: return {'jobs': jobs()}
            if '/artifacts?' in path: return {'artifacts': [artifact(data)]}
            if path.startswith('/releases?'): return []
            if path.startswith('/releases/tags/'): return missing(path)
            if path.startswith('/git/ref/tags/'):
                return {'object': {'type': 'commit', 'sha': TARGET}} if state.get('published') else missing(path)
            if path == '': return {'default_branch': 'main'}
            if path == '/branches/main': return {'commit': {'sha': TARGET}}
            if path == '/releases' and method == 'POST':
                self.assertEqual(body['target_commitish'], TARGET)
                state['release'] = {**body, 'id': 99, 'assets': [], 'author': {'login': 'github-actions[bot]'},
                                    'upload_url': f'https://uploads.github.com/repos/{p.REPO}/releases/99/assets{{?name}}'}
                return copy.deepcopy(state['release'])
            if path == '/releases/99':
                if method == 'PATCH':
                    state['release'].update(draft=False, immutable=True)
                    state['published'] = True
                return copy.deepcopy(state['release'])
            self.fail('Unexpected API: ' + path)
        def request(url, method='GET', body=None, **kwargs):
            if url == p.API + f'/actions/artifacts/{ARTIFACT}/zip': return data
            if url == p.API + '/releases/latest': return json.dumps(state['release']).encode()
            if url.startswith(f'https://uploads.github.com/repos/{p.REPO}/releases/99/assets?name='):
                name = p.urllib.parse.parse_qs(p.urllib.parse.urlsplit(url).query)['name'][0]
                self.assertEqual(method, 'POST')
                self.assertEqual(body, files()[name])
                state['release']['assets'].append({'name': name, 'size': len(body), 'state': 'uploaded',
                    'digest': 'sha256:' + p.sha(body), 'browser_download_url':
                    f'https://github.com/{p.REPO}/releases/download/{m["tag"]}/{name}'})
                return b'{}'
            self.fail('Unexpected request; real HTTP forbidden')
        with tempfile.TemporaryDirectory() as directory:
            env = {**HOSTED, 'RUNNER_TEMP': directory, 'GITHUB_STEP_SUMMARY': str(Path(directory) / 'summary')}
            with patch.dict(os.environ, env), patch.object(p, 'api', side_effect=api), \
                 patch.object(p, 'request', side_effect=request) as http, \
                 patch.object(p, 'publication_target', return_value=TARGET) as target, \
                 patch.object(p.protocol, 'check_previous', return_value=CODE - 1), \
                 patch.object(p, 'prepare_updater_check', return_value=['fake-java', p.PIN]), \
                 patch.object(p.subprocess, 'run') as execute, context_output():
                p.main()
                target.assert_called_once_with(SOURCE)
                self.assertEqual(execute.call_count, 3)
                downloads = [call for call in http.call_args_list if '/actions/artifacts/' in call.args[0]]
                self.assertEqual(len(downloads), 1)
            self.assertEqual(len(state['release']['assets']), 2)
            self.assertEqual(len([c for c in calls if c[:2] == ('POST', '/releases')]), 1)
            self.assertEqual(len([c for c in calls if c[:2] == ('PATCH', '/releases/99')]), 1)
            self.assertTrue(state['release']['immutable'])
            self.assertIn(SOURCE, (Path(directory) / 'summary').read_text())

    def test_production_updater_parses_and_discovers_offline_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            expected = directory / 'expected.json'; candidate = directory / 'candidate.json'
            expected.write_text(json.dumps(metadata())); candidate.write_text(json.dumps(release()))
            command = p.prepare_updater_check(directory) + [str(expected), str(CODE - 1), str(candidate)]
            subprocess.run(command, check=True)
            subprocess.run(command + ['--offline'], check=True)
            # A corrupt digest must also fail the unchanged production parser.
            bad = release(); bad['assets'][0]['digest'] = 'sha256:' + '0' * 64
            candidate.write_text(json.dumps(bad))
            result = subprocess.run(command + ['--offline'], capture_output=True)
            self.assertNotEqual(result.returncode, 0)


def context_output():
    import contextlib
    return contextlib.redirect_stdout(io.StringIO())


if __name__ == '__main__':
    unittest.main()
