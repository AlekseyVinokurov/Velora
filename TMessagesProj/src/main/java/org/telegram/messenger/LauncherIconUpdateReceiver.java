package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.ui.LauncherIconController;

/** Reconcile manifest defaults with an existing user choice immediately after update. */
public class LauncherIconUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            LauncherIconController.tryFixLauncherIconIfNeeded();
        }
    }
}
