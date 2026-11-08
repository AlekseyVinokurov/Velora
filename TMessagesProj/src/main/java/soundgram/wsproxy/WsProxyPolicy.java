package soundgram.wsproxy;

import android.content.Context;
import android.content.SharedPreferences;

/** Release gate: keep the native implementation for a later, explicit opt-in release. */
public final class WsProxyPolicy {
    private WsProxyPolicy() {}

    public static boolean isAvailable() { return false; }

    /** Clear legacy opt-ins only; never alter Telegram proxy settings or the user's VPN. */
    public static void disableLegacyPreferences(Context context) {
        if (context == null || isAvailable()) return;
        SharedPreferences prefs = context.getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE);
        if (prefs.getBoolean("enabled", false) || prefs.getBoolean("auto_start", true)
                || prefs.getBoolean("manual_enabled", false)) {
            prefs.edit().putBoolean("enabled", false).putBoolean("auto_start", false)
                    .putBoolean("manual_enabled", false).apply();
        }
    }
}
