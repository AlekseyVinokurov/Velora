#!/usr/bin/env python3
"""Compile production updater policy and verify disposable signed fixture APKs on the JVM."""
import hashlib
import importlib.util
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.local-build'
LIBS = WORK / 'test-libs'
ARTIFACTS = [
    ('apksig-8.13.2.jar', 'https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.13.2/apksig-8.13.2.jar', 'c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89'),
    ('gson-2.11.0.jar', 'https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar', '57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b'),
    ('json-20240303.jar', 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'),
]


def run(command, **kwargs):
    return subprocess.run([str(item) for item in command], check=True, **kwargs)


def main():
    LIBS.mkdir(parents=True, exist_ok=True)
    for name, url, expected in ARTIFACTS:
        destination = LIBS / name
        if not destination.is_file():
            with urllib.request.urlopen(url, timeout=120) as response, destination.open('wb') as out:
                shutil.copyfileobj(response, out)
        if hashlib.sha256(destination.read_bytes()).hexdigest() != expected:
            raise ValueError('Test dependency checksum differs: ' + name)
    spec = importlib.util.spec_from_file_location('velora_release_tool', ROOT / 'scripts/release.py')
    release_tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(release_tool)
    sdk = release_tool.sdk_directory()
    tools = sdk / 'build-tools/36.0.0'
    for path in [tools / 'aapt2', tools / 'apksigner', sdk / 'platforms/android-36/android.jar']:
        if not path.is_file():
            raise ValueError('Android test tooling missing; run scripts/setup-android.py: ' + str(path))
    source_dir = ROOT / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/updater'
    classes = WORK / 'updater-test-classes'
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(str(LIBS / name) for name, _, _ in ARTIFACTS)
    run(['javac', '--release', '17', '-cp', classpath, '-d', classes,
         *[source_dir / name for name in ['VeloraRelease.java', 'GitHubReleaseClient.java', 'VeloraApkSignature.java']],
         ROOT / 'tests/release/UpdaterPolicyTest.java'])
    # Passwords are random and passed only through child process environment; files are disposable.
    with tempfile.TemporaryDirectory(prefix='fixture-', dir=WORK) as temporary:
        temporary = Path(temporary)
        temporary.chmod(0o700)
        env = dict(os.environ, VELORA_TEST_PASSWORD=secrets.token_urlsafe(32))
        keys = []
        for name in ['trusted', 'other']:
            key = temporary / (name + '.p12')
            run(['keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', key, '-alias', 'fixture',
                 '-storepass:env', 'VELORA_TEST_PASSWORD', '-keypass:env', 'VELORA_TEST_PASSWORD',
                 '-keyalg', 'RSA', '-keysize', '4096', '-validity', '12000' if name == 'trusted' else '365',
                 '-dname', 'CN=Disposable updater test fixture'], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            keys.append(key)
        certificate = temporary / 'fixture.der'
        run(['keytool', '-exportcert', '-keystore', keys[0], '-alias', 'fixture',
             '-storepass:env', 'VELORA_TEST_PASSWORD', '-file', certificate], env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        pin = hashlib.sha256(certificate.read_bytes()).hexdigest()
        manifest = temporary / 'AndroidManifest.xml'
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                            'package="io.github.alekseyvinokurov.velora" android:versionCode="100001" android:versionName="0.1.1">'
                            '<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36"/>'
                            '<application android:label="Disposable updater test fixture"/></manifest>')
        unsigned = temporary / 'unsigned.apk'
        run([tools / 'aapt2', 'link', '-I', sdk / 'platforms/android-36/android.jar', '--manifest', manifest, '-o', unsigned])
        apks = []
        for key, name in zip(keys, ['trusted.apk', 'other.apk']):
            apk = temporary / name
            run([tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'fixture',
                 '--ks-pass', 'env:VELORA_TEST_PASSWORD', '--key-pass', 'env:VELORA_TEST_PASSWORD',
                 '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
                 '--out', apk, unsigned], env=env)
            apks.append(apk)
        # Validate the actual Gradle signing guard, with temporary keys outside Git as required.
        # This never writes the product certificate pin or uses a real release key.
        with tempfile.TemporaryDirectory(prefix='velora-key-policy-') as external_keys:
            external_keys = Path(external_keys)
            trusted_key = external_keys / 'trusted.p12'
            short_key = external_keys / 'short.p12'
            weak_key = external_keys / 'weak.p12'
            shutil.copyfile(keys[0], trusted_key)
            shutil.copyfile(keys[1], short_key)
            trusted_key.chmod(0o600)
            short_key.chmod(0o600)
            run(['keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', weak_key, '-alias', 'fixture',
                 '-storepass:env', 'VELORA_TEST_PASSWORD', '-keypass:env', 'VELORA_TEST_PASSWORD',
                 '-keyalg', 'RSA', '-keysize', '2048', '-validity', '12000',
                 '-dname', 'CN=Disposable weak-key rejection fixture'], env=env,
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            fixture = temporary / 'signing-project'
            (fixture / 'release').mkdir(parents=True)
            (fixture / 'settings.gradle').write_text('rootProject.name = "velora-signing-policy-fixture"\n')
            (fixture / 'release/certificate.sha256').write_text(pin + '\n')
            shutil.copyfile(ROOT / 'tests/release/signing-fixture.gradle', fixture / 'build.gradle')
            shutil.copyfile(keys[0], fixture / 'inside-git.p12')
            fixture_env = dict(env, VELORA_FIXTURE_KEY=str(trusted_key), VELORA_FIXTURE_SHORT_KEY=str(short_key),
                               VELORA_FIXTURE_WEAK_KEY=str(weak_key))
            run([ROOT / 'gradlew', '-p', fixture, '--no-daemon', '--no-configuration-cache', '--max-workers=2',
                 '-Dorg.gradle.jvmargs=-Xmx256m',
                 '-PveloraSigningScript=' + str(ROOT / 'gradle/velora-release.gradle'), 'testSigningPolicy'],
                env=fixture_env, cwd=ROOT)
        env.pop('VELORA_TEST_PASSWORD', None)
        tampered = temporary / 'tampered.apk'
        shutil.copyfile(apks[0], tampered)
        with zipfile.ZipFile(tampered, 'a') as archive:
            archive.writestr('tampered-after-signing.txt', 'Deliberate integrity failure in a disposable fixture.')
        run(['java', '-cp', str(classes) + os.pathsep + classpath,
             'app.nimarkogram.messenger.updater.UpdaterPolicyTest', *apks, tampered, unsigned, pin])
        expected = {'APP_VERSION_CODE': '100001', 'APP_VERSION_NAME': '0.1.1'}
        verified = release_tool.verify_apk(apks[0], pin=pin, expected=expected)
        assert verified['versionCode'] == 100001 and verified['certificateSha256'] == pin
        for apk, accepted_pin, version in [
            (apks[1], pin, expected), (tampered, pin, expected), (unsigned, pin, expected),
            (apks[0], pin, {'APP_VERSION_CODE': '100002', 'APP_VERSION_NAME': '0.1.2'})
        ]:
            try:
                release_tool.verify_apk(apk, pin=accepted_pin, expected=version)
            except ValueError:
                pass
            else:
                raise AssertionError('Local release verifier accepted an invalid fixture')
        print('PASS: local release artifact verifier accepts the trusted APK and rejects other signer, tampering, unsigned APK and version mismatch.')
    print('Disposable test keys/APKs deleted. No permanent release key or product fingerprint created.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print('Updater tests failed:', error, file=sys.stderr)
        sys.exit(1)
