package app.nimarkogram.messenger.updater;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Public read-only GitHub API. No token, Telegram channel, web scraper or remote code. */
public final class GitHubReleaseClient {
    public static final class Result {
        public final VeloraRelease metadata;
        public final String changelog, publishedAt;
        Result(VeloraRelease metadata, String changelog, String publishedAt) {
            this.metadata = metadata;
            this.changelog = changelog;
            this.publishedAt = publishedAt;
        }
    }

    public static Result latest(String trustedCertificate) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(VeloraRelease.API_URL).toURL().openConnection();
        return latest(trustedCertificate, connection);
    }

    // A package-private seam for deterministic HTTP status/offline tests; the public URL stays fixed.
    static Result latest(String trustedCertificate, HttpURLConnection connection) throws Exception {
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "Velora-Android-Updater");
        final JSONObject release;
        try {
            int status = connection.getResponseCode();
            if (status == 404) return null; // Repository has no public stable release yet.
            if (status != 200) throw new IOException("GitHub release check failed: HTTP " + status);
            release = new JSONObject(readLimited(connection.getInputStream(), 512 * 1024));
        } finally {
            connection.disconnect();
        }
        VeloraRelease.require(Boolean.FALSE.equals(release.get("draft"))
                && Boolean.FALSE.equals(release.get("prerelease")), "Not a public stable release");
        String tag = VeloraRelease.text(release, "tag_name");
        VeloraRelease.require(tag.matches("v(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")
                && tag.length() <= 41, "Unexpected stable tag");
        JSONObject asset = VeloraRelease.uniqueAsset(release, VeloraRelease.METADATA_NAME);
        String expectedUrl = VeloraRelease.assetUrl(tag, VeloraRelease.METADATA_NAME);
        VeloraRelease.require(expectedUrl.equals(VeloraRelease.text(asset, "browser_download_url")), "Unexpected metadata URL");
        VeloraRelease.integer(asset, "size", 64 * 1024);
        HttpURLConnection metadataConnection = openDownload(expectedUrl, 0);
        final VeloraRelease metadata;
        try {
            if (metadataConnection.getResponseCode() != 200) throw new IOException("Incomplete release metadata response");
            metadata = VeloraRelease.parse(readLimited(metadataConnection.getInputStream(), 64 * 1024), trustedCertificate);
        } finally {
            metadataConnection.disconnect();
        }
        metadata.validateRelease(release);
        String changelog = release.optString("body", "");
        if (changelog.length() > 64 * 1024) throw new IOException("Release notes too large");
        return new Result(metadata, changelog, release.optString("published_at", ""));
    }

    public static HttpURLConnection openDownload(String url, long rangeStart) throws Exception {
        String current = url;
        for (int redirects = 0; redirects < 5; redirects++) {
            VeloraRelease.validateDownloadUrl(current);
            HttpURLConnection connection = (HttpURLConnection) URI.create(current).toURL().openConnection();
            connection.setRequestProperty("User-Agent", "Velora-Android-Updater");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (rangeStart > 0) connection.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            int status;
            try { status = connection.getResponseCode(); }
            catch (Exception e) { connection.disconnect(); throw e; }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Missing redirect location");
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            if (status != 200 && !(status == 206 && rangeStart > 0)) {
                connection.disconnect();
                throw new IOException("Release download failed: HTTP " + status);
            }
            return connection;
        }
        throw new IOException("Too many release redirects");
    }

    private static String readLimited(InputStream stream, int max) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > max) throw new IOException("Release response exceeds limit");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private GitHubReleaseClient() {}
}
