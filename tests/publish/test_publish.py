#!/usr/bin/env python3
"""Host-only promotion regressions with synthetic bytes, never APKs or Android tools."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('promotion', ROOT / 'scripts/publish-existing-release.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


def receipt():
    return {'applicationId': p.protocol.PACKAGE, 'versionCode': 100001, 'versionName': '0.1.1',
            'minSdk': 24, 'sha256': p.APK_SHA, 'certificateSha256': p.PIN, 'sourceCommit': p.SOURCE, 'size': 64}


class PromotionTest(unittest.TestCase):
    def test_metadata_protocol_and_identity(self):
        record = receipt()
        metadata = p.validate_receipt(record, p.APK_SHA, 64)
        self.assertEqual(metadata['tag'], 'v0.1.1')
        self.assertEqual(metadata['apk']['name'], p.APK_NAME)
        self.assertEqual(p.protocol.validate_metadata(metadata, p.PIN), metadata)
        for key, value in [('applicationId', 'foreign.package'), ('versionName', '0.1.2'),
                           ('versionCode', 100002), ('versionCode', True), ('minSdk', 25),
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
        artifact = {'id': p.ARTIFACT, 'expired': False, 'name': 'Velora-release-arm64-42773454999-1',
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

    def test_draft_discovery_does_not_depend_on_tag_endpoint(self):
        draft = {'id': p.RESUMABLE_DRAFT, 'tag_name': p.TAG, 'draft': True}
        with patch.object(p, 'api', return_value=[draft]), patch.object(p, 'optional', return_value=None):
            self.assertEqual(p.find_version(), draft)
        with patch.object(p, 'api', return_value=[draft, {**draft, 'id': 2}]), patch.object(p, 'optional', return_value=None):
            with self.assertRaises(ValueError): p.find_version()

    def test_duplicate_json_and_host_only_guard(self):
        with self.assertRaises(ValueError): p.parse('{"versionCode":100001,"versionCode":1}')
        with patch.dict(p.os.environ, {}, clear=True), self.assertRaises(ValueError): p.main()

    def test_assets_must_be_complete_and_exact(self):
        files = {p.APK_NAME: b'fixture', 'velora-release.json': b'{}'}
        release = {'tag_name': p.TAG, 'target_commitish': p.SOURCE, 'prerelease': False,
                   'assets': [{'name': name, 'state': 'uploaded', 'size': len(data),
                               'digest': 'sha256:'+p.sha(data),
                               'browser_download_url': f'https://github.com/{p.REPO}/releases/download/{p.TAG}/{name}'}
                              for name, data in files.items()]}
        p.check_assets(release, files)
        draft = copy.deepcopy(release)
        draft['draft'] = True
        for asset in draft['assets']:
            asset['browser_download_url'] = asset['browser_download_url'].replace(p.TAG, 'untagged-bbe0420cf8a4f0abfe64')
        p.check_assets(draft, files)
        draft['draft'] = False
        with self.assertRaises(ValueError): p.check_assets(draft, files)
        missing = copy.deepcopy(release)
        missing['assets'].pop()
        with self.assertRaises(ValueError): p.check_assets(missing, files)
        wrong = copy.deepcopy(release)
        wrong['assets'][0]['digest'] = 'sha256:'+'0'*64
        with self.assertRaises(ValueError): p.check_assets(wrong, files)


if __name__ == '__main__':
    unittest.main()
