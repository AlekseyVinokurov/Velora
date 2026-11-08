#!/usr/bin/env python3
"""Lightweight CI setup checks: no Gradle, Android tools, real keys or network."""
import ast
import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('github_setup', ROOT / 'scripts/configure-github-secrets.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class SetupTests(unittest.TestCase):
    def test_repository_api_has_no_trailing_slash(self):
        with patch.object(setup, 'gh', return_value=b'{}') as gh:
            setup.api('')
        gh.assert_called_once_with('api', 'repos/AlekseyVinokurov/Velora', payload=None)

    def test_gh_sends_values_only_over_stdin_and_hides_failure_output(self):
        credential = b'disposable-test-credential'
        with patch.object(setup.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, credential, credential)) as run:
            with self.assertRaises(setup.SetupError) as error:
                setup.gh('secret', 'set', 'VELORA_KEY_PASSWORD', payload=credential)
        self.assertNotIn(credential.decode(), str(error.exception))
        self.assertEqual(run.call_args.kwargs['input'], credential)
        self.assertNotIn(credential.decode(), str(run.call_args.args))

    def test_noninteractive_setup_cannot_read_key_or_prompt(self):
        with patch.object(setup.sys.stdin, 'isatty', return_value=False), patch.object(setup.getpass, 'getpass') as prompt:
            with self.assertRaises(setup.SetupError):
                setup.main()
            prompt.assert_not_called()

    def test_owner_upload_is_main_only_and_never_prints_values(self):
        # These are inert bytes. All gh/keytool processes are mocked; no key is generated.
        inert_bytes = b'inert-keystore-fixture'
        certificate = b'inert-public-certificate-fixture'
        pin = hashlib.sha256(certificate).hexdigest()
        values = ['fixture-alias', 'fixture-store-password', 'fixture-key-password', '1234567', 'a' * 32]
        calls = []
        uploads = {}

        def fake_gh(*args, payload=None):
            calls.append((args, payload))
            if args[:2] == ('secret', 'list'):
                return b''
            if args[:2] == ('secret', 'set'):
                uploads[args[2]] = payload
                self.assertIn('--env', args)
                self.assertIn('velora-release', args)
                self.assertNotIn(payload.decode(), ' '.join(args))
                return b''
            if args[-2:] == ('--input', '-'):
                self.assertEqual(json.loads(payload), {'name': 'main', 'type': 'branch'})
                return b'{}'
            return b'{"branch_policies": []}'

        def fake_api(path, payload=None):
            if path == '':
                return {'default_branch': 'main', 'permissions': {'admin': True}}
            self.assertEqual(path, 'environments/velora-release')
            self.assertEqual(payload['deployment_branch_policy'], {'protected_branches': False, 'custom_branch_policies': True})
            return {}

        def fake_keytool(args, **kwargs):
            self.assertNotIn('-genkeypair', args)
            self.assertNotIn('-importkeystore', args)
            for secret in values:
                if 'password' in secret:
                    self.assertNotIn(secret, args)
            return subprocess.CompletedProcess(args, 0, certificate if '-exportcert' in args else b'inert-csr', b'')

        with tempfile.TemporaryDirectory(prefix='velora-ci-config-test-') as temporary:
            directory = Path(temporary)
            (directory / 'release').mkdir()
            (directory / 'release/certificate.sha256').write_text(pin + '\n')
            inert_file = directory / 'inert-fixture.dat'
            inert_file.write_bytes(inert_bytes)
            output = io.StringIO()
            output.isatty = lambda: True
            with patch.object(setup, 'ROOT', directory), patch.object(setup, 'KEY', inert_file), \
                    patch.object(setup, 'PIN', pin), patch.object(setup, 'api', side_effect=fake_api), \
                    patch.object(setup, 'gh', side_effect=fake_gh), \
                    patch.object(setup.shutil, 'which', return_value='/mock/tool'), \
                    patch.object(setup.sys.stdin, 'isatty', return_value=True), \
                    patch.object(setup.sys, 'stdout', output), \
                    patch.object(setup.getpass, 'getpass', side_effect=values), \
                    patch.object(setup.subprocess, 'run', side_effect=fake_keytool):
                setup.main()
            self.assertEqual(inert_file.read_bytes(), inert_bytes)
            self.assertEqual(set(uploads), set(setup.SECRET_NAMES))
            self.assertEqual(uploads['VELORA_KEYSTORE_BASE64'], base64.b64encode(inert_bytes))
            for value in [*values, base64.b64encode(inert_bytes).decode()]:
                self.assertNotIn(value, output.getvalue())


if __name__ == '__main__':
    ast.parse((ROOT / 'scripts/configure-github-secrets.py').read_text())
    unittest.main()
