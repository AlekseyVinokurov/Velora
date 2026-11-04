package soundgram.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public class MusicImportService extends Service {

    private static final String CHANNEL_ID = "soundgram_music_import";
    private static final int NOTIFICATION_ID = 98234;

    public static final String ACTION_START = "soundgram.music.ACTION_START_IMPORT";
    public static final String ACTION_CANCEL = "soundgram.music.ACTION_CANCEL_IMPORT";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_TOTAL = "extra_total";

    private PowerManager.WakeLock wakeLock;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManager notificationManager;
    private String currentTitle = "";
    private int currentTotal = 0;
    private static volatile boolean isServiceRunning = false;
    private static volatile MusicImportService instance;

    public static boolean isRunning() {
        return isServiceRunning;
    }

    public static void start(Context context, String title, int total) {
        try {
            Intent intent = new Intent(context, MusicImportService.class);
            intent.setAction(ACTION_START);
            intent.putExtra(EXTRA_TITLE, title);
            intent.putExtra(EXTRA_TOTAL, total);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            FileLog.e("MusicImportService start error: " + e);
        }
    }

    public static void stop() {
        try {
            Context context = ApplicationLoader.applicationContext;
            Intent intent = new Intent(context, MusicImportService.class);
            context.stopService(intent);
        } catch (Exception e) {
            FileLog.e("MusicImportService stop error: " + e);
        }
    }

    public static void updateProgress(int current, int total, String trackName) {
        if (!isServiceRunning) return;
        try {
            MusicImportService currentInstance = instance;
            if (currentInstance != null) {
                currentInstance.onProgressUpdate(current, total, trackName);
            }
        } catch (Exception e) {
            FileLog.e("MusicImportService progress error: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        isServiceRunning = true;
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        acquireWakeLock();

        // Android 8+ / Android 14+ requires immediate startForeground in onCreate()
        try {
            Notification notification = buildNotification(0, 0, "Подготовка к импорту...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int serviceType = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                }
                startForeground(NOTIFICATION_ID, notification, serviceType);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            FileLog.e("MusicImportService onCreate startForeground: " + e);
            try {
                startForeground(NOTIFICATION_ID, buildNotification(0, 0, "Подготовка к импорту..."));
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_CANCEL.equals(action)) {
                MusicImporter.cancelImport();
                try {
                    stopForeground(true);
                } catch (Throwable ignored) {}
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_START.equals(action)) {
                currentTitle = intent.getStringExtra(EXTRA_TITLE);
                if (currentTitle == null) currentTitle = "";
                currentTotal = intent.getIntExtra(EXTRA_TOTAL, 0);
            }
        }

        Notification notification = buildNotification(0, currentTotal, "Подготовка к импорту...");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int serviceType = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                }
                startForeground(NOTIFICATION_ID, notification, serviceType);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            FileLog.e("MusicImportService startForeground: " + e);
        }
        return START_NOT_STICKY;
    }

    private void onProgressUpdate(int current, int total, String trackName) {
        if (notificationManager == null || notificationBuilder == null) return;
        try {
            String title = currentTitle.isEmpty() ? "Импорт музыки" : ("Импорт: " + currentTitle);
            String text = (total > 0 ? (current + " из " + total + ": ") : "") + (trackName != null ? trackName : "");
            notificationBuilder.setContentTitle(title)
                    .setContentText(text)
                    .setProgress(total > 0 ? total : 100, current, total <= 0);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        } catch (Exception e) {
            FileLog.e("MusicImportService notify error: " + e);
        }
    }

    private Notification buildNotification(int current, int total, String trackName) {
        Intent cancelIntent = new Intent(this, MusicImportService.class);
        cancelIntent.setAction(ACTION_CANCEL);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pCancel = PendingIntent.getService(this, 1, cancelIntent, pendingFlags);

        String title = currentTitle.isEmpty() ? "Импорт музыки" : ("Импорт: " + currentTitle);
        String text = (total > 0 ? (current + " из " + total + ": ") : "") + (trackName != null ? trackName : "");

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(total > 0 ? total : 100, current, total <= 0)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", pCancel);

        return notificationBuilder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Импорт музыки",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Ход импорта треков в Velora");
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soundgram:music_import");
                    wakeLock.acquire(3 * 3600 * 1000L); // 3 hours safety max
                }
            } catch (Exception e) {
                FileLog.e("MusicImportService acquireWakeLock: " + e);
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Exception e) {
                FileLog.e("MusicImportService releaseWakeLock: " + e);
            }
            wakeLock = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        instance = null;
        releaseWakeLock();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        if (notificationManager != null) {
            try {
                notificationManager.cancel(NOTIFICATION_ID);
            } catch (Throwable ignored) {}
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
