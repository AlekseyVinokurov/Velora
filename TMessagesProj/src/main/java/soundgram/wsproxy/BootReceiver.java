package soundgram.wsproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!WsProxyPolicy.isAvailable()) {
            WsProxyPolicy.disableLegacyPreferences(context);
            return;
        }
        if (intent == null || intent.getAction() == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
        // Requirement 2: If auto_start is off, bypass must NEVER start on boot!
        if (!prefs.getBoolean("auto_start", false)) return;
        if (ProxyAutoDetector.isVpnActive(context)) return;

        // Auto-start is on: only start if Telegram servers are actually blocked
        new Thread(() -> {
            ProxyAutoDetector.checkAndAutoEnable(context);
        }, "proxy-boot-auto-detect").start();
    }
}
