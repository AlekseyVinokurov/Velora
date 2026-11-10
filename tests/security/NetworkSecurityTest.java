import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.KeyStore;
import javax.net.ssl.*;
import soundgram.music.MusicNetworkHelper;
import soundgram.wsproxy.BootReceiver;
import soundgram.wsproxy.WsProxyPolicy;

/** Executes the production connection helper against local TLS servers; no Internet or real keys. */
public class NetworkSecurityTest {
    private static int assertions;
    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private static KeyStore store(String path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(path)) { store.load(input, password); }
        return store;
    }
    private static HttpsServer server(KeyStore store, char[] password) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            byte[] body = "verified TLS".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        return server;
    }
    private static void request(HttpsServer server, boolean direct, boolean accept) throws Exception {
        URL url = new URL("https://localhost:" + server.getAddress().getPort() + "/");
        var connection = direct ? MusicNetworkHelper.openConnectionDirect(url) : MusicNetworkHelper.openConnection(url);
        try {
            try (var input = connection.getInputStream()) {
                check(accept && input.readAllBytes().length > 0, "expected valid server only");
            }
        } catch (SSLException rejected) {
            check(!accept, "trusted hostname must succeed");
        } finally { connection.disconnect(); }
    }
    public static void main(String[] args) throws Exception {
        char[] password = System.getenv("VELORA_SECURITY_TEST_PASSWORD").toCharArray();
        KeyStore good = store(args[0], password);
        KeyStore wrongHost = store(args[1], password);
        KeyStore untrusted = store(args[2], password);
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("localhost", good.getCertificate("fixture"));
        trust.setCertificateEntry("wrong-host", wrongHost.getCertificate("fixture"));
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, managers.getTrustManagers(), null);
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        HttpsServer[] servers = {server(good, password), server(wrongHost, password), server(untrusted, password)};
        try {
            for (boolean direct : new boolean[]{false, true}) {
                request(servers[0], direct, true);
                request(servers[1], direct, false);
                request(servers[2], direct, false);
            }
        } finally { for (HttpsServer server : servers) server.stop(0); }
        android.content.Context context = new android.content.Context();
        var ws = context.getSharedPreferences("tgwsproxy", 0);
        var main = context.getSharedPreferences("mainconfig", 0);
        ws.edit().putBoolean("auto_start", true).putBoolean("enabled", true).putBoolean("manual_enabled", true).apply();
        main.edit().putBoolean("proxy_enabled", true).putBoolean("vpn_test_marker", true).apply();
        WsProxyPolicy.disableLegacyPreferences(context);
        check(!ws.getBoolean("auto_start", true) && !ws.getBoolean("enabled", true)
                && !ws.getBoolean("manual_enabled", true), "all legacy WS opt-ins reset");
        check(main.getBoolean("proxy_enabled", false) && main.getBoolean("vpn_test_marker", false), "other preferences unchanged");
        ws.edit().putBoolean("auto_start", true).putBoolean("enabled", true).apply();
        new BootReceiver().onReceive(context, new android.content.Intent().setAction(android.content.Intent.ACTION_BOOT_COMPLETED));
        check(context.starts == 0, "boot must not start a service even with restored flags");
        check(WsProxyPolicy.isAvailable(), "manual feature available");
        WsProxyPolicy.disableLegacyPreferences(context);
        MusicNetworkHelper.ensureBypassServiceRunning();
        check(context.starts == 0 && WsProxyPolicy.session() == 0, "music and cold start cannot authorize proxy");
        ws.edit().putString("secret", "saved-secret").apply();
        WsProxyPolicy.startManually(context);
        check(context.starts == 1 && WsProxyPolicy.session() != 0 && ws.getBoolean("enabled", false), "explicit manual start authorized");
        check(!ws.getBoolean("auto_start", true) && ws.getString("secret", "").equals("saved-secret"), "manual start retains parameters, forbids auto start");
        WsProxyPolicy.stopManually(context);
        check(WsProxyPolicy.session() == 0 && !ws.getBoolean("enabled", true) && context.stops == 1, "manual stop revokes pending starts and stops service");
        check(main.getBoolean("proxy_enabled", false), "manual session never edits saved Telegram proxy");
        soundgram.wsproxy.ProxyAutoDetector.vpn = true;
        WsProxyPolicy.startManually(context);
        check(context.starts == 1 && WsProxyPolicy.session() == 0, "active VPN is preserved");
        System.out.println("PASS: " + assertions + " TLS/legacy WS policy assertions on production classes (host stubs, no device).");
    }
}
