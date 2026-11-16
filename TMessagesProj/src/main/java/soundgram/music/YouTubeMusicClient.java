package soundgram.music;

import org.json.JSONArray;
import org.json.JSONObject;
import soundgram.music.wave.InnerTubeClient;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Small anonymous direct-AAC subset, not a cipher solver or SABR implementation.
 * Design references and pinned upstream revisions are recorded in docs/MUSIC_SOURCES.ru.md.
 * Implemented locally; no Metrolist/InnerTubeX/QuackJS source is vendored.
 */
public final class YouTubeMusicClient {
    private static final String PREFIX = "https://music.youtube.com/watch?v=";
    private static final long EXPIRY_MARGIN_MS = 30_000;
    private static final Cache CACHE = new Cache(InnerTubeClient::requestAudioPlayer, System::currentTimeMillis);

    public static final class Stream {
        public final String videoId, url, title, artist, artwork;
        public final int duration, itag;
        public final long expiresAtMs, contentLength;
        public final Map<String, String> headers;

        private Stream(String id, String url, String title, String artist, String artwork,
                       int duration, long expiresAtMs, int itag, long contentLength) {
            this.videoId = id;
            this.url = url;
            this.title = title;
            this.artist = artist;
            this.artwork = artwork;
            this.duration = duration;
            this.expiresAtMs = expiresAtMs;
            this.itag = itag;
            this.contentLength = contentLength;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", InnerTubeClient.AUDIO_USER_AGENT);
            headers.put("Origin", "https://music.youtube.com");
            headers.put("Referer", "https://music.youtube.com/");
            headers.put("Accept", "audio/mp4");
            headers.put("Accept-Encoding", "identity");
            this.headers = Collections.unmodifiableMap(headers);
        }
    }

    interface PlayerLoader { String load(String videoId); }

    static final class Cache {
        private final PlayerLoader loader;
        private final LongSupplier clock;
        private final LinkedHashMap<String, Stream> entries = new LinkedHashMap<>();

        Cache(PlayerLoader loader, LongSupplier clock) { this.loader = loader; this.clock = clock; }

        Stream resolve(String videoId) {
            if (!validId(videoId)) return null;
            synchronized (entries) {
                Stream cached = entries.get(videoId);
                if (cached != null && cached.expiresAtMs > clock.getAsLong() + EXPIRY_MARGIN_MS) return cached;
                entries.remove(videoId);
            }
            // Network outside the cache lock; one slow query must not block a different track.
            Stream loaded;
            try { loaded = parsePlayer(loader.load(videoId), videoId, clock.getAsLong()); }
            catch (Exception ignored) { return null; }
            if (loaded != null) synchronized (entries) {
                if (entries.size() >= 32) entries.remove(entries.keySet().iterator().next());
                entries.put(videoId, loaded);
            }
            return loaded;
        }
    }

    private YouTubeMusicClient() {}

    public static Stream resolve(String videoId) { return CACHE.resolve(videoId); }

    public static String reference(String videoId) { return validId(videoId) ? PREFIX + videoId : null; }

    public static String videoId(String reference) {
        if (reference == null || !reference.startsWith(PREFIX)) return null;
        String id = reference.substring(PREFIX.length());
        return validId(id) ? id : null;
    }

    public static boolean isReference(String value) { return videoId(value) != null; }

    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{11}"); }

    /** Resolve a catalog candidate only after confirming a compatible full audio-only format. */
    public static MusicWebSearcher.WebTrack candidate(InnerTubeClient.RawTrack candidate) {
        if (candidate == null) return null;
        Stream stream = resolve(candidate.videoId);
        if (stream == null) return null;
        String title = safeText(candidate.title) ? candidate.title : stream.title;
        String artist = safeText(candidate.artist) ? candidate.artist : stream.artist;
        if (!safeText(title) || !safeText(artist)) return null;
        MusicWebSearcher.WebTrack track = new MusicWebSearcher.WebTrack(title, artist, reference(stream.videoId),
                String.format(Locale.US, "%d:%02d", stream.duration / 60, stream.duration % 60), stream.artwork);
        track.title = title;
        track.artist = artist;
        return track;
    }

    public static List<MusicWebSearcher.WebTrack> search(String query) {
        List<MusicWebSearcher.WebTrack> results = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        long deadline = System.nanoTime() + 8_000_000_000L;
        for (InnerTubeClient.RawTrack candidate : InnerTubeClient.searchSongsForPlayback(query, 3)) {
            if (System.nanoTime() >= deadline) break;
            if (!seen.add(candidate.videoId)) continue;
            MusicWebSearcher.WebTrack track = candidate(candidate);
            if (track != null) results.add(track);
        }
        return results;
    }

    static Stream parsePlayer(String body, String requestedId, long nowMs) {
        if (!validId(requestedId)) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject status = root.optJSONObject("playabilityStatus");
            JSONObject details = root.optJSONObject("videoDetails");
            JSONObject data = root.optJSONObject("streamingData");
            if (status == null || !"OK".equals(status.optString("status")) || details == null || data == null
                    || !requestedId.equals(details.optString("videoId"))
                    || details.optBoolean("isLiveContent", false) || details.optBoolean("isUpcoming", false)
                    || hasProtection(root, "licenseInfos") || hasProtection(root, "drmFamilies")
                    || hasProtection(data, "licenseInfos") || hasProtection(data, "drmFamilies")) return null;
            int duration = details.optInt("lengthSeconds", 0);
            if (duration <= 0) return null;
            long lifetime = data.optLong("expiresInSeconds", 0);
            if (lifetime <= 0 || lifetime > Long.MAX_VALUE / 1000 - nowMs / 1000) return null;
            long responseExpiry = nowMs + lifetime * 1000;
            JSONArray formats = data.optJSONArray("adaptiveFormats");
            if (formats == null) return null;
            JSONObject selected = null;
            long selectedExpiry = 0;
            int bestBitrate = -1;
            for (int i = 0; i < Math.min(formats.length(), 100); i++) {
                JSONObject format = formats.optJSONObject(i);
                if (format == null || !format.optString("mimeType", "").matches("audio/mp4;\\s*codecs=\"mp4a\\.40\\.2\"")
                        || format.has("width") || format.has("height")
                        || !format.optString("signatureCipher", "").isEmpty()
                        || !format.optString("cipher", "").isEmpty()
                        || hasProtection(format, "drmFamilies") || hasProtection(format, "drmTrackType")
                        || format.optLong("contentLength", 0) <= 0 || format.optInt("itag", -1) < 0) continue;
                JSONObject audioTrack = format.optJSONObject("audioTrack");
                if (audioTrack != null && !audioTrack.optBoolean("audioIsDefault", false)) continue;
                long formatDuration = format.optLong("approxDurationMs", 0);
                if (formatDuration <= 0 || Math.abs(formatDuration - duration * 1000L) > 3000) continue;
                String url = format.optString("url", "");
                long urlExpiry = mediaUrlExpiry(url);
                long expiry = Math.min(responseExpiry, urlExpiry);
                if (expiry <= nowMs + EXPIRY_MARGIN_MS) continue;
                int bitrate = format.optInt("bitrate", 0);
                if (bitrate > bestBitrate) { selected = format; selectedExpiry = expiry; bestBitrate = bitrate; }
            }
            if (selected == null) return null;
            String cover = "";
            JSONObject thumbnail = details.optJSONObject("thumbnail");
            JSONArray images = thumbnail == null ? null : thumbnail.optJSONArray("thumbnails");
            if (images != null && images.length() > 0) {
                JSONObject image = images.optJSONObject(images.length() - 1);
                String value = image == null ? "" : image.optString("url", "");
                if (value.startsWith("https://") && safeText(value)) cover = value;
            }
            return new Stream(requestedId, selected.getString("url"), details.optString("title", ""),
                    details.optString("author", ""), cover, duration, selectedExpiry,
                    selected.getInt("itag"), selected.getLong("contentLength"));
        } catch (Exception ignored) { return null; }
    }

    private static long mediaUrlExpiry(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null
                    || !(host.equals("googlevideo.com") || host.endsWith(".googlevideo.com"))
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !"/videoplayback".equals(uri.getPath()) || uri.getRawQuery() == null) return 0;
            long expiry = 0;
            for (String pair : uri.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                String key = URLDecoder.decode(kv[0], "UTF-8");
                // This subset never returns an untransformed challenge or a token-gated URL.
                if (key.equals("n") || key.equals("s") || key.equals("pot")) return 0;
                if (key.equals("expire")) {
                    if (expiry != 0 || kv.length != 2) return 0;
                    long seconds = Long.parseLong(URLDecoder.decode(kv[1], "UTF-8"));
                    if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000) return 0;
                    expiry = seconds * 1000;
                }
            }
            return expiry;
        } catch (Exception ignored) { return 0; }
    }

    private static boolean hasProtection(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return false;
        if (value instanceof JSONArray) return ((JSONArray) value).length() > 0;
        return true;
    }

    private static boolean safeText(String value) {
        return value != null && !value.trim().isEmpty() && !value.contains("|||")
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }
}
