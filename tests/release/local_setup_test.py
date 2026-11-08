#!/usr/bin/env python3
"""Regression tests for local setup; no real key or API credentials are created."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('velora_local_setup', ROOT / 'scripts/configure-local-release.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class InteractiveOutput(io.StringIO):
    def isatty(self):
        return True


class LocalSetupTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='velora-setup-policy-')
        self.addCleanup(self.directory.cleanup)
        base = Path(self.directory.name)
        self.repo = base / 'repo'
        (self.repo / 'release').mkdir(parents=True)
        (self.repo / 'docs').mkdir()
        (self.repo / '.git').mkdir()
        self.pin = self.repo / 'release/certificate.sha256'
        self.pin.write_text('# no permanent pin\n')
        self.key = base / 'existing.p12'
        self.key.write_bytes(b'Placeholder for a preserved existing key; not a usable keystore')
        self.original_key = self.key.read_bytes()
        self.addCleanup(patch.stopall)
        patch.object(setup, 'ROOT', self.repo).start()
        patch.object(setup.sys.stdin, 'isatty', return_value=True).start()
        patch.object(setup.sys.stdout, 'isatty', return_value=True).start()

    def fake_export(self, args, **kwargs):
        self.assertIn('-exportcert', args)  # An existing key must never invoke key generation.
        self.assertNotIn('fixture passphrase', ' '.join(args))
        Path(args[args.index('-file') + 1]).write_bytes(b'disposable public certificate fixture')
        return type('Result', (), {'returncode': 0})()

    def invoke(self, answers, password='fixture passphrase'):
        with patch('builtins.input', side_effect=answers), patch.object(setup.getpass, 'getpass', side_effect=[password, '', 'a'*32]), \
                patch.object(setup.subprocess, 'run', side_effect=self.fake_export), contextlib.redirect_stdout(InteractiveOutput()):
            setup.main()

    def test_existing_key_is_preserved_and_secrets_stay_local(self):
        self.invoke([str(self.key), '', '123456'])
        self.assertEqual(self.key.read_bytes(), self.original_key)
        config = self.repo / 'private.properties'
        self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertIn('RELEASE_STORE_PASSWORD=fixture\\ passphrase', config.read_text())
        self.assertEqual(self.pin.read_text().strip(), setup.hashlib.sha256(b'disposable public certificate fixture').hexdigest())

    def test_different_pin_is_never_replaced(self):
        original = 'f'*64+'\n'
        self.pin.write_text(original)
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.invoke([str(self.key), ''])
        self.assertEqual(self.pin.read_text(), original)
        self.assertFalse((self.repo / 'private.properties').exists())
        self.assertEqual(self.key.read_bytes(), self.original_key)

    def test_pinned_key_must_be_restored_instead_of_regenerated(self):
        self.pin.write_text('f'*64+'\n')
        with patch('builtins.input', side_effect=[str(self.key.with_name('missing.p12')), '']), \
                patch.object(setup.subprocess, 'run') as command:
            with self.assertRaisesRegex(ValueError, 'Restore'):
                setup.main()
            command.assert_not_called()

    def test_key_inside_git_is_rejected_before_any_command(self):
        with patch('builtins.input', return_value=str(self.repo / 'secret.p12')), \
                patch.object(setup.subprocess, 'run') as command:
            with self.assertRaisesRegex(ValueError, 'outside'):
                setup.main()
            command.assert_not_called()

    def test_invalid_api_does_not_replace_configuration(self):
        config = self.repo / 'private.properties'
        config.write_text('# original local configuration\n')
        with self.assertRaisesRegex(ValueError, 'API ID/HASH'):
            self.invoke([str(self.key), '', '0'])
        self.assertEqual(config.read_text(), '# original local configuration\n')
        self.assertEqual(self.pin.read_text(), '# no permanent pin\n')

    def test_noninteractive_input_is_rejected(self):
        with patch.object(setup.sys.stdin, 'isatty', return_value=False):
            with self.assertRaisesRegex(ValueError, 'interactive'):
                setup.main()
        self.assertFalse((self.repo / 'private.properties').exists())


if __name__ == '__main__':
    unittest.main()
