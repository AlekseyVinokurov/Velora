/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.ui.LaunchActivity;

public class NotificationsService extends Service implements NotificationCenter.NotificationCenterDelegate {

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appIconChanged);

        if (allowResidentNotification()) {
            try {
                NotificationChannelCompat channel = new NotificationChannelCompat.Builder("nimarkoPush", NotificationManagerCompat.IMPORTANCE_DEFAULT)
                        .setName("Velora")
                        .setLightsEnabled(false)
                        .setVibrationEnabled(false)
                        .setSound(null, null)
                        .build();
                NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
                notificationManager.createNotificationChannel(channel);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (allowResidentNotification()) {
            try {
                startInForeground();
            } catch (Exception e) {
                FileLog.e("Failed to start foreground", e);
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.appIconChanged);
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", false)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appIconChanged) {
            if (allowResidentNotification()) {
                try {
                    startInForeground();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
    }

    private void startInForeground() {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.setComponent(org.telegram.ui.LauncherIconController.getActiveLauncherComponent(this));
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder bldr = new NotificationCompat.Builder(this, "nimarkoPush")
                .setSmallIcon(R.drawable.notification)
                .setLargeIcon(org.telegram.ui.LauncherIconController.getActiveIconBitmap(this))
                .setShowWhen(false)
                .setOngoing(true)
                .setContentText("Velora")
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(pendingIntent);
        org.telegram.ui.LauncherIconController.applyNotificationAppInfo(bldr, this);
        startForeground(1390, bldr.build());
    }

    private boolean allowResidentNotification() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && Build.VERSION.SDK_INT < 35
                && app.nimarkogram.messenger.NimarkoConfig.residentNotification;
    }
}
