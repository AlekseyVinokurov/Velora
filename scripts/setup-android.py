#!/usr/bin/env python3
"""Install the pinned Android packages from Google's SDK repository (interactive licenses)."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / '.local-build/android-sdk'
DOWNLOADS = ROOT / '.local-build/downloads'
BASE = 'https://dl.google.com/android/repository/'
COMMAND_LINE_TOOLS = 'commandlinetools-linux-16111833_latest.zip'
COMMAND_LINE_TOOLS_SHA256 = '0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8'
PACKAGES = ['platforms;android-36', 'build-tools;36.0.0', 'ndk;26.3.11579264', 'cmake;3.22.1', 'platform-tools']

def main():
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    SDK.mkdir(parents=True, exist_ok=True)
    manager = SDK / 'cmdline-tools/latest/bin/sdkmanager'
    if not manager.is_file():
        # Fixed official bootstrap, checked against the archive fetched from Google's repository.
        destination = DOWNLOADS / COMMAND_LINE_TOOLS
        if not destination.is_file() or hashlib.sha256(destination.read_bytes()).hexdigest() != COMMAND_LINE_TOOLS_SHA256:
            print('Downloading official Android command-line tools:', COMMAND_LINE_TOOLS, flush=True)
            with urllib.request.urlopen(BASE + COMMAND_LINE_TOOLS, timeout=120) as response, destination.open('wb') as out:
                shutil.copyfileobj(response, out)
        if hashlib.sha256(destination.read_bytes()).hexdigest() != COMMAND_LINE_TOOLS_SHA256:
            raise RuntimeError('Android SDK archive checksum mismatch')
        tools = SDK / 'cmdline-tools'
        tools.mkdir(exist_ok=True)
        with zipfile.ZipFile(destination) as archive_file:
            for entry in archive_file.infolist():
                path = Path(entry.filename)
                if path.is_absolute() or '..' in path.parts or path.parts[0] != 'cmdline-tools':
                    raise RuntimeError('Unexpected SDK archive entry')
            temporary = tools / 'bootstrap'
            archive_file.extractall(temporary)
            (temporary / 'cmdline-tools').rename(tools / 'latest')
            temporary.rmdir()
        for executable in (tools / 'latest/bin').iterdir():
            executable.chmod(0o755)
    print('Install pinned packages; review the Android SDK license prompts.', flush=True)
    subprocess.run([str(manager), '--sdk_root=' + str(SDK), *PACKAGES], check=True)
    properties = ROOT / 'local.properties'
    lines = properties.read_text().splitlines() if properties.is_file() else []
    lines = [line for line in lines if not line.startswith('sdk.dir=')]
    properties.write_text('\n'.join([*lines, 'sdk.dir=' + str(SDK)]) + '\n')
    print('SDK ready:', SDK)
    print('sdk.dir written to ignored local.properties. No credentials were written.')

if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError, StopIteration, zipfile.BadZipFile) as error:
        print('Android setup failed:', error, file=sys.stderr)
        sys.exit(1)
