package soundgram.wsproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Opt-in, on-device, bounded WS diagnostic log.
 * Only fixed event codes and numerical native counters enter this file.
 * Never pass URLs, server names, exception messages, Telegram data or secrets.
 */
public final class WsProxyDiagnostics {
    private static final String PREF_NAME = "tgwsproxy";
    private static final String KEY_ENABLED = "diagnostics_enabled";
    private static final String FILE_NAME = "ws-proxy-diagnostics.log";
    private static final int MAX_BYTES = 32 * 1024;
    private static final String[] NATIVE_KEYS = {
            "dns_ok", "dns_fail", "tcp_ok", "tcp_fail", "tls_ok", "tls_fail",
            "ws_ok", "ws_fail", "http_403", "http_429", "cf_try", "ws_try"
    };

    private WsProxyDiagnostics() {}

    public static boolean enabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static synchronized void setEnabled(Context context, boolean value) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, value).apply();
        clear(context); // No prior session is silently exposed on re-enable.
        NativeProxy.setDiagnosticsEnabled(value);
        if (value) event(context, "diagnostics_enabled");
    }

    public static synchronized void clear(Context context) {
        File f = new File(context.getFilesDir(), FILE_NAME);
        if (f.exists() && !f.delete()) {
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                // Truncate any old data.
            } catch (IOException ignored) {}
        }
    }

    public static synchronized void event(Context context, String code) {
        if (!enabled(context) || code == null || !code.matches("[a-z_]{3,40}")) return;
        append(context, code);
    }

    public static synchronized void sample(Context context, String snapshot) {
        if (!enabled(context)) return;
        String checked = validateNativeSnapshot(snapshot);
        if (!checked.isEmpty()) append(context, "native " + checked);
    }

    /** A strict whitelist makes it impossible to export an engine log line or endpoint. */
    static String validateNativeSnapshot(String snapshot) {
        if (snapshot == null || snapshot.length() > 320) return "";
        String[] fields = snapshot.split(";", -1);
        if (fields.length != NATIVE_KEYS.length) return "";
        for (int i = 0; i < fields.length; i++) {
            String prefix = NATIVE_KEYS[i] + "=";
            if (!fields[i].startsWith(prefix)) return "";
            String num = fields[i].substring(prefix.length());
            if (num.isEmpty() || num.length() > 12) return "";
            for (int j = 0; j < num.length(); j++) {
                if (num.charAt(j) < '0' || num.charAt(j) > '9') return "";
            }
        }
        return snapshot;
    }

    private static String utc(long millis) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date(millis));
    }

    private static String network(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "unknown";
            Network active = cm.getActiveNetwork();
            if (active == null) return "offline";
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null) return "unknown";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            return "other";
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private static void append(Context context, String code) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            String line = utc(System.currentTimeMillis()) + " network=" + network(context)
                    + " " + code + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.US_ASCII));
            }
            if (file.length() > MAX_BYTES) {
                String all = read(file);
                int cut = all.indexOf('\n', all.length() / 2);
                String keep = cut >= 0 ? all.substring(cut + 1) : "";
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(keep.getBytes(StandardCharsets.US_ASCII));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Logging must never interfere with the proxy.
        }
    }

    private static String read(File file) throws IOException {
        if (!file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int size;
            while ((size = in.read(buffer)) != -1 && out.size() < MAX_BYTES * 2) {
                out.write(buffer, 0, Math.min(size, MAX_BYTES * 2 - out.size()));
            }
            return new String(out.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    /** Only the person using the phone chooses the destination via ACTION_CREATE_DOCUMENT. */
    public static synchronized String report(Context context, boolean running) {
        SharedPreferences p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String version = "unknown";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        StringBuilder text = new StringBuilder(1000);
        text.append("Velora WS Proxy diagnostics v1\n");
        text.append("Generated UTC: ").append(utc(System.currentTimeMillis())).append('\n');
        text.append("Velora version: ").append(version).append('\n');
        text.append("Android API: ").append(Build.VERSION.SDK_INT).append('\n');
        text.append("Network at export: ").append(network(context)).append('\n');
        text.append("Local listener running: ").append(running).append('\n');
        text.append("Local port: ").append(p.getInt("port", 1443)).append('\n');
        text.append("CF enabled: ").append(p.getBoolean("cf_enabled", true)).append('\n');
        text.append("FakeTLS enabled: ").append(p.getBoolean("fake_tls", false)).append('\n');
        text.append("Diagnostics enabled: ").append(enabled(context)).append('\n');
        text.append("Note: local TCP availability does not prove Telegram connectivity.\n");
        text.append("Events (UTC; no endpoints, keys or message contents):\n");
        try {
            String data = read(new File(context.getFilesDir(), FILE_NAME));
            text.append(data.isEmpty() ? "(none)\n" : data);
        } catch (IOException | RuntimeException ignored) {
            text.append("(unavailable)\n");
        }
        return text.toString();
    }
}
