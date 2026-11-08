#!/usr/bin/env python3
"""Host security regressions; Android stubs are test-only and never packaged."""
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    'android/text/TextUtils.java': '''package android.text; public class TextUtils {
        public static boolean isEmpty(CharSequence value) { return value == null || value.length() == 0; }}''',
    'android/content/SharedPreferences.java': '''package android.content; public class SharedPreferences {
        private final java.util.Map<String,Object> values = new java.util.HashMap<>();
        public boolean getBoolean(String key, boolean fallback) { return (boolean)values.getOrDefault(key, fallback); }
        public int getInt(String key, int fallback) { return (int)values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String)values.getOrDefault(key, fallback); }
        public Editor edit() { return new Editor(); }
        public class Editor { public Editor putBoolean(String k, boolean v) { values.put(k,v); return this; }
        public Editor putString(String k, String v) { values.put(k,v); return this; } public void apply() {} }}''',
    'android/content/Context.java': '''package android.content; public class Context {
        public static final int MODE_PRIVATE=0;
        private final java.util.Map<String,SharedPreferences> prefs = new java.util.HashMap<>();
        public SharedPreferences getSharedPreferences(String name, int mode) { return prefs.computeIfAbsent(name, n -> new SharedPreferences()); }
        public void startService(Intent i) { throw new AssertionError("Service must not start"); }
        public void startForegroundService(Intent i) { throw new AssertionError("Foreground service must not start"); }}''',
    'android/content/Intent.java': '''package android.content; public class Intent {
        public static final String ACTION_BOOT_COMPLETED="android.intent.action.BOOT_COMPLETED";
        private String action; public Intent() {} public Intent(Context c, Class<?> k) {}
        public Intent setAction(String v) { action=v; return this; } public String getAction() { return action; }
        public Intent putExtra(String k, Object v) { return this; }}''',
    'android/content/BroadcastReceiver.java': '''package android.content; public abstract class BroadcastReceiver {
        public abstract void onReceive(Context c, Intent i); }''',
    'android/os/Build.java': '''package android.os; public class Build {
        public static class VERSION { public static final int SDK_INT=36; }
        public static class VERSION_CODES { public static final int O=26; }}''',
    'org/telegram/messenger/FileLog.java': '''package org.telegram.messenger; public class FileLog {
        public static void e(String v) {} public static void w(String v) {} }''',
    'org/telegram/messenger/SharedConfig.java': '''package org.telegram.messenger; public class SharedConfig {
        public static boolean isProxyEnabled() { return false; } public static ProxyInfo currentProxy;
        public static class ProxyInfo { public String username,password,address,secret; public int port; }}''',
    'org/telegram/messenger/ApplicationLoader.java': '''package org.telegram.messenger; public class ApplicationLoader {
        public static android.content.Context applicationContext; }''',
    'soundgram/wsproxy/ProxyAutoDetector.java': '''package soundgram.wsproxy; public class ProxyAutoDetector {
        public static boolean isVpnActive(android.content.Context c) { throw new AssertionError("No VPN detection on boot"); }
        public static void checkAndAutoEnable(android.content.Context c) { throw new AssertionError("No detector on boot"); }}''',
}


def run(command, **kwargs):
    subprocess.run([str(item) for item in command], check=True, **kwargs)


def main():
    work = ROOT / '.local-build'
    work.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='security-test-', dir=work) as directory:
        temporary = Path(directory)
        for name, source in STUBS.items():
            path = temporary / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
        helper = ROOT / 'TMessagesProj/src/main/java/soundgram/music/MusicNetworkHelper.java'
        import re
        extras = sorted(set(re.findall(r'TgWsProxyService\.(EXTRA_[A-Z_]+|ACTION_START)', helper.read_text())))
        stub = temporary / 'soundgram/wsproxy/TgWsProxyService.java'
        stub.write_text('package soundgram.wsproxy; public class TgWsProxyService { public static boolean isRunning=false; '
                        'public static int currentPort=0; ' + ''.join('public static final String '+name+'="'+name+'";' for name in extras)+'}')
        source = ROOT / 'TMessagesProj/src/main/java/soundgram/wsproxy'
        classes = temporary / 'classes'
        run(['javac', '--release', '17', '-d', classes, *temporary.rglob('*.java'), helper,
             source / 'WsProxyPolicy.java', source / 'BootReceiver.java', ROOT / 'tests/security/NetworkSecurityTest.java'])
        env = dict(os.environ, VELORA_SECURITY_TEST_PASSWORD=secrets.token_urlsafe(32))
        keys = []
        for name, host in [('good', 'localhost'), ('wrong-host', 'other.example'), ('untrusted', 'localhost')]:
            key = temporary / (name + '.p12')
            run(['keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', key, '-alias', 'fixture',
                 '-storepass:env', 'VELORA_SECURITY_TEST_PASSWORD', '-keypass:env', 'VELORA_SECURITY_TEST_PASSWORD',
                 '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1', '-dname', 'CN=Disposable TLS fixture',
                 '-ext', 'SAN=DNS:' + host], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            keys.append(key)
        run(['java', '-cp', classes, 'NetworkSecurityTest', *keys], env=env)
    manifest = ET.parse(ROOT / 'TMessagesProj/src/main/AndroidManifest.xml')
    ns = '{http://schemas.android.com/apk/res/android}'
    for name in ['soundgram.wsproxy.TgWsProxyService', 'soundgram.wsproxy.BootReceiver']:
        matches = [node for node in manifest.iter() if node.get(ns+'name') == name]
        assert len(matches) == 1 and matches[0].get(ns+'enabled') == 'false', name
    print('PASS: WS service and boot receiver disabled in the product manifest. Temporary fixtures deleted.')


if __name__ == '__main__':
    main()
