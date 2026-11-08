package app.nimarkogram.messenger.updater;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;

/** Host tests of production metadata and signature policy; not an Android/device test. */
public final class UpdaterPolicyTest {
    private static int assertions;
    private interface Action { void run() throws Exception; }
    private static void check(boolean condition, String name) {
        assertions++;
        if (!condition) throw new AssertionError(name);
    }
    private static void reject(Action action, String name) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (Exception expected) { rejected = true; }
        check(rejected, name);
    }
    private static boolean signatureAccepted(File file, String pin) {
        try { return VeloraApkSignature.verify(file, pin, 24, 36); }
        catch (Exception rejected) { return false; }
    }
    private static JSONObject metadata(String pin, String digest, long size) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("repository", VeloraRelease.REPOSITORY)
                .put("channel", "stable").put("applicationId", VeloraRelease.PACKAGE_NAME)
                .put("versionName", "0.1.1").put("versionCode", 100001).put("tag", "v0.1.1")
                .put("sourceCommit", "0123456789012345678901234567890123456789").put("minSdk", 24)
                .put("apk", new JSONObject().put("name", "Velora-0.1.1-100001.apk").put("size", size)
                        .put("sha256", digest).put("certificateSha256", pin));
    }
    public static void main(String[] args) throws Exception {
        File signed = new File(args[0]);
        File otherSigner = new File(args[1]);
        File tampered = new File(args[2]);
        File unsigned = new File(args[3]);
        String pin = args[4]; // Real, random, temporary fixture certificate; never the product pin.
        String digest = VeloraApkSignature.fingerprint(Files.readAllBytes(signed.toPath()));
        long size = signed.length();
        String json = metadata(pin, digest, size).toString();
        VeloraRelease release = VeloraRelease.parse(json, pin);
        check(release.isNewerThan(100000, 36), "new update");
        check(!release.isNewerThan(100001, 36), "same version rejected");
        check(!release.isNewerThan(100002, 36), "downgrade rejected");
        check(!release.isNewerThan(100000, 23), "unsupported SDK rejected");
        check(release.acceptsApk(VeloraRelease.PACKAGE_NAME, 100001, "0.1.1", 24, 100000, 36), "matching APK identity");
        check(!release.acceptsApk(VeloraRelease.PACKAGE_NAME + ".dev", 100001, "0.1.1", 24, 100000, 36), "DEV APK rejected");
        check(!release.acceptsApk("org.telegram.messenger", 100001, "0.1.1", 24, 100000, 36), "wrong package rejected");
        check(!release.acceptsApk(VeloraRelease.PACKAGE_NAME, 100002, "0.1.1", 24, 100000, 36), "wrong APK code rejected");
        check(!release.acceptsApk(VeloraRelease.PACKAGE_NAME, 100001, "0.1.0", 24, 100000, 36), "wrong APK name rejected");
        check(!release.acceptsApk(VeloraRelease.PACKAGE_NAME, 100001, "0.1.1", 25, 100000, 36), "wrong APK minSdk rejected");
        check(!release.acceptsApk(VeloraRelease.PACKAGE_NAME, 100001, "0.1.1", 24, 100001, 36), "already installed APK rejected");
        reject(() -> VeloraRelease.parse(json, ""), "missing trust root");
        reject(() -> VeloraRelease.parse(json, "f".repeat(64)), "unknown metadata signer");
        reject(() -> VeloraRelease.parse(json + "{}", pin), "trailing object");
        reject(() -> VeloraRelease.parse(json.replace("\"versionCode\":100001", "\"versionCode\":1.5"), pin), "fractional versionCode");
        reject(() -> VeloraRelease.parse(json.replace("\"versionCode\":100001", "\"versionCode\":1e5"), pin), "exponent versionCode");
        reject(() -> VeloraRelease.parse(json.replace("\"versionCode\":100001", "\"versionCode\":100001,\"versionCode\":100002"), pin), "duplicate field");
        reject(() -> VeloraRelease.parse(json.replace("\"versionCode\":100001", "\"versionCode\":0"), pin), "zero code");
        reject(() -> VeloraRelease.parse(json.replace("\"versionCode\":100001", "\"versionCode\":9999999999999999999999"), pin), "integer overflow");
        reject(() -> VeloraRelease.parse("/*comment*/" + json, pin), "lenient JSON rejected");
        reject(() -> VeloraRelease.parse(metadata(pin, digest, size).put("unexpected", true).toString(), pin), "unknown field");
        reject(() -> VeloraRelease.parse(metadata(pin, digest, size).put("tag", "v0.1.0").toString(), pin), "wrong tag");
        reject(() -> VeloraRelease.parse(metadata(pin, digest, size).put("channel", "beta").toString(), pin), "wrong channel");
        reject(() -> VeloraRelease.parse(metadata(pin, digest, size).put("applicationId", "org.telegram.messenger").toString(), pin), "wrong metadata package");
        reject(() -> VeloraRelease.parse(metadata(pin, digest, size).put("repository", "other/repo").toString(), pin), "wrong repository");
        reject(() -> VeloraRelease.parse(metadata(pin, digest, size).put("versionName", "../escape").toString(), pin), "path traversal");
        reject(() -> VeloraRelease.parse(metadata(pin, digest, VeloraRelease.MAX_APK_BYTES + 1).toString(), pin), "oversized APK");
        JSONObject shortHash = metadata(pin, digest, size);
        shortHash.getJSONObject("apk").put("sha256", "abcd");
        reject(() -> VeloraRelease.parse(shortHash.toString(), pin), "short digest");
        check(VeloraApkSignature.verifyDigest(signed, size, digest), "download hash accepted");
        check(!VeloraApkSignature.verifyDigest(signed, size + 1, digest), "wrong size rejected");
        check(!VeloraApkSignature.verifyDigest(signed, size, "f".repeat(64)), "wrong digest rejected");
        check(!VeloraApkSignature.verifyDigest(tampered, size, digest), "tampered download rejected");
        check(signatureAccepted(signed, pin), "cryptographic signature accepted");
        check(!signatureAccepted(otherSigner, pin), "valid APK from another key rejected");
        check(!signatureAccepted(tampered, pin), "APK with altered bytes rejected");
        check(!signatureAccepted(unsigned, pin), "unsigned APK rejected");
        JSONObject asset = new JSONObject().put("name", release.apkName).put("state", "uploaded").put("size", size)
                .put("browser_download_url", release.downloadUrl()).put("digest", "sha256:" + digest);
        JSONObject github = new JSONObject().put("tag_name", release.tag).put("draft", false).put("prerelease", false)
                .put("assets", new JSONArray().put(asset));
        release.validateRelease(github);
        assertions++;
        reject(() -> release.validateRelease(new JSONObject(github.toString()).put("draft", true)), "draft ignored");
        reject(() -> release.validateRelease(new JSONObject(github.toString()).put("prerelease", true)), "prerelease ignored");
        JSONObject duplicate = new JSONObject(github.toString());
        duplicate.getJSONArray("assets").put(asset);
        reject(() -> release.validateRelease(duplicate), "ambiguous APK assets");
        JSONObject foreign = new JSONObject(github.toString());
        foreign.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.org/update.apk");
        reject(() -> release.validateRelease(foreign), "foreign initial download");
        JSONObject wrongDigest = new JSONObject(github.toString());
        wrongDigest.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:" + "f".repeat(64));
        reject(() -> release.validateRelease(wrongDigest), "GitHub digest differs");
        VeloraRelease.validateDownloadUrl(release.downloadUrl()); assertions++;
        VeloraRelease.validateDownloadUrl("https://release-assets.githubusercontent.com/release.apk?token=public"); assertions++;
        for (String url : new String[]{"http://github.com/update.apk", "https://github.com.evil.example/update.apk",
                "https://user@github.com/update.apk", "https://github.com:8443/update.apk", "file:///tmp/update.apk",
                "https://example.org/update.apk", "https://github.com/update.apk#fragment"}) {
            reject(() -> VeloraRelease.validateDownloadUrl(url), "untrusted redirect rejected");
        }
        StubConnection absent = new StubConnection(404, "");
        check(GitHubReleaseClient.latest(pin, absent) == null && absent.closed, "no published release");
        StubConnection limited = new StubConnection(403, "rate limited");
        reject(() -> GitHubReleaseClient.latest(pin, limited), "rate limit is an error, not no-update");
        check(limited.closed, "rate-limited connection closed");
        StubConnection unavailable = new StubConnection(503, "unavailable");
        reject(() -> GitHubReleaseClient.latest(pin, unavailable), "server failure");
        reject(() -> GitHubReleaseClient.latest(pin, new StubConnection(-1, "")), "offline connection");
        reject(() -> GitHubReleaseClient.latest(pin, new StubConnection(200, "{broken")), "invalid release response");
        System.out.println("PASS: " + assertions + " production updater policy/signature assertions.");
        System.out.println("Temporary test APKs only; no Velora app was built, installed or upgraded by this host test.");
    }
    private static final class StubConnection extends java.net.HttpURLConnection {
        private final int status;
        private final String body;
        boolean closed;
        StubConnection(int status, String body) throws Exception {
            super(new java.net.URL(VeloraRelease.API_URL));
            this.status = status;
            this.body = body;
        }
        @Override public int getResponseCode() throws java.io.IOException {
            if (status < 0) throw new java.io.IOException("Offline fixture");
            return status;
        }
        @Override public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        @Override public void disconnect() { closed = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }

}
