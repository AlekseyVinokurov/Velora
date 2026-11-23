package soundgram.wsproxy;

import android.util.Log;

import androidx.annotation.Keep;

@Keep
public class NativeProxy {

    private static boolean isLoaded = false;

    static {
        try {
            System.loadLibrary("tgwsproxy");
            Log.i("NativeProxy", "tgwsproxy loaded");
        } catch (Throwable e) {
            Log.e("NativeProxy", "Failed to load tgwsproxy", e);
        }
        try {
            System.loadLibrary("tgwsproxy_jni");
            isLoaded = true;
            Log.i("NativeProxy", "tgwsproxy_jni loaded");
        } catch (Throwable e) {
            Log.e("NativeProxy", "Failed to load tgwsproxy_jni", e);
        }
    }

    public static boolean isLoaded() {
        return isLoaded;
    }

    public static native int StartProxy(String host, int port, String dcIps, String secret, int verbose);
    public static native int StopProxy();
    public static native void SetPoolSize(int size);
    public static native void SetRecvBuffer(int size);
    public static native void SetSendBuffer(int size);
    public static native void SetCfProxyCacheDir(String cacheDir);
    public static native void SetCfProxyConfig(int enabled, int priority, String userDomain);
    public static native void SetFakeTls(int enabled, String domain);
    public static native String GetSecretWithPrefix();
    public static native String GetStats();
    public static native String GetDiagnosticStats();
    public static native void SetDiagnosticEnabled(int enabled);
    public static native void FreeString(String p);
    public static native void ResetForNetworkChange();
    public static native void ResetCooldowns();

    public static int startProxy(String host, int port, String dcIps, String secret, int verbose) {
        if (!isLoaded) return -1;
        try {
            return StartProxy(host, port, dcIps, secret, verbose);
        } catch (Throwable t) {
            Log.e("NativeProxy", "StartProxy error", t);
            return -1;
        }
    }

    public static int stopProxy() {
        if (!isLoaded) return -1;
        try {
            return StopProxy();
        } catch (Throwable t) {
            Log.e("NativeProxy", "StopProxy error", t);
            return -1;
        }
    }

    public static void setPoolSize(int size) {
        if (!isLoaded) return;
        try {
            SetPoolSize(size);
        } catch (Throwable t) {
            Log.e("NativeProxy", "SetPoolSize error", t);
        }
    }

    public static void setRecvBuffer(int size) {
        if (!isLoaded) return;
        try {
            SetRecvBuffer(size);
        } catch (Throwable t) {
            Log.e("NativeProxy", "SetRecvBuffer error", t);
        }
    }

    public static void setSendBuffer(int size) {
        if (!isLoaded) return;
        try {
            SetSendBuffer(size);
        } catch (Throwable t) {
            Log.e("NativeProxy", "SetSendBuffer error", t);
        }
    }

    public static void setCfProxyCacheDir(String cacheDir) {
        if (!isLoaded) return;
        try {
            SetCfProxyCacheDir(cacheDir);
        } catch (Throwable t) {
            Log.e("NativeProxy", "SetCfProxyCacheDir error", t);
        }
    }

    public static void setCfProxyConfig(boolean enabled, boolean priority, String userDomain) {
        if (!isLoaded) return;
        try {
            SetCfProxyConfig(enabled ? 1 : 0, priority ? 1 : 0, userDomain != null ? userDomain : "");
        } catch (Throwable t) {
            Log.e("NativeProxy", "SetCfProxyConfig error", t);
        }
    }

    public static void setFakeTls(boolean enabled, String domain) {
        if (!isLoaded) return;
        try {
            SetFakeTls(enabled ? 1 : 0, domain != null ? domain : "");
        } catch (Throwable t) {
            Log.e("NativeProxy", "SetFakeTls error", t);
        }
    }

    public static String getSecretWithPrefix() {
        if (!isLoaded) return "";
        try {
            return GetSecretWithPrefix();
        } catch (Throwable t) {
            Log.e("NativeProxy", "GetSecretWithPrefix error", t);
            return "";
        }
    }

    public static void setDiagnosticsEnabled(boolean enabled) {
        if (!isLoaded) return;
        try { SetDiagnosticEnabled(enabled ? 1 : 0); }
        catch (Throwable t) { Log.w("NativeProxy", "Diagnostic switch unavailable"); }
    }

    public static String getDiagnosticStats() {
        if (!isLoaded) return "";
        try {
            String value = GetDiagnosticStats();
            return value != null ? value : "";
        } catch (Throwable t) {
            return "";
        }
    }

    public static String getStats() {
        if (!isLoaded) return "";
        try {
            return GetStats();
        } catch (Throwable t) {
            Log.e("NativeProxy", "GetStats error", t);
            return "";
        }
    }

    /** Called by the service when the network interface changes (WiFi ↔ LTE).
     *  Flushes the WS pool and all fail-cooldowns without restarting the proxy. */
    public static void resetForNetworkChange() {
        try { ResetForNetworkChange(); } catch (Throwable ignored) {}
    }

    /**
     * Clears all CF 429 cooldowns, DC fail counters and WS blacklists without
     * restarting the proxy. Call when the proxy is alive but quality is degraded
     * (e.g. watchdog restart, user manual refresh, or periodic evening recovery).
     */
    public static void resetCooldowns() {
        try { ResetCooldowns(); } catch (Throwable ignored) {}
    }
}
