#!/usr/bin/env python3
"""Check the production UI notes formatter with only javac/java; no Android tools."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='velora-notes-') as directory:
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', directory,
                    str(ROOT / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/updater/VeloraReleaseNotes.java'),
                    str(ROOT / 'tests/release/ReleaseNotesTest.java')], check=True)
    subprocess.run(['java', '-cp', directory, 'ReleaseNotesTest',
                    str(ROOT / 'release/notes-v0.1.3.md')], check=True)
