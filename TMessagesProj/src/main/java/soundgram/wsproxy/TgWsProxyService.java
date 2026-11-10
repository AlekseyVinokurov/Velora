package soundgram.wsproxy;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import java.net.Socket;
import java.net.InetSocketAddress;
import org.telegram.utils.proxy.ProxySettings;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground lifetime is a single explicit user session. No alarms, workers or sticky restart. */
public class TgWsProxyService extends Service {
    public static final String ACTION_START = "tgws.proxy.START";
    public static final String ACTION_STOP  = "tgws.proxy.STOP";
    public static final String ACTION_STATE_CHANGED = "tgws.proxy.STATE_CHANGED";

    public static final String EXTRA_PORT        = "port";
    public static final String EXTRA_SECRET      = "secret";
    public static final String EXTRA_POOL_SIZE   = "pool";
    public static final String EXTRA_RECV_BUF    = "recv_buf";
    public static final String EXTRA_SEND_BUF    = "send_buf";
    public static final String EXTRA_CF_ENABLED  = "cf";
    public static final String EXTRA_CF_DOMAIN   = "cf_domain";
    public static final String EXTRA_FAKE_TLS    = "fake_tls";
    public static final String EXTRA_FAKE_DOMAIN = "fake_domain";
    public static final String EXTRA_VERBOSE     = "verbose";
    public static final String EXTRA_DC_IPS      = "dc_ips";
    public static final String EXTRA_FROM_WORKER = "from_worker";
    public static final String EXTRA_IS_HEARTBEAT = "heartbeat"; // distinguishes periodic alarm from real start


    public static volatile boolean isRunning;
    public static volatile int currentPort;
    private static final Object NATIVE_LOCK = new Object();
    private static volatile boolean stopping;
    public static boolean isStopInProgress() { return stopping; }
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private volatile long token;
    private boolean routeApplied;
    private Thread monitor;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private SharedPreferences.OnSharedPreferenceChangeListener proxySettingsListener;
    private SharedPreferences prefs() { return getSharedPreferences("tgwsproxy", MODE_PRIVATE); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        long requested = intent == null ? 0 : intent.getLongExtra("manual_session", 0);
        if (intent == null || !ACTION_START.equals(intent.getAction()) || requested == 0
                || requested != WsProxyPolicy.session()) {
            if (intent != null && ACTION_STOP.equals(intent.getAction())) WsProxyPolicy.stopManually(this);
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        token = requested;
        try {
            showNotification();
        } catch (RuntimeException e) {
            fail("Android не разрешил foreground service. Повторите из открытого приложения.");
            return START_NOT_STICKY;
        }
        executor.execute(() -> {
            synchronized (NATIVE_LOCK) {
                if (!active(requested)) return;
                stopNative();
                if (!active(requested)) return;
                SharedPreferences p = prefs();
                int port = p.getInt("port", 1443);
                String secret = ProxyAutoDetector.getOrCreateSecret(p);
                if (port < 1024 || port > 65535 || !secret.matches("[a-fA-F0-9]{32}")) {
                    fail("Проверьте локальный порт (1024–65535) и секрет (32 hex-символа).");
                    return;
                }
                // Do not mistake another process's listener for our own native startup.
                if (isPortOpen("127.0.0.1", port, 150)) {
                    fail("Локальный порт занят. Выберите другой порт.");
                    return;
                }
                if (!NativeProxy.isLoaded()) { fail("Не удалось загрузить библиотеку WS Proxy."); return; }
                NativeProxy.setPoolSize(Math.max(2, Math.min(16, p.getInt("pool_size", 4))));
                int buffer = Math.max(16, Math.min(1024, p.getInt("buffer", 256)));
                NativeProxy.setRecvBuffer(buffer);
                NativeProxy.setSendBuffer(buffer);
                NativeProxy.setCfProxyCacheDir(getCacheDir().getAbsolutePath());
                NativeProxy.setCfProxyConfig(p.getBoolean("cf_enabled", true), true, p.getString("cf_domain", ""));
                NativeProxy.setFakeTls(p.getBoolean("fake_tls", false), p.getString("fake_domain", "google.com"));
                int result = NativeProxy.startProxy("127.0.0.1", port, p.getString("dc_ips", ""), secret, 0);
                if (!active(requested)) { NativeProxy.stopProxy(); return; }
                if (result != 0 || !isPortOpen("127.0.0.1", port, 500)) {
                    NativeProxy.stopProxy();
                    fail("Не удалось запустить WS Proxy (код " + result + "). Проверьте настройки и повторите вручную.");
                    return;
                }
                currentPort = port;
                isRunning = true;
                String prefixed = NativeProxy.getSecretWithPrefix();
                if (prefixed == null || prefixed.isEmpty()) { fail("WS Proxy не вернул секрет подключения."); return; }
                final String routeSecret = prefixed;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(requested) || !isRunning) return;
                    // Runtime route only: no mainconfig, proxy list or currentProxy mutation.
                    ConnectionsManager.setProxySettings(true, "127.0.0.1", port, "", "", routeSecret);
                    routeApplied = true;
                    watchSavedProxyChanges();
                    sendState();
                });
                watchNetwork();
                startMonitor(requested, port);
            }
        });
        return START_NOT_STICKY;
    }

    private boolean active(long requested) {
        return !destroyed && requested != 0 && token == requested && WsProxyPolicy.session() == requested;
    }

    private void watchSavedProxyChanges() {
        if (proxySettingsListener != null) return;
        proxySettingsListener = (preferences, key) -> {
            if (key != null && key.startsWith("proxy_")) {
                // A user-selected Telegram proxy always takes precedence.
                WsProxyPolicy.stopManually(this);
            }
        };
        MessagesController.getGlobalMainSettings().registerOnSharedPreferenceChangeListener(proxySettingsListener);
    }

    private void watchNetwork() {
        if (networkCallback != null) return;
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivity == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { resetNetwork(); }
            @Override public void onLost(Network network) { resetNetwork(); }
        };
        try { connectivity.registerDefaultNetworkCallback(networkCallback); }
        catch (RuntimeException ignored) { networkCallback = null; }
    }

    private void resetNetwork() {
        if (!isRunning || destroyed) return;
        if (ProxyAutoDetector.isVpnActive(this)) {
            fail("VPN активен. Velora Bypass остановлен; повторное включение только вручную.");
        } else {
            try {
                executor.execute(() -> { synchronized (NATIVE_LOCK) {
                    if (!destroyed && isRunning) NativeProxy.resetForNetworkChange();
                }});
            } catch (java.util.concurrent.RejectedExecutionException ignored) {}
        }
    }

    private void startMonitor(long requested, int port) {
        if (monitor != null) monitor.interrupt();
        monitor = new Thread(() -> {
            int failures = 0;
            while (active(requested) && isRunning) {
                try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                if (!active(requested) || !isRunning) return;
                if (isPortOpen("127.0.0.1", port, 500)) failures = 0;
                else if (++failures >= 2) {
                    fail("WS Proxy перестал отвечать. Повторите включение вручную.");
                    return;
                }
            }
        }, "velora-proxy-health");
        monitor.start();
    }

    private void fail(String message) {
        prefs().edit().putString("last_error", message).apply();
        AndroidUtilities.runOnUIThread(() -> WsProxyPolicy.stopManually(this));
        sendState();
    }

    private void showNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("tgws_proxy", "Velora Bypass", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Intent stop = new Intent(this, TgWsProxyService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification notification = new NotificationCompat.Builder(this, "tgws_proxy")
                .setContentTitle("Velora Bypass")
                .setContentText("WS Proxy · " + prefs().getInt("port", 1443))
                .setSmallIcon(R.drawable.proxy_off_solar).setOngoing(true).setSilent(true)
                .addAction(0, "Отключить", stopIntent).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(101, notification);
    }

    private void stopNative() {
        if (monitor != null) { monitor.interrupt(); monitor = null; }
        // Do not load the Go runtime when a rejected start is being destroyed.
        if (isRunning) NativeProxy.stopProxy();
        isRunning = false;
        currentPort = 0;
    }

    private void restoreSavedRoute() {
        if (!routeApplied) return;
        routeApplied = false;
        SharedPreferences p = MessagesController.getGlobalMainSettings();
        ConnectionsManager.setProxySettings(p.getBoolean("proxy_enabled", false), ProxySettings.fromSharedPreferences(p));
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    private void sendState() {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName())
                .putExtra("running", isRunning).putExtra("port", currentPort));
    }

    public static boolean isPortOpen(String host, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (Exception e) { return false; }
    }
    public static int getPingMs(int port) {
        long start = android.os.SystemClock.elapsedRealtime();
        return isRunning && isPortOpen("127.0.0.1", port, 500)
                ? (int) (android.os.SystemClock.elapsedRealtime() - start) : -1;
    }

    @Override public void onTimeout(int startId, int type) { WsProxyPolicy.stopManually(this); }

    @Override public void onDestroy() {
        destroyed = true;
        stopping = true;
        if (proxySettingsListener != null) MessagesController.getGlobalMainSettings()
                .unregisterOnSharedPreferenceChangeListener(proxySettingsListener);
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
        }
        restoreSavedRoute();
        if (WsProxyPolicy.session() == token) WsProxyPolicy.disableLegacyPreferences(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        executor.execute(() -> { synchronized (NATIVE_LOCK) {
            stopNative();
            stopping = false;
            sendState();
        }});
        executor.shutdown();
        super.onDestroy();
    }
}
