package soundgram.music;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;


public final class MusicNetworkHelper {

    private static final String TAG = "MusicNetworkHelper";
    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static volatile boolean sInitialized = false;

    static {
        init();
    }

    public static synchronized void init() {
        if (sInitialized) return;
        sInitialized = true;

        try {
            // Setup global authenticator for proxy credentials if configured
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    try {
                        if (SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null) {
                            SharedConfig.ProxyInfo pi = SharedConfig.currentProxy;
                            if (!TextUtils.isEmpty(pi.username) && !TextUtils.isEmpty(pi.address)) {
                                String host = getRequestingHost();
                                if (host == null || host.equalsIgnoreCase(pi.address) || getRequestorType() == RequestorType.PROXY) {
                                    return new PasswordAuthentication(pi.username,
                                            (pi.password != null ? pi.password : "").toCharArray());
                                }
                            }
                        }
                    } catch (Exception e) {
                        FileLog.e(TAG + " proxy auth error: " + e);
                    }
                    return super.getPasswordAuthentication();
                }
            });
        } catch (Exception e) {
            FileLog.e(TAG + " init authenticator failed: " + e);
        }

        installProxySelector();
    }

    /**
     * Installs a custom ProxySelector so that ExoPlayer and all default Java HttpURLConnection
     * calls respect Telegram's proxy when enabled, or use direct connection when disabled.
     */
    public static void installProxySelector() {
        try {
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    if (uri != null) {
                        String host = uri.getHost();
                        if (host == null || "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) {
                            return Collections.singletonList(Proxy.NO_PROXY);
                        }
                        // Web requests, mirrors and media CDNs should use direct connection unless user configured a socks5 proxy
                        if (isBypassOrMirrorHost(host)) {
                            if (SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null) {
                                SharedConfig.ProxyInfo pi = SharedConfig.currentProxy;
                                if (!TextUtils.isEmpty(pi.address) && pi.port > 0 && TextUtils.isEmpty(pi.secret)) {
                                    return Collections.singletonList(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(pi.address, pi.port)));
                                }
                            }
                            return Collections.singletonList(Proxy.NO_PROXY);
                        }
                    }
                    Proxy active = getActiveProxy();
                    return Collections.singletonList(active);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    FileLog.w(TAG + " connect failed to " + uri + " via " + sa + ": " + ioe);
                }
            });
        } catch (Exception e) {
            FileLog.e(TAG + " installProxySelector failed: " + e);
        }
    }

    public static void ensureBypassServiceRunning() {
        if (!soundgram.wsproxy.WsProxyPolicy.isAvailable()) return;
        try {
            android.content.Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
            if (ctx == null) return;
            android.content.SharedPreferences wsPrefs = ctx.getSharedPreferences("tgwsproxy", android.content.Context.MODE_PRIVATE);
            if (wsPrefs.getBoolean("enabled", false) || soundgram.wsproxy.TgWsProxyService.isRunning) {
                if (!soundgram.wsproxy.TgWsProxyService.isRunning) {
                    String secret = wsPrefs.getString("secret", "");
                    if (secret.isEmpty()) {
                        byte[] b = new byte[16];
                        new java.security.SecureRandom().nextBytes(b);
                        StringBuilder sb = new StringBuilder();
                        for (byte v : b) sb.append(String.format("%02x", v));
                        secret = sb.toString();
                        wsPrefs.edit().putString("secret", secret).apply();
                    }
                    android.content.Intent intent = new android.content.Intent(ctx, soundgram.wsproxy.TgWsProxyService.class);
                    intent.setAction(soundgram.wsproxy.TgWsProxyService.ACTION_START);
                    intent.putExtra(soundgram.wsproxy.TgWsProxyService.EXTRA_PORT, wsPrefs.getInt("port", 1443));
                    intent.putExtra(soundgram.wsproxy.TgWsProxyService.EXTRA_SECRET, secret);
                    intent.putExtra(soundgram.wsproxy.TgWsProxyService.EXTRA_POOL_SIZE, wsPrefs.getInt("pool_size", 4));
                    intent.putExtra(soundgram.wsproxy.TgWsProxyService.EXTRA_RECV_BUF, wsPrefs.getInt("buffer", 256));
                    intent.putExtra(soundgram.wsproxy.TgWsProxyService.EXTRA_SEND_BUF, wsPrefs.getInt("buffer", 256));
                    intent.putExtra(soundgram.wsproxy.TgWsProxyService.EXTRA_CF_ENABLED, wsPrefs.getBoolean("cf_enabled", true));
                    intent.putExtra(soundgram.wsproxy.TgWsProxyService.EXTRA_CF_DOMAIN, wsPrefs.getString("cf_domain", ""));
                    intent.putExtra(soundgram.wsproxy.TgWsProxyService.EXTRA_VERBOSE, wsPrefs.getInt("verbose", 0));
                    intent.putExtra(soundgram.wsproxy.TgWsProxyService.EXTRA_DC_IPS, wsPrefs.getString("dc_ips", ""));
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        ctx.startForegroundService(intent);
                    } else {
                        ctx.startService(intent);
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e(TAG + " ensureBypassServiceRunning error: " + t);
        }
    }

    /**
     * Returns the active Proxy configured in Telegram or Proxy.NO_PROXY if disabled.
     */
    public static Proxy getActiveProxy() {
        try {
            if (soundgram.wsproxy.TgWsProxyService.isRunning && soundgram.wsproxy.TgWsProxyService.currentPort > 0) {
                return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", soundgram.wsproxy.TgWsProxyService.currentPort));
            }
            if (SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null) {
                SharedConfig.ProxyInfo pi = SharedConfig.currentProxy;
                if (!TextUtils.isEmpty(pi.address) && pi.port > 0) {
                    if ("127.0.0.1".equals(pi.address) || "localhost".equalsIgnoreCase(pi.address)) {
                        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", pi.port));
                    }
                    if (TextUtils.isEmpty(pi.secret)) {
                        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(pi.address, pi.port));
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(TAG + " getActiveProxy error: " + e);
        }
        return Proxy.NO_PROXY;
    }

    public static boolean isBypassOrMirrorHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.contains("jina.ai")
                || h.contains("allorigins")
                || h.contains("codetabs")
                || h.contains("corsproxy")
                || h.contains("nadeko.net")
                || h.contains("nerdvpn.de")
                || h.contains("yewtu.be")
                || h.contains("pipedapi")
                || h.contains("piped")
                || h.contains("spotifydown")
                || h.contains("hitmo")
                || h.contains("deezer")
                || h.contains("cdn")
                || h.contains("cloudflare")
                || h.contains("github")
                || h.contains("raw.githubusercontent.com");
    }

    /**
     * Checks if proxy is currently enabled and active.
     */
    public static boolean isProxyActive() {
        return getActiveProxy() != Proxy.NO_PROXY;
    }

    /**
     * Opens a HttpURLConnection strictly using the active proxy setting (or direct if disabled).
     */
    public static HttpURLConnection openConnection(URL url) throws IOException {
        Proxy proxy = Proxy.NO_PROXY;
        if (url != null && !isBypassOrMirrorHost(url.getHost())) {
            proxy = getActiveProxy();
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);

        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        return conn;
    }

    /**
     * Opens a HttpURLConnection strictly bypassing any proxy (direct connection).
     */
    public static HttpURLConnection openConnectionDirect(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);

        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        return conn;
    }

    /**
     * Opens a HttpURLConnection strictly bypassing any proxy for a given URL string.
     */
    public static HttpURLConnection openConnectionDirect(String urlStr) throws IOException {
        return openConnectionDirect(new URL(urlStr));
    }

    /**
     * Opens a HttpURLConnection for a given URL string.
     */
    public static HttpURLConnection openConnection(String urlStr) throws IOException {
        return openConnection(new URL(urlStr));
    }
}
