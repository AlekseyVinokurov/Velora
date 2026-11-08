package soundgram.wsproxy;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Checks whether Telegram DCs are reachable.
 * Monitors network changes and VPN state to auto-enable/disable proxy.
 */
public class ProxyAutoDetector implements NotificationCenter.NotificationCenterDelegate {

    private static final String TAG = "ProxyAutoDetect";

    // Primary Telegram Data Centers to test direct access
    private static final String[] TG_DCS = {
        "149.154.167.51", // DC2 (Amsterdam / Primary RU & EU)
        "149.154.167.50", // DC2
        "149.154.167.91", // DC4
        "149.154.175.53", // DC1
        "91.108.56.130"   // DC5
    };
    private static final int TG_PORT = 443;
    private static final int TG_TIMEOUT = 1200;

    private static final int PORT_WAIT_TIMEOUT_MS = 8_000;
    private static final int PORT_WAIT_INTERVAL_MS = 300;

    private static ConnectivityManager connectivityManager;
    private static ConnectivityManager.NetworkCallback networkCallback;
    private static volatile boolean isInitialized = false;
    private static volatile boolean vpnActive = false;
    private static volatile int lastNetworkTransport = -2;
    private static volatile boolean lastVpnState = false;
    private static volatile boolean isEvaluatingNetwork = false;
    private static Context sContext;
    private static final ProxyAutoDetector sDelegate = new ProxyAutoDetector();
    private static volatile long lastConnectionCheckTime = 0;
    private static volatile long sLastAutoCheckTime = 0;

    public static void init(Context context) {
        if (!WsProxyPolicy.isAvailable()) return;
        if (isInitialized) return;
        isInitialized = true;
        sContext = context.getApplicationContext();

        vpnActive = isVpnActive(sContext);
        lastVpnState = vpnActive;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            registerNetworkCallback(sContext);
        }

        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                NotificationCenter.getInstance(i).addObserver(sDelegate, NotificationCenter.didUpdateConnectionState);
            }
        });

        // Delay initial check on startup so Telegram has a chance to connect directly
        SharedPreferences prefs = sContext.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean("auto_start", false);
        if (autoStart && !isVpnActive(sContext)) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                new Thread(() -> {
                    checkAndAutoEnable(sContext);
                }, "proxy-auto-detect-init").start();
            }, 4000);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!WsProxyPolicy.isAvailable()) return;
        if (id == NotificationCenter.didUpdateConnectionState) {
            if (account != UserConfig.selectedAccount) return;
            if (sContext == null) return;
            SharedPreferences prefs = sContext.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);

            // 1. If auto_start is turned OFF: bypass MUST NOT start automatically
            if (!prefs.getBoolean("auto_start", false)) return;

            // 2. If VPN is active: bypass sleeps
            if (isVpnActive(sContext)) return;

            // 3. If bypass is already running: nothing to do
            if (TgWsProxyService.isRunning) return;

            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (state == ConnectionsManager.ConnectionStateConnecting || state == ConnectionsManager.ConnectionStateWaitingForNetwork) {
                long now = System.currentTimeMillis();
                if (now - lastConnectionCheckTime < 10000) {
                    return;
                }
                lastConnectionCheckTime = now;

                // Wait 4 seconds to see if Telegram connects directly on its own
                AndroidUtilities.runOnUIThread(() -> {
                    if (sContext == null || isVpnActive(sContext) || TgWsProxyService.isRunning) return;
                    int curState = ConnectionsManager.getInstance(account).getConnectionState();
                    if (curState == ConnectionsManager.ConnectionStateConnected || curState == ConnectionsManager.ConnectionStateUpdating) {
                        return; // Connected directly!
                    }
                    new Thread(() -> {
                        checkAndAutoEnable(sContext);
                    }, "proxy-auto-detect-connection").start();
                }, 4000);
            }
        }
    }

    private static int getActiveTransport(ConnectivityManager cm) {
        if (cm == null) return -1;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return NetworkCapabilities.TRANSPORT_VPN;
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return NetworkCapabilities.TRANSPORT_WIFI;
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return NetworkCapabilities.TRANSPORT_CELLULAR;
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return NetworkCapabilities.TRANSPORT_ETHERNET;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static void registerNetworkCallback(Context context) {
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        lastNetworkTransport = getActiveTransport(connectivityManager);

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network n) {
                int currentTransport = getActiveTransport(connectivityManager);
                boolean vpnNow = isVpnActive(context);
                if (currentTransport != lastNetworkTransport || vpnNow != lastVpnState) {
                    lastNetworkTransport = currentTransport;
                    lastVpnState = vpnNow;
                    evaluateNetwork(context);
                }
            }

            @Override public void onLost(@NonNull Network n) {
                int currentTransport = getActiveTransport(connectivityManager);
                lastNetworkTransport = currentTransport;
                lastVpnState = isVpnActive(context);
                evaluateNetwork(context);
            }

            @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
                boolean vpnNow = isVpnActive(context);
                int currentTransport = getActiveTransport(connectivityManager);
                // Only evaluate if transport type or VPN status truly changed.
                // Ignore transient RSSI / signal strength fluctuations!
                if (vpnNow != lastVpnState || (currentTransport != -1 && currentTransport != lastNetworkTransport)) {
                    lastVpnState = vpnNow;
                    lastNetworkTransport = currentTransport;
                    evaluateNetwork(context);
                }
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } else {
                connectivityManager.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "registerNetworkCallback failed", e);
        }
    }

    private static synchronized void evaluateNetwork(Context context) {
        if (context == null || isEvaluatingNetwork) return;
        isEvaluatingNetwork = true;
        try {
            boolean hasVpn = isVpnActive(context);
            SharedPreferences prefs = context.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);

            if (hasVpn) {
                vpnActive = true;
                // VPN activated: bypass MUST enter sleep mode until VPN is disconnected
                boolean wasRunning = TgWsProxyService.isRunning || prefs.getBoolean("enabled", false);
                if (wasRunning) {
                    Log.i(TAG, "VPN activated -> bypass entering sleep mode");
                    prefs.edit()
                            .putBoolean("vpn_sleep", true)
                            .putBoolean("enabled", false)
                            .apply();

                    stopProxyService(context);
                    disableTelegramProxy();
                    sendStateChangedBroadcast(context);
                }
                return;
            }

            // VPN is NOT active
            boolean wasInVpnSleep = prefs.getBoolean("vpn_sleep", false);
            if (vpnActive || wasInVpnSleep) {
                vpnActive = false;
                prefs.edit().putBoolean("vpn_sleep", false).apply();
                Log.i(TAG, "VPN disconnected -> waking up bypass from sleep mode");

                // Only after VPN is turned off should bypass automatically start
                new Thread(() -> {
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                    if (isVpnActive(context)) return;

                    boolean manual = prefs.getBoolean("manual_enabled", false);
                    boolean autoStart = prefs.getBoolean("auto_start", false);

                    if (manual) {
                        Log.i(TAG, "Resuming manual bypass after VPN");
                        prefs.edit().putBoolean("enabled", true).apply();
                        startProxyService(context, prefs);
                    } else if (autoStart) {
                        Log.i(TAG, "Checking TG access after VPN disconnect");
                        checkAndAutoEnable(context);
                    }
                }, "proxy-vpn-wakeup").start();
                return;
            }

            // Normal network switch (e.g. Wi-Fi <-> Cellular)
            boolean autoStart = prefs.getBoolean("auto_start", false);
            boolean manual = prefs.getBoolean("manual_enabled", false);

            if (TgWsProxyService.isRunning) {
                new Thread(() -> {
                    NativeProxy.resetForNetworkChange();
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    startProxyService(context, prefs);
                }, "proxy-net-change").start();
            } else if (autoStart && !manual) {
                new Thread(() -> {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    checkAndAutoEnable(context);
                }, "proxy-net-recheck").start();
            }
        } finally {
            isEvaluatingNetwork = false;
        }
    }

    public static boolean isVpnActive(Context context) {
        if (context == null) return false;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) {
                        return true;
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Network[] networks = cm.getAllNetworks();
                if (networks != null) {
                    for (Network n : networks) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isVpnActive error", t);
        }
        return false;
    }

    public static boolean isTelegramReachableDirectly() {
        // If Telegram is already connected without proxy, it is accessible directly
        if (!SharedConfig.isProxyEnabled() && !TgWsProxyService.isRunning) {
            try {
                int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
                if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        // Test direct reachability in parallel across DCs with 1200ms timeout
        final java.util.concurrent.atomic.AtomicBoolean reachable = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(TG_DCS.length);
        for (String dc : TG_DCS) {
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    if (!reachable.get() && tcpPing(dc, TG_PORT, TG_TIMEOUT)) {
                        reachable.set(true);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(1400, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        return reachable.get();
    }

    public static boolean isInternetAvailable() {
        final java.util.concurrent.atomic.AtomicBoolean available = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(3);
        final String[] hosts = {"8.8.8.8", "1.1.1.1", "google.com"};
        final int[] ports = {53, 53, 443};
        final int[] timeouts = {1200, 1200, 1500};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    if (!available.get() && tcpPing(hosts[idx], ports[idx], timeouts[idx])) {
                        available.set(true);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(1600, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        return available.get();
    }

    public static void checkAndAutoEnable(Context ctx) {
        if (!WsProxyPolicy.isAvailable()) return;
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (now - sLastAutoCheckTime < 6000) {
            return;
        }
        sLastAutoCheckTime = now;
        SharedPreferences prefs = ctx.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);

        // 1. If auto_start is OFF: bypass MUST NOT start automatically
        if (!prefs.getBoolean("auto_start", false)) {
            Log.i(TAG, "Auto-start is disabled — skipping bypass auto-enable");
            return;
        }

        // 2. If VPN is active: bypass MUST be in sleep mode
        if (isVpnActive(ctx)) {
            Log.i(TAG, "VPN is active — skipping bypass auto-enable");
            return;
        }

        // 3. If bypass is already running: no action needed
        if (TgWsProxyService.isRunning) {
            return;
        }

        // 4. Check if Telegram servers are accessible directly
        Log.i(TAG, "Checking direct Telegram reachability...");
        if (isTelegramReachableDirectly()) {
            Log.i(TAG, "Telegram reachable directly — bypass not needed");
            return;
        }

        // 5. If Telegram is unreachable, make sure internet is working
        Log.i(TAG, "Telegram unreachable — checking internet connection...");
        if (!isInternetAvailable()) {
            Log.i(TAG, "No internet connection — skipping bypass");
            return;
        }

        // 6. Telegram is blocked, internet is available, VPN is off, auto_start is on -> enable bypass
        Log.i(TAG, "Telegram blocked, internet OK — auto-enabling bypass");
        prefs.edit().putBoolean("enabled", true).apply();
        startProxyService(ctx, prefs);
    }

    public static String getOrCreateSecret(SharedPreferences prefs) {
        String secret = prefs.getString("secret", "");
        if (secret == null || secret.isEmpty()) {
            byte[] b = new byte[16];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte v : b) sb.append(String.format("%02x", v));
            secret = sb.toString();
            prefs.edit().putString("secret", secret).apply();
        }
        return secret;
    }

    public static void startProxyService(Context ctx, SharedPreferences prefs) {
        if (!WsProxyPolicy.isAvailable()) return;
        int port = prefs.getInt("port", 1443);
        String secret = getOrCreateSecret(prefs);
        
        Intent intent = new Intent(ctx, TgWsProxyService.class);
        intent.setAction(TgWsProxyService.ACTION_START);
        intent.putExtra(TgWsProxyService.EXTRA_PORT,        port);
        intent.putExtra(TgWsProxyService.EXTRA_SECRET,      secret);
        intent.putExtra(TgWsProxyService.EXTRA_POOL_SIZE,   prefs.getInt("pool_size",       4));
        intent.putExtra(TgWsProxyService.EXTRA_RECV_BUF,    prefs.getInt("buffer",         256));
        intent.putExtra(TgWsProxyService.EXTRA_SEND_BUF,    prefs.getInt("buffer",         256));
        intent.putExtra(TgWsProxyService.EXTRA_CF_ENABLED,  prefs.getBoolean("cf_enabled", true));
        intent.putExtra(TgWsProxyService.EXTRA_CF_DOMAIN,   prefs.getString("cf_domain",   ""));
        intent.putExtra(TgWsProxyService.EXTRA_FAKE_TLS,    prefs.getBoolean("fake_tls",   false));
        intent.putExtra(TgWsProxyService.EXTRA_FAKE_DOMAIN, prefs.getString("fake_domain", "google.com"));
        intent.putExtra(TgWsProxyService.EXTRA_VERBOSE,     0);
        intent.putExtra(TgWsProxyService.EXTRA_DC_IPS,      prefs.getString("dc_ips",      ""));

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to start TgWsProxyService foreground service: " + e.getMessage());
            try {
                ctx.startService(intent);
            } catch (Throwable ignored) {}
        }

        waitForPortAndApply(ctx, port, secret);
    }

    public static void stopProxyService(Context context) {
        if (context == null) return;
        try {
            Intent stopIntent = new Intent(context, TgWsProxyService.class);
            stopIntent.setAction(TgWsProxyService.ACTION_STOP);
            context.startService(stopIntent);
        } catch (Throwable t) {
            Log.w(TAG, "stopProxyService failed", t);
        }
    }

    private static void waitForPortAndApply(Context ctx, int port, String secret) {
        long deadline = System.currentTimeMillis() + PORT_WAIT_TIMEOUT_MS;
        boolean opened = false;

        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(PORT_WAIT_INTERVAL_MS); } catch (InterruptedException e) { return; }
            if (tcpPing("127.0.0.1", port, 800)) {
                opened = true;
                break;
            }
        }

        if (!opened) {
            Log.w(TAG, "Proxy port didn't open in time — skipping applyTelegramProxySettings");
            return;
        }

        String proxySecret = NativeProxy.getSecretWithPrefix();
        if (proxySecret == null || proxySecret.isEmpty()) {
            proxySecret = "dd" + secret;
        }
        final String finalSecret = proxySecret;
        final int finalPort = port;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                org.telegram.messenger.SharedConfig.ProxyInfo existing = null;
                for (org.telegram.messenger.SharedConfig.ProxyInfo info : org.telegram.messenger.SharedConfig.proxyList) {
                    if ("127.0.0.1".equals(info.address) && info.port == finalPort) {
                        existing = info;
                        break;
                    }
                }

                org.telegram.messenger.SharedConfig.ProxyInfo proxyInfo;
                if (existing != null) {
                    existing.secret = finalSecret;
                    proxyInfo = existing;
                } else {
                    proxyInfo = new org.telegram.messenger.SharedConfig.ProxyInfo("127.0.0.1", finalPort, "", "", finalSecret);
                    org.telegram.messenger.SharedConfig.addProxy(proxyInfo);
                }

                org.telegram.messenger.SharedConfig.currentProxy = proxyInfo;
                org.telegram.messenger.SharedConfig.saveProxyList();

                org.telegram.messenger.MessagesController.getGlobalMainSettings()
                        .edit()
                        .putBoolean("proxy_enabled", true)
                        .putString("proxy_ip",     "127.0.0.1")
                        .putString("proxy_user",   "")
                        .putString("proxy_pass",   "")
                        .putInt("proxy_port",       finalPort)
                        .putString("proxy_secret",  finalSecret)
                        .commit();

                org.telegram.tgnet.ConnectionsManager.setProxySettings(true, "127.0.0.1", finalPort, "", "", finalSecret);
                org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.proxySettingsChanged);

            } catch (Exception e) {
                Log.e(TAG, "Failed to apply Telegram proxy settings", e);
            }
        });
    }

    public static void disableTelegramProxy() {
        if (!WsProxyPolicy.isAvailable()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                org.telegram.messenger.MessagesController.getGlobalMainSettings()
                        .edit().putBoolean("proxy_enabled", false).commit();
                org.telegram.tgnet.ConnectionsManager.setProxySettings(
                        false, "", 0, "", "", "");
                org.telegram.messenger.NotificationCenter.getGlobalInstance()
                        .postNotificationName(
                                org.telegram.messenger.NotificationCenter.proxySettingsChanged);
            } catch (Exception e) {
                Log.e(TAG, "Failed to disable Telegram proxy settings", e);
            }
        });
    }

    private static void sendStateChangedBroadcast(Context context) {
        Intent intent = new Intent(TgWsProxyService.ACTION_STATE_CHANGED);
        intent.setPackage(context.getPackageName());
        intent.putExtra("running", TgWsProxyService.isRunning);
        intent.putExtra("port", TgWsProxyService.currentPort);
        context.sendBroadcast(intent);
    }

    public static boolean tcpPing(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
