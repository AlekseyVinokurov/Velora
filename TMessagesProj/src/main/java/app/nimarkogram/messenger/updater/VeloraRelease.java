package app.nimarkogram.messenger.updater;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Immutable, bounded metadata for a single stable APK; no executable remote configuration. */
public final class VeloraRelease {
    public static final String REPOSITORY = "AlekseyVinokurov/Velora";
    public static final String PACKAGE_NAME = "io.github.alekseyvinokurov.velora";
    public static final String API_URL = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    public static final String METADATA_NAME = "velora-release.json";
    public static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    private static final String VERSION = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)";

    public final String versionName, tag, sourceCommit, apkName, sha256, certificateSha256;
    public final int versionCode, minSdk;
    public final long size;
    private final String json;

    private VeloraRelease(JSONObject object, String trustedCertificate) throws JSONException {
        keys(object, "schemaVersion", "repository", "channel", "applicationId", "versionName", "versionCode",
                "tag", "sourceCommit", "minSdk", "apk");
        require(integer(object, "schemaVersion", 1) == 1, "Unsupported metadata schema");
        require(REPOSITORY.equals(text(object, "repository")), "Wrong repository");
        require("stable".equals(text(object, "channel")), "Not stable metadata");
        require(PACKAGE_NAME.equals(text(object, "applicationId")), "Wrong applicationId");
        versionName = text(object, "versionName");
        require(versionName.length() <= 40 && versionName.matches(VERSION), "Invalid versionName");
        versionCode = (int) integer(object, "versionCode", 2100000000L);
        tag = text(object, "tag");
        require(tag.equals("v" + versionName), "Tag and version differ");
        sourceCommit = text(object, "sourceCommit");
        require(sourceCommit.matches("[0-9a-f]{40}"), "Invalid source commit");
        minSdk = (int) integer(object, "minSdk", 1000);
        require(minSdk >= 24, "Unsupported minimum SDK");
        Object apkObject = object.get("apk");
        require(apkObject instanceof JSONObject, "Invalid APK metadata");
        JSONObject apk = (JSONObject) apkObject;
        keys(apk, "name", "size", "sha256", "certificateSha256");
        apkName = text(apk, "name");
        require(apkName.equals("Velora-" + versionName + "-" + versionCode + ".apk"), "Unexpected asset name");
        size = integer(apk, "size", MAX_APK_BYTES);
        sha256 = text(apk, "sha256");
        certificateSha256 = text(apk, "certificateSha256");
        require(sha256.matches("[0-9a-f]{64}"), "Invalid APK digest");
        require(trustedCertificate != null && trustedCertificate.matches("[0-9a-f]{64}")
                && trustedCertificate.equals(certificateSha256), "Untrusted certificate pin");
        json = object.toString();
    }

    public static VeloraRelease parse(String json, String trustedCertificate) throws JSONException {
        require(json != null && json.length() <= 64 * 1024, "Metadata too large");
        final Object value;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            value = readJson(reader, 0);
            require(reader.peek() == JsonToken.END_DOCUMENT && value instanceof JSONObject,
                    "Expected one metadata object");
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new JSONException("Invalid strict release JSON");
        }
        return new VeloraRelease((JSONObject) value, trustedCertificate);
    }

    private static Object readJson(JsonReader reader, int depth) throws java.io.IOException, JSONException {
        require(depth <= 8, "Metadata nesting too deep");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                reader.beginObject();
                JSONObject object = new JSONObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    require(names.add(name), "Duplicate metadata field");
                    object.put(name, readJson(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                throw new JSONException("Arrays are not part of release metadata");
            case STRING: return reader.nextString();
            case NUMBER:
                String number = reader.nextString();
                require(number.matches("-?(?:0|[1-9][0-9]*)"), "Expected decimal integer");
                try { return Long.parseLong(number); }
                catch (NumberFormatException e) { throw new JSONException("Integer overflow"); }
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            default: throw new JSONException("Invalid metadata value");
        }
    }

    public String toJson() { return json; }

    public String downloadUrl() { return assetUrl(tag, apkName); }

    public boolean isNewerThan(long installedVersionCode, int deviceSdk) {
        return versionCode > installedVersionCode && minSdk <= deviceSdk;
    }

    public boolean acceptsApk(String actualPackage, long actualCode, String actualName,
                              int actualMinSdk, long installedCode, int deviceSdk) {
        return PACKAGE_NAME.equals(actualPackage) && actualCode == versionCode
                && versionName.equals(actualName) && actualMinSdk == minSdk
                && isNewerThan(installedCode, deviceSdk);
    }

    public static String assetUrl(String tag, String name) {
        return "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + name;
    }

    /** Validate even /latest responses; never trust draft/prerelease flags implicitly. */
    public void validateRelease(JSONObject release) throws JSONException {
        require(Boolean.FALSE.equals(release.get("draft")) && Boolean.FALSE.equals(release.get("prerelease")),
                "Draft or prerelease");
        require(tag.equals(text(release, "tag_name")), "Release tag mismatch");
        JSONObject asset = uniqueAsset(release, apkName);
        require(size == integer(asset, "size", MAX_APK_BYTES), "Asset size mismatch");
        require(downloadUrl().equals(text(asset, "browser_download_url")), "APK URL is outside this release");
        Object digest = asset.opt("digest");
        if (digest != null && digest != JSONObject.NULL) {
            require(("sha256:" + sha256).equals(digest), "GitHub asset digest mismatch");
        }
    }

    public static JSONObject uniqueAsset(JSONObject release, String name) throws JSONException {
        JSONArray assets = release.getJSONArray("assets");
        require(assets.length() <= 100, "Too many release assets");
        JSONObject found = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (name.equals(asset.opt("name"))) {
                require(found == null && "uploaded".equals(asset.opt("state")), "Duplicate or incomplete asset");
                found = asset;
            }
        }
        require(found != null, "Required release asset is missing");
        return found;
    }

    public static void validateDownloadUrl(String value) throws java.io.IOException {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            boolean allowed = "github.com".equals(host) || "release-assets.githubusercontent.com".equals(host)
                    || "objects.githubusercontent.com".equals(host) || "github-releases.githubusercontent.com".equals(host);
            if (!"https".equals(uri.getScheme()) || !allowed || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new java.io.IOException("Untrusted release download URL");
            }
        } catch (java.net.URISyntaxException | NullPointerException e) {
            throw new java.io.IOException("Invalid release download URL");
        }
    }

    static long integer(JSONObject object, String key, long max) throws JSONException {
        Object value = object.get(key);
        require(value instanceof Integer || value instanceof Long, "Expected integer: " + key);
        long number = ((Number) value).longValue();
        require(number > 0 && number <= max, "Integer out of range: " + key);
        return number;
    }

    static String text(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        require(value instanceof String, "Expected string: " + key);
        return (String) value;
    }

    private static void keys(JSONObject object, String... expected) throws JSONException {
        Set<String> actual = new HashSet<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) actual.add(iterator.next());
        require(actual.equals(new HashSet<>(Arrays.asList(expected))), "Unknown or missing metadata fields");
    }

    static void require(boolean condition, String error) throws JSONException {
        if (!condition) throw new JSONException(error);
    }
}
