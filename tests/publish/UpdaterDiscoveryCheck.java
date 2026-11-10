import app.nimarkogram.messenger.updater.GitHubReleaseClient;
import app.nimarkogram.messenger.updater.VeloraRelease;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Exercises unchanged production updater code; never fetches APK bytes. */
public final class UpdaterDiscoveryCheck {
    public static void main(String[] args) throws Exception {
        String pin = args[0];
        String expectedJson = Files.readString(Path.of(args[1]));
        VeloraRelease expected = VeloraRelease.parse(expectedJson, pin);
        long previousCode = Long.parseLong(args[2]);
        boolean offline = args.length == 5 && "--offline".equals(args[4]);
        if (offline) {
            String releaseJson = Files.readString(Path.of(args[3]));
            // Intercept only the two production discovery URLs. A request for
            // any APK or unexpected host fails, rather than accessing the net.
            URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) {
                    String body;
                    if (url.toString().equals(VeloraRelease.API_URL)) body = releaseJson;
                    else if (url.toString().equals(expected.downloadUrl().replace(expected.apkName, "velora-release.json"))) body = expectedJson;
                    else throw new AssertionError("Unexpected discovery URL; APK requests forbidden");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    return new HttpURLConnection(url) {
                        @Override public int getResponseCode() { return 200; }
                        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                        @Override public void connect() {}
                        @Override public void disconnect() {}
                        @Override public boolean usingProxy() { return false; }
                    };
                }
            } : null);
        }
        VeloraRelease actual;
        if (args.length == 4) {
            actual = VeloraRelease.parse(expectedJson, pin);
            actual.validateRelease(new JSONObject(Files.readString(Path.of(args[3]))));
        } else {
            GitHubReleaseClient.Result result = GitHubReleaseClient.latest(pin);
            if (result == null) throw new AssertionError("Updater found no stable release");
            actual = result.metadata;
        }
        if (!new JSONObject(expected.toJson()).similar(new JSONObject(actual.toJson()))
                || !actual.isNewerThan(previousCode, actual.minSdk)
                || actual.isNewerThan(actual.versionCode, actual.minSdk)
                || !actual.acceptsApk(VeloraRelease.PACKAGE_NAME, actual.versionCode,
                                     actual.versionName, actual.minSdk, previousCode, actual.minSdk)) {
            throw new AssertionError("Production updater rejected the verified CI successor");
        }
        System.out.println("PASS: production updater accepts " + actual.tag + " after code " + previousCode
                           + ", preserves CI metadata, and rejects equal-code offers.");
    }
}
