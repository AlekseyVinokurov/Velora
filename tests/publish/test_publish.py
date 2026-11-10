#!/usr/bin/env python3
"""Host-only promotion regressions with synthetic bytes, never APKs or Android tools."""
import copy
import importlib.util
import io
import json
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('promotion', ROOT / 'scripts/publish-existing-release.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


def receipt():
    return {'applicationId': p.protocol.PACKAGE, 'versionCode': 100002, 'versionName': '0.1.2',
            'minSdk': 24, 'sha256': p.APK_SHA, 'certificateSha256': p.PIN, 'sourceCommit': p.SOURCE, 'size': 64}


class PromotionTest(unittest.TestCase):
    def test_metadata_protocol_and_identity(self):
        record = receipt()
        metadata = p.validate_receipt(record, p.APK_SHA, 64)
        self.assertEqual(metadata['tag'], 'v0.1.2')
        self.assertEqual(metadata['apk']['name'], p.APK_NAME)
        self.assertEqual(p.protocol.validate_metadata(metadata, p.PIN), metadata)
        for key, value in [('applicationId', 'foreign.package'), ('versionName', '0.1.3'),
                           ('versionCode', 100003), ('versionCode', True), ('minSdk', 25),
                           ('sha256', '0'*64), ('certificateSha256', '0'*64),
                           ('sourceCommit', '0'*40), ('size', 65)]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                p.validate_receipt({**record, key: value}, p.APK_SHA, 64)
        with self.assertRaises(ValueError):
            p.validate_receipt({**record, 'unexpected': 1}, p.APK_SHA, 64)
        with self.assertRaises(ValueError):
            p.validate_receipt(record, '0'*64, 64)

    def test_provenance_is_bound_to_exact_successful_run(self):
        run = {'id': p.RUN, 'status': 'completed', 'conclusion': 'success', 'event': 'workflow_dispatch',
               'path': '.github/workflows/android-release.yml', 'head_sha': p.SOURCE, 'head_branch': 'main',
               'run_attempt': 1, 'head_repository': {'full_name': p.REPO}}
        artifact = {'id': p.ARTIFACT, 'expired': False, 'name': 'Velora-release-arm64-42797176789-1',
                    'digest': 'sha256:' + p.ARCHIVE_SHA,
                    'workflow_run': {'id': p.RUN, 'head_sha': p.SOURCE, 'head_branch': 'main'}}
        p.check_provenance(run, artifact)
        for key, value in [('conclusion', 'failure'), ('head_sha', '0'*40), ('head_branch', 'other'),
                           ('run_attempt', 2), ('event', 'pull_request')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                p.check_provenance({**run, key: value}, artifact)
        for key, value in [('expired', True), ('id', 1), ('digest', 'sha256:'+'0'*64)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                p.check_provenance(run, {**artifact, key: value})

    def test_archive_binding_and_unexpected_entries(self):
        payload = b'Synthetic integrity fixture, not an Android package.'
        record = {**receipt(), 'size': len(payload), 'sha256': p.sha(payload)}
        for extra in [False, True]:
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, 'w') as archive:
                archive.writestr(p.APK_NAME, payload)
                archive.writestr('stable-build.json', json.dumps(record))
                if extra:
                    archive.writestr('../unexpected', b'no')
            data = buffer.getvalue()
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):
                    p.unpack(data, Path(directory))
                with patch.object(p, 'ARCHIVE_SHA', p.sha(data)), patch.object(p, 'APK_SHA', p.sha(payload)):
                    if extra:
                        with self.assertRaises(ValueError): p.unpack(data, Path(directory))
                    else:
                        metadata = p.unpack(data, Path(directory))
                        self.assertEqual(metadata['apk']['size'], len(payload))
                        self.assertEqual((Path(directory)/p.APK_NAME).read_bytes(), payload)

    def test_hosted_build_and_signature_firebase_gates(self):
        job = {'run_id': p.RUN, 'run_attempt': 1, 'conclusion': 'success',
               'runner_group_name': 'GitHub Actions', 'steps': [
                   {'name': name, 'conclusion': 'success'} for name in (
                       'Build and verify with the existing permanent release key',
                       'Validate and install Firebase client config from protected environment',
                       'Verify Firebase configuration in the signed APK')]}
        p.check_build_jobs({'total_count': 1, 'jobs': [job]})
        for bad in ({**job, 'runner_group_name': 'self-hosted'}, {**job, 'steps': []},
                    {**job, 'conclusion': 'failure'}, {**job, 'run_attempt': 2}):
            with self.assertRaises(ValueError):
                p.check_build_jobs({'total_count': 1, 'jobs': [bad]})

    def test_draft_discovery_does_not_depend_on_tag_endpoint(self):
        draft = {'id': 12345, 'tag_name': p.TAG, 'draft': True}
        with patch.object(p, 'api', return_value=[draft]), patch.object(p, 'optional', return_value=None):
            self.assertEqual(p.find_version(), draft)
        with patch.object(p, 'api', return_value=[draft, {**draft, 'id': 2}]), patch.object(p, 'optional', return_value=None):
            with self.assertRaises(ValueError): p.find_version()

    def test_duplicate_json_and_host_only_guard(self):
        with self.assertRaises(ValueError): p.parse('{"versionCode":100002,"versionCode":1}')
        with patch.dict(p.os.environ, {}, clear=True), self.assertRaises(ValueError): p.main()

    def test_assets_must_be_complete_and_exact(self):
        files = {p.APK_NAME: b'fixture', 'velora-release.json': b'{}'}
        target = 'a' * 40
        release = {'tag_name': p.TAG, 'target_commitish': target, 'prerelease': False,
                   'assets': [{'name': name, 'state': 'uploaded', 'size': len(data),
                               'digest': 'sha256:'+p.sha(data),
                               'browser_download_url': f'https://github.com/{p.REPO}/releases/download/{p.TAG}/{name}'}
                              for name, data in files.items()]}
        p.check_assets(release, files, target)
        with self.assertRaises(ValueError):
            p.check_assets({**release, 'target_commitish': p.SOURCE}, files, target)
        self.assertEqual(p.validate_receipt(receipt(), p.APK_SHA, 64)['sourceCommit'], p.SOURCE)
        draft = copy.deepcopy(release)
        draft['draft'] = True
        for asset in draft['assets']:
            asset['browser_download_url'] = asset['browser_download_url'].replace(p.TAG, 'untagged-bbe0420cf8a4f0abfe64')
        p.check_assets(draft, files, target)
        draft['draft'] = False
        with self.assertRaises(ValueError): p.check_assets(draft, files, target)
        missing = copy.deepcopy(release)
        missing['assets'].pop()
        with self.assertRaises(ValueError): p.check_assets(missing, files, target)
        wrong = copy.deepcopy(release)
        wrong['assets'][0]['digest'] = 'sha256:'+'0'*64
        with self.assertRaises(ValueError): p.check_assets(wrong, files, target)

    def test_publication_target_only_accepts_publication_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True).strip()

            def commit(path, contents):
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(contents)
                git('add', '--', path)
                git('commit', '-qm', 'test fixture')
                return git('rev-parse', 'HEAD')

            git('init', '-q')
            git('config', 'user.name', 'Test')
            git('config', 'user.email', 'test@example.invalid')
            source = commit('gradle.properties', 'original\n')
            target = commit('release/notes-v0.1.2.md', 'notes\n')
            with patch.object(p, 'ROOT', root), patch.object(p, 'SOURCE', source):
                with patch.dict(p.os.environ, {'GITHUB_SHA': target}), patch.object(p, 'api', side_effect=[
                        {'default_branch': 'main'}, {'commit': {'sha': target}}]):
                    self.assertEqual(p.publication_target(), target)
                with patch.dict(p.os.environ, {'GITHUB_SHA': source}), self.assertRaises(ValueError):
                    p.publication_target()
                commit('gradle.properties', 'forbidden change\n')
                target = commit('gradle.properties', 'original\n')
                # Even a forbidden change later reverted must stop publication.
                with patch.dict(p.os.environ, {'GITHUB_SHA': target}), patch.object(p, 'api') as api:
                    with self.assertRaises(ValueError): p.publication_target()
                    api.assert_not_called()

    def test_main_must_still_be_the_verified_default_head(self):
        target = 'a' * 40
        for responses in ([{'default_branch': 'other'}],
                          [{'default_branch': 'main'}, {'commit': {'sha': 'b' * 40}}]):
            with patch.object(p, 'api', side_effect=responses), self.assertRaises(ValueError):
                p.check_current_main(target)

    def test_safe_http_diagnostics_and_optional_404(self):
        url = p.API + '/releases?credential=hidden-query'
        token = 'test-private-token-do-not-print'
        body = json.dumps({'message': 'Resource not accessible. Authorization: Bearer ' + token
                           + ' token=other-private-value https://example.invalid/?key=private-query '
                           + '\n' + 'x' * 1000}).encode()
        error = urllib.error.HTTPError(url, 403, 'Forbidden', {'Authorization': token}, io.BytesIO(body))
        with patch.dict(p.os.environ, {'GH_TOKEN': token}), patch.object(p.urllib.request, 'build_opener') as opener:
            opener.return_value.open.side_effect = error
            with self.assertRaises(p.GitHubAPIError) as raised:
                p.request(url, 'POST', {'tag_name': p.TAG})
        message = str(raised.exception)
        self.assertIn('HTTP 403: POST /repos/' + p.REPO + '/releases:', message)
        self.assertIn('Resource not accessible', message)
        for private in (token, 'other-private-value', 'private-query', 'hidden-query', 'Authorization', '\n'):
            self.assertNotIn(private, message)
        self.assertLess(len(message), 550)
        for code in (404, 403):
            safe_error = p.GitHubAPIError(code, 'GET', url, b'not JSON')
            with patch.object(p, 'api', side_effect=safe_error):
                if code == 404:
                    self.assertIsNone(p.optional('/missing'))
                else:
                    with self.assertRaises(p.GitHubAPIError): p.optional('/missing')


if __name__ == '__main__':
    unittest.main()
