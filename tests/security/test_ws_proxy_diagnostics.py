#!/usr/bin/env python3
"""Static host contract checks for the optional WS Proxy diagnostic pipeline.

These checks do not substitute for Android/Go compilation, JNI ABI verification,
or network tests on the device. No SDK/Gradle/NDK or network access is used.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROXY = ROOT / "TMessagesProj/src/main/java/soundgram/wsproxy"


class WsProxyDiagnosticContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.diag = (PROXY / "WsProxyDiagnostics.java").read_text()
        cls.ui = (PROXY / "NekoWsProxyActivity.java").read_text()
        cls.service = (PROXY / "TgWsProxyService.java").read_text()
        cls.native = (PROXY / "NativeProxy.java").read_text()
        cls.go = (PROXY / "tg-ws-proxy.go").read_text()
        cls.jni = (ROOT / "TMessagesProj/jni/jni_bridge.c").read_text()

    def test_exact_counter_schema_shared_by_java_and_go(self):
        java_list = re.search(r"NATIVE_KEYS\s*=\s*\{(.*?)\};", self.diag, re.S)
        self.assertIsNotNone(java_list)
        keys = re.findall(r'"([a-z0-9_]+)"', java_list.group(1))
        self.assertEqual(keys, [
            "dns_ok", "dns_fail", "tcp_ok", "tcp_fail", "tls_ok", "tls_fail",
            "ws_ok", "ws_fail", "http_403", "http_429", "cf_try", "ws_try",
        ])
        fmt = re.search(r'func diagnosticsSnapshot\(\) string \{\s*return fmt.Sprintf\("([^"]+)"', self.go)
        self.assertIsNotNone(fmt)
        self.assertEqual(fmt.group(1), ";".join(k + "=%d" for k in keys))
        self.assertIn('snapshot.split(";", -1)', self.diag)
        self.assertIn("fields.length != NATIVE_KEYS.length", self.diag)
        self.assertIn("num.charAt(j) > '9'", self.diag)

    def test_native_abi_has_both_java_mappings(self):
        for symbol in ("GetDiagnosticStats", "SetDiagnosticEnabled"):
            self.assertIn("//export " + symbol, self.go)
            self.assertIn('dlsym(go_handle, "' + symbol + '")', self.jni)
            self.assertIn("Java_soundgram_wsproxy_NativeProxy_" + symbol, self.jni)
            self.assertIn("Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_" + symbol, self.jni)
            self.assertIn("native " + ("String " if symbol.startswith("Get") else "void ") + symbol, self.native)

    def test_ws80_is_opt_in_and_scoped_to_cf(self):
        self.assertIn('getBoolean("cf_ws80_fallback", false)', self.service)
        self.assertIn('setCfProxyPlainFallback(', self.service)
        self.assertIn('SetCfProxyPlainFallback', self.native)
        for symbol in ("GetTransportStats", "SetCfProxyPlainFallback"):
            self.assertIn("//export " + symbol, self.go)
            self.assertIn('dlsym(go_handle, "' + symbol + '")', self.jni)
            self.assertIn('Java_soundgram_wsproxy_NativeProxy_' + symbol, self.jni)
        self.assertIn('cfPlainFallbackEnabled.Load()', self.go)
        self.assertIn('if !secure { port = "80"; scheme = "http" }', self.go)
        self.assertIn('cfTLSName(host)', self.go)
        self.assertIn('verifiedWsTLSConfig(tlsServerName)', self.go)
        self.assertIn('String checked = validateTransportSnapshot(snapshot);', self.diag)
        self.assertIn('WsProxyDiagnostics.transportSample(this, transportSnapshot)', self.service)
        self.assertIn('new android.app.AlertDialog.Builder(getParentActivity())', self.ui)
        self.assertIn('Резервный WS/80 без TLS', self.ui)

    def test_opt_in_bounded_and_no_raw_strings(self):
        self.assertIn('getBoolean(KEY_ENABLED, false)', self.diag)
        self.assertIn('if (!enabled(context)) return;', self.diag)
        self.assertIn('code.matches("[a-z_]{3,40}")', self.diag)
        self.assertIn("private static final int MAX_BYTES = 32 * 1024", self.diag)
        self.assertIn("clear(context);", self.diag)
        self.assertIn('append(context, "native " + checked)', self.diag)
        self.assertIn('if wsDiagnosticsEnabled.Load() {', self.go)
        self.assertNotIn('getSecretWithPrefix()', self.diag)
        self.assertNotIn('getString("secret"', self.diag)

    def test_export_requires_user_document_destination(self):
        self.assertIn("Intent.ACTION_CREATE_DOCUMENT", self.ui)
        self.assertIn("EXPORT_DIAGNOSTICS_REQUEST", self.ui)
        self.assertIn("openOutputStream(destination", self.ui)
        self.assertIn("WsProxyDiagnostics.report(", self.ui)
        self.assertIn("WsProxyDiagnostics.sample(this, snapshot)", self.service)
        self.assertIn('telegram_state_waiting', self.service)
        self.assertIn("Локальный порт запущен; Telegram не проверен", self.service)


if __name__ == "__main__":
    unittest.main()
