package soundgram.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Public read-only REST adapter. Contract: https://docs.audius.co/api/ .
 * Only ungated full streams are exposed. M1 deliberately does not enable downloads.
 */
public final class AudiusClient {
    private static final String BASE = "https://api.audius.co/v1/";
    private static final int LIMIT = 20;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    public static final class Track {
        public final String id, title, artist, artwork, streamUrl;
        public final int duration;

        private Track(String id, String title, String artist, int duration, String artwork) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
            this.artwork = artwork;
            // Stable API URL, never persist a short-lived redirect or preview URL.
            this.streamUrl = BASE + "tracks/" + id + "/stream?app_name=Velora";
        }
    }

    private AudiusClient() {}

    public static List<Track> search(String query, int page) {
        if (query == null || query.trim().isEmpty() || page < 1 || page > 1000) {
            return new ArrayList<>();
        }
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(query.trim(), "UTF-8");
            URL url = new URL(BASE + "tracks/search?app_name=Velora&query=" + encoded
                    + "&limit=" + LIMIT + "&offset=" + ((page - 1) * LIMIT));
            connection = MusicNetworkHelper.openConnection(url);
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1500);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Velora");
            // No retry/authentication workaround on 401/403/429 or server errors.
            connection.setInstanceFollowRedirects(false);
            long deadline = System.nanoTime() + 3_000_000_000L;
            if (connection.getResponseCode() != 200) return new ArrayList<>();
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (System.nanoTime() > deadline || output.size() + count > MAX_RESPONSE_BYTES) {
                        return new ArrayList<>();
                    }
                    output.write(buffer, 0, count);
                }
                return parseSearch(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // A source failure is isolated from the existing catalog.
            return new ArrayList<>();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static List<Track> parseSearch(String body) {
        List<Track> tracks = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(body).optJSONArray("data");
            if (data == null) return tracks;
            for (int i = 0; i < Math.min(data.length(), LIMIT); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null || !Boolean.TRUE.equals(item.opt("is_streamable"))
                        || !Boolean.FALSE.equals(item.opt("is_stream_gated"))
                        || item.optBoolean("is_delete", false)
                        || item.optBoolean("is_unlisted", false)
                        || item.optBoolean("is_private", false)
                        || item.optBoolean("is_scheduled_release", false)
                        || (item.has("is_available") && !Boolean.TRUE.equals(item.opt("is_available")))
                        || hasConditions(item, "stream_conditions")
                        || hasConditions(item, "access_authorities")
                        || hasConditions(item, "allowed_api_keys")) continue;
                JSONObject access = item.optJSONObject("access");
                // Fail closed if the API supplies an access decision other than full stream access.
                if (item.has("access") && (access == null
                        || !Boolean.TRUE.equals(access.opt("stream")))) continue;
                String id = item.optString("id", "");
                String title = item.optString("title", "").trim();
                JSONObject user = item.optJSONObject("user");
                String artist = user == null ? "" : user.optString("name", "").trim();
                int duration = item.optInt("duration", 0);
                if (!id.matches("[A-Za-z0-9]+") || !safeText(title) || !safeText(artist)
                        || duration <= 0) continue;
                JSONObject artwork = item.optJSONObject("artwork");
                String cover = artwork == null ? "" : artwork.optString("480x480", "");
                if (!cover.startsWith("https://") || !safeText(cover)) cover = "";
                tracks.add(new Track(id, title, artist, duration, cover));
            }
        } catch (Exception ignored) {
            tracks.clear();
        }
        return tracks;
    }

    private static boolean hasConditions(JSONObject item, String key) {
        Object value = item.opt(key);
        if (value == null || value == JSONObject.NULL) return false;
        if (value instanceof JSONObject) return ((JSONObject) value).length() != 0;
        if (value instanceof JSONArray) return ((JSONArray) value).length() != 0;
        return true;
    }

    private static boolean safeText(String value) {
        return !value.isEmpty() && !value.contains("|||") && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0;
    }

    /** Audius streams must not enter the legacy offline downloader in M1. */
    public static boolean isStreamUrl(String value) {
        if (value == null) return false;
        try {
            URL url = new URL(value);
            return "api.audius.co".equalsIgnoreCase(url.getHost())
                    && url.getPath().startsWith("/v1/tracks/");
        } catch (Exception ignored) {
            return false;
        }
    }
}
