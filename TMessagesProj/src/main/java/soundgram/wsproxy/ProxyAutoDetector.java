package soundgram.wsproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;
import java.net.Socket;
import java.net.InetSocketAddress;

/** Compatibility entry points. Detection and automatic startup are intentionally disabled. */
public final class ProxyAutoDetector {
    private static final String TAG = "VeloraBypass";
    public static void init(Context context) {}
    public static void checkAndAutoEnable(Context context) {}
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

    public static boolean tcpPing(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
