package soundgram.wsproxy;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** User intent lives only in this process; persisted flags never authorize a start. */
public final class WsProxyPolicy {
    private WsProxyPolicy() {}
    private static volatile long session;
    public static boolean isAvailable() { return true; }
    public static long session() { return session; }

    public static void disableLegacyPreferences(Context context) {
        if (context == null) return;
        session = 0;
        context.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", false).putBoolean("auto_start", false)
                .putBoolean("manual_enabled", false).putBoolean("vpn_sleep", false).apply();
    }

    public static void startManually(Context context) {
        if (context == null) return;
        if (TgWsProxyService.isStopInProgress()) return;
        SharedPreferences prefs = context.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
        if (ProxyAutoDetector.isVpnActive(context)) {
            prefs.edit().putString("last_error", "VPN активен. Отключите VPN перед включением Velora Bypass.").apply();
            return;
        }
        long token = System.nanoTime();
        session = token;
        prefs.edit().putBoolean("enabled", true).putBoolean("manual_enabled", true)
                .putBoolean("auto_start", false).putString("last_error", "").apply();
        Intent intent = new Intent(context, TgWsProxyService.class).setAction(TgWsProxyService.ACTION_START)
                .putExtra("manual_session", token);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException e) {
            session = 0;
            prefs.edit().putBoolean("enabled", false).putBoolean("manual_enabled", false)
                    .putString("last_error", "Android не разрешил запуск сервиса. Откройте Velora и повторите.").apply();
        }
    }

    public static void stopManually(Context context) {
        session = 0;
        if (context == null) return;
        context.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", false).putBoolean("manual_enabled", false)
                .putBoolean("auto_start", false).apply();
        // stopService never creates a service when it is already off.
        context.stopService(new Intent(context, TgWsProxyService.class));
    }
}
