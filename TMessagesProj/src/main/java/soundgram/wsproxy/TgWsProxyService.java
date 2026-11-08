package soundgram.wsproxy;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.telegram.messenger.R;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TgWsProxyService extends Service {

    private static final String TAG = "TgWsProxy";
    private static final int NOTIFICATION_ID = 101;
    private static final String CHANNEL_ID = "tgws_proxy";

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

    public static volatile boolean isRunning = false;
    public static volatile int currentPort = 1443;

    // Expose for SmartConfig polling
    private static final AtomicBoolean sStopInProgress = new AtomicBoolean(false);
    public static boolean isStopInProgress() { return sStopInProgress.get(); }

    private PowerManager.WakeLock wakeLock;
    private ExecutorService executor;
    private volatile boolean userStopped = false;
    private volatile boolean destroyed = false;

    // Startup watchdog: if port doesn't open within 7s → force kill + restart
    private static final int STARTUP_TIMEOUT_MS = 7_000;
    private static int consecutiveStartupFails = 0;
    private static final int MAX_STARTUP_FAILS = 3;

    // Unified health & loss monitor (10s interval)
    private static final int MONITOR_INTERVAL_MS = 10_000;
    private Thread monitorThread;
    private CountDownLatch runningLatch;

    // Single shared lock for start/stop serialization
    private final Object proxyLock = new Object();

    // Network / VPN monitoring is now handled by ProxyAutoDetector



    // -------------------------------------------------------------------------
    // Telegram proxy helpers (called from service, posted to main thread)
    // -------------------------------------------------------------------------

    private void disableTelegramProxySettings() {
        if (!WsProxyPolicy.isAvailable()) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                org.telegram.messenger.MessagesController.getGlobalMainSettings()
                        .edit().putBoolean("proxy_enabled", false).commit();
                org.telegram.tgnet.ConnectionsManager.setProxySettings(
                        false, "", 0, "", "", "");
                org.telegram.messenger.NotificationCenter.getGlobalInstance()
                        .postNotificationName(
                                org.telegram.messenger.NotificationCenter.proxySettingsChanged);
            } catch (Exception e) {
                Log.w(TAG, "disableTelegramProxy", e);
            }
        });
    }

    private void applyTelegramProxySettings() {
        if (!WsProxyPolicy.isAvailable()) return;
        SharedPreferences p = getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
        int port = p.getInt("port", 1443);
        String secret = p.getString("secret", "");
        if (secret.isEmpty()) return;

        String proxySecret = NativeProxy.getSecretWithPrefix();
        if (proxySecret == null) proxySecret = "dd" + secret;
        final String finalSecret = proxySecret;
        final int finalPort = port;

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                org.telegram.messenger.SharedConfig.ProxyInfo existing = null;
                for (org.telegram.messenger.SharedConfig.ProxyInfo info :
                        org.telegram.messenger.SharedConfig.proxyList) {
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
                    proxyInfo = new org.telegram.messenger.SharedConfig.ProxyInfo(
                            "127.0.0.1", finalPort, "", "", finalSecret);
                    org.telegram.messenger.SharedConfig.addProxy(proxyInfo);
                }
                org.telegram.messenger.SharedConfig.currentProxy = proxyInfo;
                org.telegram.messenger.SharedConfig.saveProxyList();
                org.telegram.messenger.MessagesController.getGlobalMainSettings()
                        .edit()
                        .putBoolean("proxy_enabled", true)
                        .putString("proxy_ip", "127.0.0.1")
                        .putString("proxy_pass", "")
                        .putString("proxy_user", "")
                        .putInt("proxy_port", finalPort)
                        .putString("proxy_secret", finalSecret)
                        .commit();
                org.telegram.tgnet.ConnectionsManager.setProxySettings(
                        true, "127.0.0.1", finalPort, "", "", finalSecret);
                org.telegram.messenger.NotificationCenter.getGlobalInstance()
                        .postNotificationName(
                                org.telegram.messenger.NotificationCenter.proxySettingsChanged);
            } catch (Exception e) {
                Log.w(TAG, "applyTelegramProxy", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Scheduling
    // -------------------------------------------------------------------------

    private void scheduleRestart(long delayMs) {
        if (!WsProxyPolicy.isAvailable()) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!sStopInProgress.get() && !destroyed) {
                // Run blocking restartFromPrefs on a background thread
                new Thread(this::restartFromPrefs, "proxy-restart").start();
            }
        }, delayMs);
    }

    private void scheduleServiceRestart(long delayMs) {
        if (!WsProxyPolicy.isAvailable()) return;
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, TgWsProxyService.class);
            intent.setAction(ACTION_START);
            intent.putExtra(EXTRA_FROM_WORKER, true);
            PendingIntent pi = PendingIntent.getService(this, 0, intent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null && pi != null) {
                long triggerAt = SystemClock.elapsedRealtime() + delayMs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "AlarmManager SecurityException, falling back to WorkManager", e);
            scheduleWorkManagerRestart(delayMs);
        } catch (Exception e) {
            Log.e(TAG, "scheduleServiceRestart failed", e);
        }
    }

    /** Schedules a periodic liveness alarm every 5 minutes.
     *  The alarm is re-scheduled on each fire so it keeps repeating on API 23+
     *  (setAndAllowWhileIdle is one-shot; we re-arm it inside onStartCommand). */
    private void schedulePeriodicHeartbeat() {
        if (!WsProxyPolicy.isAvailable()) return;
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, TgWsProxyService.class);
            intent.setAction(ACTION_START);
            intent.putExtra(EXTRA_IS_HEARTBEAT, true); // so onStartCommand can re-arm
            PendingIntent pi = PendingIntent.getService(this, 1, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null && pi != null) {
                long intervalMs = 5 * 60 * 1000L;
                long triggerAt  = SystemClock.elapsedRealtime() + intervalMs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // setAndAllowWhileIdle is one-shot — we re-schedule it each time it fires
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                } else {
                    am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt, intervalMs, pi);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "schedulePeriodicHeartbeat failed", e);
        }
    }

    private void scheduleWorkManagerRestart(long delayMs) {
        if (!WsProxyPolicy.isAvailable()) return;
        try {
            OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(RestartWorker.class)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build();
            WorkManager.getInstance(this).enqueueUniqueWork(
                    "tgws_restart", ExistingWorkPolicy.REPLACE, workRequest);
        } catch (Exception e) {
            Log.e(TAG, "WorkManager fallback failed", e);
        }
    }

    public static class RestartWorker extends Worker {
        public RestartWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            if (!WsProxyPolicy.isAvailable()) return Result.success();
            Context ctx = getApplicationContext();
            if (ProxyAutoDetector.isVpnActive(ctx)) return Result.success();
            SharedPreferences prefs = ctx.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
            boolean manual = prefs.getBoolean("manual_enabled", false);
            boolean autoStart = prefs.getBoolean("auto_start", false);
            if (!manual && !autoStart) return Result.success();
            if (!manual && autoStart) {
                ProxyAutoDetector.checkAndAutoEnable(ctx);
                return Result.success();
            }
            if (!prefs.getBoolean("enabled", false)) return Result.success();

            Intent intent = new Intent(ctx, TgWsProxyService.class);
            intent.setAction(ACTION_START);
            intent.putExtra(EXTRA_FROM_WORKER,  true);
            intent.putExtra(EXTRA_PORT,         prefs.getInt("port",           1443));
            intent.putExtra(EXTRA_SECRET,        prefs.getString("secret",      ""));
            intent.putExtra(EXTRA_POOL_SIZE,    prefs.getInt("pool_size",       4));
            intent.putExtra(EXTRA_RECV_BUF,     prefs.getInt("buffer",         256));
            intent.putExtra(EXTRA_SEND_BUF,     prefs.getInt("buffer",         256));
            intent.putExtra(EXTRA_CF_ENABLED,   prefs.getBoolean("cf_enabled", true));
            intent.putExtra(EXTRA_CF_DOMAIN,     prefs.getString("cf_domain",   ""));
            intent.putExtra(EXTRA_FAKE_TLS,     prefs.getBoolean("fake_tls",   false));
            intent.putExtra(EXTRA_FAKE_DOMAIN,   prefs.getString("fake_domain", "google.com"));
            intent.putExtra(EXTRA_VERBOSE,      prefs.getInt("verbose",         0));
            intent.putExtra(EXTRA_DC_IPS,        prefs.getString("dc_ips",      ""));

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent);
                } else {
                    ctx.startService(intent);
                }
            } catch (Throwable e) {
                Log.w(TAG, "RestartWorker failed to start TgWsProxyService foreground: " + e.getMessage());
                try {
                    ctx.startService(intent);
                } catch (Throwable ignored) {}
            }
            return Result.success();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        if (!WsProxyPolicy.isAvailable()) {
            WsProxyPolicy.disableLegacyPreferences(this);
            stopSelf();
            return;
        }
        destroyed = false;
        ensureForegroundNotification(currentPort > 0 ? currentPort : 1443);
    }

    private void ensureForegroundNotification(int port) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "TG WS Proxy", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);

            Intent contentIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            PendingIntent contentPI = null;
            if (contentIntent != null) {
                contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                contentPI = PendingIntent.getActivity(this, 0, contentIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("TG WS Proxy")
                    .setContentText("Running on 127.0.0.1:" + port)
                    .setSmallIcon(R.drawable.proxy_off_solar)
                    .setContentIntent(contentPI)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setLocalOnly(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
            }

            Notification notification;
            try {
                notification = builder.build();
            } catch (Exception e) {
                notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("TG WS Proxy")
                        .setContentText("Running")
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOngoing(true)
                        .build();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            Log.e(TAG, "ensureForegroundNotification failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!WsProxyPolicy.isAvailable()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            userStopped = true;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(NOTIFICATION_ID);
                }
            } catch (Throwable ignore) {}
            new Thread(this::stopProxy, "proxy-stop").start();
            return START_NOT_STICKY;
        }

        SharedPreferences prefs = getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enabled", false);
        boolean manual = prefs.getBoolean("manual_enabled", false);
        boolean autoStart = prefs.getBoolean("auto_start", false);

        if (intent == null || intent.getAction() == null) {
            if (!enabled || (!manual && !autoStart)) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(Service.STOP_FOREGROUND_REMOVE);
                    } else {
                        stopForeground(true);
                    }
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.cancel(NOTIFICATION_ID);
                    }
                } catch (Throwable ignore) {}
                stopSelf();
                return START_NOT_STICKY;
            }
            int notifPort = currentPort > 0 ? currentPort : 1443;
            ensureForegroundNotification(notifPort);
            tryAutoStart();
            return START_STICKY;
        }

        int notifPort = currentPort > 0 ? currentPort : 1443;
        notifPort = intent.getIntExtra(EXTRA_PORT, notifPort);
        ensureForegroundNotification(notifPort);

        switch (intent.getAction()) {
            case ACTION_START: {
                // Heartbeat alarm (fired by schedulePeriodicHeartbeat)
                if (intent.getBooleanExtra(EXTRA_IS_HEARTBEAT, false)) {
                    // Re-arm the alarm for the next cycle (setAndAllowWhileIdle is one-shot on API 23+)
                    schedulePeriodicHeartbeat();
                    if (ProxyAutoDetector.isVpnActive(this)) {
                        break;
                    }
                    prefs = getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
                    manual = prefs.getBoolean("manual_enabled", false);
                    autoStart = prefs.getBoolean("auto_start", false);
                    if (!manual && !autoStart) {
                        break;
                    }
                    if (!isRunning && !sStopInProgress.get() && !destroyed) {
                        Log.i(TAG, "Heartbeat: proxy not running — auto-starting");
                        new Thread(this::tryAutoStart, "proxy-heartbeat-start").start();
                    } else if (isRunning) {
                        // Proxy is alive — take the opportunity to reset stale cooldowns
                        // so evening CF 429 locks don't persist until morning
                        new Thread(NativeProxy::resetCooldowns, "proxy-heartbeat-reset").start();
                    }
                    break;
                }

                if (sStopInProgress.get()) {
                    Log.w(TAG, "Start requested while stop in progress, delaying");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (!sStopInProgress.get() && !destroyed) onStartCommand(intent, flags, startId);
                    }, 1500);
                    return START_STICKY;
                }
                int port         = intent.getIntExtra(EXTRA_PORT, 1443);
                String secret    = intent.getStringExtra(EXTRA_SECRET);
                int pool         = intent.getIntExtra(EXTRA_POOL_SIZE, 4);
                int recvBuf      = intent.getIntExtra(EXTRA_RECV_BUF, 256);
                int sendBuf      = intent.getIntExtra(EXTRA_SEND_BUF, 256);
                boolean cf       = intent.getBooleanExtra(EXTRA_CF_ENABLED, true);
                String cfDomain  = intent.getStringExtra(EXTRA_CF_DOMAIN);
                boolean fakeTls  = intent.getBooleanExtra(EXTRA_FAKE_TLS, false);
                String fakeDomain = intent.getStringExtra(EXTRA_FAKE_DOMAIN);
                int verbose      = intent.getIntExtra(EXTRA_VERBOSE, 0);
                String dcIps     = intent.getStringExtra(EXTRA_DC_IPS);

                startProxy(port,
                        secret     != null ? secret     : "",
                        pool, recvBuf, sendBuf, cf,
                        cfDomain   != null ? cfDomain   : "",
                        fakeTls,
                        fakeDomain != null ? fakeDomain : "google.com",
                        verbose,
                        dcIps      != null ? dcIps      : "");
                break;
            }
            case ACTION_STOP:
                userStopped = true;
                // *** FIX: dispatch blocking stop to background thread ***
                new Thread(this::stopProxy, "proxy-stop").start();
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!WsProxyPolicy.isAvailable()) return;
        if (ProxyAutoDetector.isVpnActive(this)) return;
        SharedPreferences prefs = getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
        boolean manual = prefs.getBoolean("manual_enabled", false);
        boolean autoStart = prefs.getBoolean("auto_start", false);
        if ((manual || autoStart) && prefs.getBoolean("enabled", false) && !userStopped) {
            Log.i(TAG, "Task removed — scheduling restart");
            scheduleServiceRestart(2000);
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "Foreground service timed out (fgsType=" + fgsType + "), stopping foreground service cleanly");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
        } catch (Throwable t) {
            Log.e(TAG, "Error in onTimeout", t);
        }
    }

    private void tryAutoStart() {
        if (!WsProxyPolicy.isAvailable()) return;
        if (ProxyAutoDetector.isVpnActive(this)) return;
        SharedPreferences prefs = getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
        boolean manual = prefs.getBoolean("manual_enabled", false);
        boolean autoStart = prefs.getBoolean("auto_start", false);
        if (!manual && !autoStart) return;
        if (isRunning) return;
        if (manual) {
            String secret = prefs.getString("secret", "");
            if (secret.isEmpty()) return;
            new Thread(this::restartFromPrefs, "proxy-auto-start").start();
        } else {
            ProxyAutoDetector.checkAndAutoEnable(this);
        }
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    private void startProxy(int port, String secret, int poolSize, int recvBuf, int sendBuf,
            boolean cfEnabled, String cfDomain, boolean fakeTls, String fakeDomain,
            int verbose, String dcIps) {

        synchronized (proxyLock) {
            if (isRunning || sStopInProgress.get() || destroyed) return;

            ensureForegroundNotification(port);

            acquireWakeLock();
            isRunning = true;
            sStopInProgress.set(false);
            userStopped = false;
            currentPort = port;

            saveCurrentParams(port, secret, poolSize, recvBuf, sendBuf,
                    cfEnabled, cfDomain, fakeTls, fakeDomain, verbose, dcIps);

            executor = Executors.newSingleThreadExecutor();
            final String fSecret     = secret;
            final String fCfDomain   = cfDomain;
            final String fFakeDomain = fakeDomain;
            final String fDcIps      = dcIps;

            executor.execute(() -> {
                try {
                    // Force stop any lingering native proxy instance first to release sockets
                    try { NativeProxy.stopProxy(); } catch (Throwable ignored) {}

                    // Wait for the port to be fully closed if a previous instance is still shutting down
                    long portCloseDeadline = System.currentTimeMillis() + 1500;
                    while (System.currentTimeMillis() < portCloseDeadline && isPortOpen("127.0.0.1", port, 100)) {
                        Thread.sleep(100);
                    }

                    NativeProxy.setPoolSize(poolSize);
                    NativeProxy.setRecvBuffer(recvBuf);
                    NativeProxy.setSendBuffer(sendBuf);
                    NativeProxy.setCfProxyCacheDir(getCacheDir().getAbsolutePath());
                    NativeProxy.setCfProxyConfig(cfEnabled, true, fCfDomain);
                    NativeProxy.setFakeTls(fakeTls, fFakeDomain);

                    final AtomicBoolean nativeStartFailed = new AtomicBoolean(false);
                    Thread nativeProxyThread = new Thread(() -> {
                        int result = NativeProxy.startProxy("127.0.0.1", port, fDcIps, fSecret, verbose);
                        if (result != 0) {
                            Log.e(TAG, "Start error: " + result);
                            nativeStartFailed.set(true);
                        }
                    }, "native-proxy-runner");
                    nativeProxyThread.start();

                    // ── Startup watchdog: 7-секундный жёсткий лимит ──────
                    // Если порт не открылся или произошла ошибка запуска — убиваем нативный движок и перезапускаемся.
                    // Фикс бага с бесконечным зависанием при старте/применении настроек.
                    boolean portOpened = false;
                    long startupDeadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
                    while (System.currentTimeMillis() < startupDeadline && isRunning && !sStopInProgress.get() && !nativeStartFailed.get()) {
                        Thread.sleep(150);
                        if (isPortOpen("127.0.0.1", port, 200)) {
                            Log.i(TAG, "Proxy port open: " + port);
                            portOpened = true;
                            break;
                        }
                    }

                    sendStateBroadcast();

                    if (!portOpened) {
                        consecutiveStartupFails++;
                        Log.e(TAG, "Startup watchdog: порт не открылся или произошла ошибка запуска за "
                                + STARTUP_TIMEOUT_MS + "ms — принудительный перезапуск (попытка "
                                + consecutiveStartupFails + "/" + MAX_STARTUP_FAILS + ")");
                        try { NativeProxy.stopProxy(); } catch (Throwable ignored) {}
                        isRunning = false;
                        sStopInProgress.set(false);
                        releaseWakeLock();
                        sendStateBroadcast();
                        if (consecutiveStartupFails >= MAX_STARTUP_FAILS) {
                            Log.e(TAG, "Startup watchdog: достигнут лимит попыток запуска. Выключаем обход.");
                            consecutiveStartupFails = 0;
                            getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE)
                                    .edit().putBoolean("enabled", false).apply();
                            disableTelegramProxySettings();
                            stopProxy();
                        } else {
                            scheduleRestart(2000);
                        }
                        return;
                    }

                    consecutiveStartupFails = 0;
                    applyTelegramProxySettings();

                    // Start unified health & loss monitor
                    startUnifiedMonitor(port);
                    schedulePeriodicHeartbeat();

                    runningLatch = new CountDownLatch(1);
                    try {
                        runningLatch.await();
                    } catch (InterruptedException ignored) {}

                } catch (Exception e) {
                    Log.e(TAG, "Error", e);
                    stopProxy();
                }
            });
        }
    }

    /**
     * Must be called from a background thread — contains blocking operations.
     */
    private void stopProxy() {
        stopProxyInternal();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignore) {}
        stopSelf();
    }

    /**
     * Blocking stop — ALWAYS call from a background thread.
     */
    private void stopProxyInternal() {
        synchronized (proxyLock) {
            if (!isRunning && !sStopInProgress.get()) return;
            sStopInProgress.set(true);
            isRunning = false;
            currentPort = 0;
            if (!getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE).getBoolean("manual_enabled", false)) {
                getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            }
            sendStateBroadcast(); // immediate UI update

            if (runningLatch != null) {
                runningLatch.countDown();
            }
            stopUnifiedMonitor();

            final CountDownLatch latch = new CountDownLatch(1);

            if (executor != null && !executor.isShutdown()) {
                try {
                    executor.execute(() -> {
                        try { NativeProxy.stopProxy(); }
                        catch (Exception e) { Log.w(TAG, "Native stop error", e); }
                        finally { latch.countDown(); }
                    });
                } catch (RejectedExecutionException e) {
                    latch.countDown();
                }
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) { executor.shutdownNow(); }
                executor = null;
            } else {
                latch.countDown();
            }

            try { latch.await(4, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            releaseWakeLock();
            sStopInProgress.set(false);
        }
    }

    private void restartFromPrefs() {
        if (!WsProxyPolicy.isAvailable()) return;
        SharedPreferences prefs = getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("enabled", false)) return;
        String secret = prefs.getString("secret", "");
        if (secret.isEmpty()) return;

        int port       = prefs.getInt("port", 1443);
        int pool       = prefs.getInt("pool_size", 4);
        int buf        = prefs.getInt("buffer", 256);
        boolean cf     = prefs.getBoolean("cf_enabled", true);
        String cfDomain  = prefs.getString("cf_domain", "");
        boolean fakeTls  = prefs.getBoolean("fake_tls", false);
        String fakeDomain = prefs.getString("fake_domain", "google.com");
        int verbose    = prefs.getInt("verbose", 0);
        String dcIps   = prefs.getString("dc_ips", "");

        if (isRunning || sStopInProgress.get()) {
            stopProxyInternal();
        }

        startProxy(port, secret, pool, buf, buf, cf, cfDomain,
                fakeTls, fakeDomain, verbose, dcIps);
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void saveCurrentParams(int port, String secret, int pool,
            int recvBuf, int sendBuf, boolean cf, String cfDomain,
            boolean fakeTls, String fakeDomain, int verbose, String dcIps) {
        getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("enabled", true)   // ensures BootReceiver / watchdog can restart
                .putInt("port", port)
                .putString("secret", secret)
                .putInt("pool_size", pool)
                .putInt("buffer", recvBuf)
                .putBoolean("cf_enabled", cf)
                .putString("cf_domain", cfDomain != null ? cfDomain : "")
                .putBoolean("fake_tls", fakeTls)
                .putString("fake_domain", fakeDomain != null ? fakeDomain : "google.com")
                .putInt("verbose", verbose)
                .putString("dc_ips", dcIps != null ? dcIps : "")
                .apply();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    public static boolean isPortOpen(String host, int port, int timeout) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Unified Health & Loss Monitor
    // -------------------------------------------------------------------------

    private void startUnifiedMonitor(int port) {
        stopUnifiedMonitor();
        monitorThread = new Thread(() -> {
            int failCount = 0;
            while (!Thread.currentThread().isInterrupted() && isRunning && !destroyed) {
                try {
                    Thread.sleep(MONITOR_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!isRunning || destroyed) break;

                boolean alive = isPortOpen("127.0.0.1", port, 1000);
                if (alive) {
                    failCount = 0;
                } else {
                    failCount++;
                    Log.w(TAG, "Proxy monitor: probe failed " + failCount + "/2");
                    if (failCount >= 2) {
                        Log.e(TAG, "Proxy monitor: native proxy unresponsive — resetting cooldowns and restarting");
                        failCount = 0;
                        new Thread(() -> {
                            NativeProxy.resetCooldowns();
                            stopProxyInternal();
                            if (!destroyed && !userStopped) {
                                restartFromPrefs();
                            }
                        }, "proxy-monitor-restart").start();
                        break;
                    }
                }
            }
        }, "proxy-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void stopUnifiedMonitor() {
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }

    public static int getPingMs(int port) {
        if (!isRunning || port <= 0) return -1;
        long start = System.currentTimeMillis();
        if (isPortOpen("127.0.0.1", port, 500)) {
            return (int)(System.currentTimeMillis() - start);
        }
        return -1;
    }

    private void sendStateBroadcast() {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra("running", isRunning);
        intent.putExtra("port", currentPort);
        sendBroadcast(intent);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGWS:Proxy");
                wakeLock.acquire(15_000L);
            }
        } catch (Exception e) { Log.w(TAG, "WakeLock", e); }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        wakeLock = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        destroyed = true;
        stopUnifiedMonitor();
        if (WsProxyPolicy.isAvailable() && !userStopped && !ProxyAutoDetector.isVpnActive(this)) {
            SharedPreferences prefs = getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean("enabled", false);
            boolean manual = prefs.getBoolean("manual_enabled", false);
            boolean autoStart = prefs.getBoolean("auto_start", false);
            if (enabled && (manual || autoStart)) {
                scheduleServiceRestart(3000);
            }
        }
        super.onDestroy();
    }
}
