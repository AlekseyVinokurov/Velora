#!/usr/bin/env python3
"""Validate client Firebase config and its packaged resources; never print values."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

PACKAGE = 'io.github.alekseyvinokurov.velora'
CONFIG = Path('TMessagesProj_AppStandalone/google-services.json')


def expected_resources(config):
    # A service-account private key must never enter an Android client build.
    def check_keys(value):
        if isinstance(value, dict):
            if {'private_key', 'private_key_id', 'client_email'} & value.keys():
                raise ValueError('Server credentials are forbidden in client Firebase config')
            for item in value.values():
                check_keys(item)
        elif isinstance(value, list):
            for item in value:
                check_keys(item)
    check_keys(config)
    project = config['project_info']
    clients = [client for client in config['client']
               if client['client_info']['android_client_info']['package_name'] == PACKAGE]
    if len(clients) != 1:
        raise ValueError('Firebase config must contain exactly one stable package client')
    client = clients[0]
    values = {
        'google_app_id': client['client_info']['mobilesdk_app_id'],
        'gcm_defaultSenderId': project['project_number'],
        'project_id': project['project_id'],
        'google_api_key': client['api_key'][0]['current_key'],
    }
    patterns = {
        'gcm_defaultSenderId': r'[1-9][0-9]*',
        'project_id': r'[a-z][a-z0-9-]{4,28}[a-z0-9]',
        'google_api_key': r'AIza[0-9A-Za-z_-]{35}',
    }
    for name, pattern in patterns.items():
        if not isinstance(values[name], str) or not re.fullmatch(pattern, values[name]):
            raise ValueError('Invalid Firebase client field: ' + name)
    if not isinstance(values['google_app_id'], str) or not re.fullmatch(
            r'1:' + re.escape(values['gcm_defaultSenderId']) + r':android:[0-9a-f]+', values['google_app_id']):
        raise ValueError('Firebase app ID and sender ID must agree')
    return values


def analyze(tool, apk, *args):
    result = subprocess.run([tool, *args, str(apk)], capture_output=True, text=True)
    if result.returncode:
        raise ValueError('APK Firebase inspection failed (tool output hidden)')
    return result.stdout.strip()


def verify_apk(apk):
    expected = expected_resources(json.loads(CONFIG.read_text()))
    tool = str(Path(os.environ['ANDROID_HOME']) / 'cmdline-tools/latest/bin/apkanalyzer')
    if analyze(tool, apk, 'manifest', 'application-id') != PACKAGE:
        raise ValueError('Unexpected APK package')
    for name, value in expected.items():
        actual = analyze(tool, apk, 'resources', 'value', '--config', 'default',
                         '--type', 'string', '--name', name, '--package', PACKAGE)
        if actual not in (value, json.dumps(value)):
            raise ValueError('APK Firebase resource mismatch: ' + name)
    manifest = ET.fromstring(analyze(tool, apk, 'manifest', 'print'))
    android = '{http://schemas.android.com/apk/res/android}'
    application = manifest.find('application')
    services = application.findall('service')
    if not any(s.get(android + 'name') == 'org.telegram.messenger.GcmPushListenerService'
               and s.get(android + 'exported') == 'false'
               and any(a.get(android + 'name') == 'com.google.firebase.MESSAGING_EVENT'
                       for a in s.findall('intent-filter/action')) for s in services):
        raise ValueError('APK FCM listener missing or exported')
    if not any(p.get(android + 'name') == 'com.google.firebase.provider.FirebaseInitProvider'
               for p in application.findall('provider')):
        raise ValueError('APK Firebase initialization provider missing')
    print('APK Firebase resources match validated configuration; FCM components present. Values hidden.')


def main():
    if sys.argv[1:] == ['install']:
        required_environment = {
            'GITHUB_ACTIONS': 'true',
            'RUNNER_ENVIRONMENT': 'github-hosted',
            'RUNNER_OS': 'Linux',
            'GITHUB_EVENT_NAME': 'workflow_dispatch',
            'GITHUB_REPOSITORY': 'AlekseyVinokurov/Velora',
            'GITHUB_REF': 'refs/heads/main',
        }
        if any(os.environ.get(name) != value for name, value in required_environment.items()):
            raise ValueError('Firebase config installation requires a main-only GitHub-hosted release run')
        try:
            data = base64.b64decode(os.environ['VELORA_GOOGLE_SERVICES_JSON_BASE64'], validate=True)
            config = json.loads(data)
        except (ValueError, KeyError):
            raise ValueError('Missing or invalid VELORA_GOOGLE_SERVICES_JSON_BASE64') from None
        expected_resources(config)
        descriptor = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
        print('Stable Firebase client configuration validated and installed; values hidden.')
    elif len(sys.argv) == 3 and sys.argv[1] == 'verify-apk':
        verify_apk(Path(sys.argv[2]))
    else:
        raise ValueError('Usage: push-config.py install | verify-apk APK')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, TypeError, IndexError, OSError, AttributeError, ET.ParseError):
        # Parser/tool exceptions can include input data: never echo them.
        sys.exit('Firebase validation failed; check client config/package and APK resources (values hidden).')
