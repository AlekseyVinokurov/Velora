package soundgram.music.wave;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import soundgram.music.MusicNetworkHelper;

public final class InnerTubeClient {

    private static final String TAG = "InnerTubeClient";
    private static final String BASE_URL = "https://music.youtube.com/youtubei/v1/";
    private static final String CLIENT_VERSION = "1.20231101.01.00";
    private static final String CLIENT_NAME = "WEB_REMIX";

    public static class RawTrack {
        public final String videoId;
        public final String title;
        public final String artist;
        public final int durationSec;
        public final String downloadUrl;
        public final String coverUrl;

        public RawTrack(String videoId, String title, String artist, int durationSec) {
            this(videoId, title, artist, durationSec, null, null);
        }

        public RawTrack(String videoId, String title, String artist, int durationSec, String downloadUrl, String coverUrl) {
            this.videoId = videoId;
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.durationSec = durationSec;
            this.downloadUrl = downloadUrl;
            this.coverUrl = coverUrl;
        }

        @Override
        public String toString() {
            return artist + " - " + title + " (" + videoId + ")";
        }
    }

    public static class NextResult {
        public final List<RawTrack> tracks;
        public final String continuationToken;

        public NextResult(List<RawTrack> tracks, String continuationToken) {
            this.tracks = tracks != null ? tracks : new ArrayList<>();
            this.continuationToken = continuationToken;
        }
    }

    private InnerTubeClient() {}

    private static JSONObject buildContext() throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", CLIENT_VERSION);
        client.put("hl", "ru");
        client.put("gl", "US");

        JSONObject ctx = new JSONObject();
        ctx.put("client", client);
        return ctx;
    }

    private static final String[] RELAY_BASES = {
            "https://api.piped.private.coffee",
            "https://pipedapi.ducks.party"
    };
    private static volatile long sLastDirectFailTime = 0;
    private static final long DIRECT_RETRY_INTERVAL = 15 * 60 * 1000L; // 15 min

    private static String post(String endpoint, String jsonBody, AtomicBoolean cancelFlag) {
        if (cancelFlag != null && cancelFlag.get()) return "";
        try {
            URL url = new URL(BASE_URL + endpoint);
            HttpURLConnection conn = MusicNetworkHelper.openConnection(url);

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            conn.setRequestProperty("Origin", "https://music.youtube.com");
            conn.setRequestProperty("Referer", "https://music.youtube.com/");
            conn.setRequestProperty("X-YouTube-Client-Name", "67"); // 67 = WEB_REMIX
            conn.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);

            byte[] bytes = jsonBody.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                FileLog.w(TAG + " " + endpoint + " returned HTTP " + code);
                return "";
            }

            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    if (cancelFlag != null && cancelFlag.get()) return "";
                    bos.write(buf, 0, len);
                }
                return bos.toString("UTF-8");
            }
        } catch (Exception e) {
            FileLog.w(TAG + " post " + endpoint + " error: " + e.getMessage());
            return "";
        }
    }

    // Direct-only playback subset. Profile parameters were checked against InnerTubeX
    // e4fb0003ce092beb71f3de464c661bf9f3f54c7f (YouTubeClient.ANDROID_VR_NO_AUTH).
    // No extractor/cipher implementation is copied; no cookies or attestation are requested.
    public static final String AUDIO_USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.61.48 "
            + "(Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)";

    public static String requestAudioPlayer(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return "";
        try {
            JSONObject client = new JSONObject().put("clientName", "ANDROID_VR")
                    .put("clientVersion", "1.61.48").put("userAgent", AUDIO_USER_AGENT)
                    .put("hl", "en").put("gl", "US");
            JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client))
                    .put("videoId", videoId);
            return postPlaybackRequest("player", body, "28", "1.61.48", AUDIO_USER_AGENT);
        } catch (Exception ignored) { return ""; }
    }

    /** Bounded single catalog request for normal search; the existing radio requests are unchanged. */
    public static List<RawTrack> searchSongsForPlayback(String query, int limit) {
        List<RawTrack> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return results;
        try {
            JSONObject body = new JSONObject().put("context", buildContext()).put("query", query.trim())
                    .put("params", "EgWKAQIIAWoSEAUQAxAJEAQQChAQEBUQDhAR");
            String response = postPlaybackRequest("search", body, "67", CLIENT_VERSION,
                    "Mozilla/5.0 (Android 12; Mobile) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36");
            if (!response.isEmpty()) collectSearchSongs(new JSONObject(response), results, Math.min(5, limit));
        } catch (Exception ignored) {}
        return results;
    }

    private static String postPlaybackRequest(String endpoint, JSONObject body, String clientId,
                                               String clientVersion, String userAgent) {
        HttpURLConnection connection = null;
        try {
            connection = MusicNetworkHelper.openConnection(new URL(BASE_URL + endpoint));
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1500);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("X-YouTube-Client-Name", clientId);
            connection.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            connection.setRequestProperty("Origin", "https://music.youtube.com");
            connection.setRequestProperty("Referer", "https://music.youtube.com/");
            byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            long deadline = System.nanoTime() + 3_000_000_000L;
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            if (connection.getResponseCode() != 200) return "";
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (System.nanoTime() > deadline || output.size() + count > 1024 * 1024) return "";
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) { return ""; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private static String getFromRelay(String path, AtomicBoolean cancelFlag) {
        if (cancelFlag != null && cancelFlag.get()) return "";
        for (String base : RELAY_BASES) {
            if (cancelFlag != null && cancelFlag.get()) return "";
            try {
                URL url = new URL(base + path);
                HttpURLConnection conn = MusicNetworkHelper.openConnection(url);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");

                int code = conn.getResponseCode();
                if (code == 200) {
                    try (InputStream is = conn.getInputStream();
                         ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) != -1) {
                            if (cancelFlag != null && cancelFlag.get()) return "";
                            bos.write(buf, 0, len);
                        }
                        return bos.toString("UTF-8");
                    }
                }
            } catch (Exception e) {
                FileLog.w(TAG + " relay " + base + path + " error: " + e.getMessage());
            }
        }
        return "";
    }

    private static String extractVideoId(String url) {
        if (url == null || url.isEmpty()) return "";
        int vIdx = url.indexOf("v=");
        if (vIdx != -1) {
            String sub = url.substring(vIdx + 2);
            int amp = sub.indexOf('&');
            return amp != -1 ? sub.substring(0, amp) : sub;
        }
        if (url.startsWith("/watch?")) {
            return url.substring(7);
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash + 1 < url.length()) {
            return url.substring(lastSlash + 1);
        }
        return url;
    }

    private static NextResult fetchRadioViaRelay(String videoId, AtomicBoolean cancelFlag) {
        if (videoId == null || videoId.isEmpty() || (cancelFlag != null && cancelFlag.get())) {
            return new NextResult(null, null);
        }
        String resp = getFromRelay("/playlists/RDAMVM" + videoId, cancelFlag);
        if (resp == null || resp.isEmpty()) {
            return new NextResult(null, null);
        }
        try {
            JSONObject json = new JSONObject(resp);
            JSONArray related = json.optJSONArray("relatedStreams");
            if (related != null && related.length() > 0) {
                List<RawTrack> tracks = new ArrayList<>();
                for (int i = 0; i < related.length(); i++) {
                    JSONObject item = related.getJSONObject(i);
                    String url = item.optString("url", "");
                    String vid = extractVideoId(url);
                    String title = item.optString("title", "");
                    String artist = item.optString("uploaderName", "");
                    int duration = item.optInt("duration", 0);
                    if (!vid.isEmpty() && !title.isEmpty()) {
                        tracks.add(new RawTrack(vid, title, artist, duration));
                    }
                }
                if (!tracks.isEmpty()) {
                    FileLog.d(TAG + " fetchRadioViaRelay got " + tracks.size() + " tracks for " + videoId);
                    return new NextResult(tracks, null);
                }
            }
        } catch (Exception e) {
            FileLog.w(TAG + " fetchRadioViaRelay parse error: " + e);
        }
        return new NextResult(null, null);
    }

    private static List<RawTrack> searchSongsViaRelay(String query, int limit, AtomicBoolean cancelFlag) {
        List<RawTrack> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty() || (cancelFlag != null && cancelFlag.get())) {
            return results;
        }
        try {
            String encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8");
            String resp = getFromRelay("/search?q=" + encoded + "&filter=music_songs", cancelFlag);
            if (resp == null || resp.isEmpty()) {
                resp = getFromRelay("/search?q=" + encoded, cancelFlag);
            }
            if (resp != null && !resp.isEmpty()) {
                JSONObject json = new JSONObject(resp);
                JSONArray items = json.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        String url = item.optString("url", "");
                        String vid = extractVideoId(url);
                        String title = item.optString("title", "");
                        String artist = item.optString("uploaderName", "");
                        int duration = item.optInt("duration", 0);
                        if (!vid.isEmpty() && !title.isEmpty()) {
                            results.add(new RawTrack(vid, title, artist, duration));
                            if (results.size() >= limit) break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.w(TAG + " searchSongsViaRelay error: " + e);
        }
        return results;
    }

    /**
     * Calls /next to get radio recommendations based on videoId or continuation token.
     */
    public static NextResult fetchRadioBatch(String videoId, String continuationToken, AtomicBoolean cancelFlag) {
        try {
            boolean shouldTryDirect = (System.currentTimeMillis() - sLastDirectFailTime) > DIRECT_RETRY_INTERVAL;
            if (shouldTryDirect) {
                JSONObject body = new JSONObject();
                body.put("context", buildContext());

                if (continuationToken != null && !continuationToken.isEmpty()) {
                    body.put("continuation", continuationToken);
                } else if (videoId != null && !videoId.isEmpty()) {
                    body.put("videoId", videoId);
                    body.put("playlistId", "RDAMVM" + videoId);
                    body.put("isAudioOnly", true);
                } else {
                    return new NextResult(null, null);
                }

                String resp = post("next", body.toString(), cancelFlag);
                if (!resp.isEmpty()) {
                    JSONObject json = new JSONObject(resp);
                    List<RawTrack> tracks = new ArrayList<>();
                    collectPanelVideos(json, tracks);
                    if (tracks.isEmpty()) {
                        collectSearchSongs(json, tracks, 25);
                    }
                    String nextContinuation = findContinuationToken(json);
                    if (!tracks.isEmpty()) {
                        return new NextResult(tracks, nextContinuation);
                    }
                } else {
                    sLastDirectFailTime = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            FileLog.e(TAG + " fetchRadioBatch direct error: " + e);
            sLastDirectFailTime = System.currentTimeMillis();
        }

        if (videoId != null && !videoId.isEmpty()) {
            return fetchRadioViaRelay(videoId, cancelFlag);
        }
        return new NextResult(null, null);
    }

    /**
     * Calls /search to search for songs by query (e.g. artist name).
     */
    public static List<RawTrack> searchSongs(String query, int limit, AtomicBoolean cancelFlag) {
        List<RawTrack> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return results;
        try {
            boolean shouldTryDirect = (System.currentTimeMillis() - sLastDirectFailTime) > DIRECT_RETRY_INTERVAL;
            if (shouldTryDirect) {
                JSONObject body = new JSONObject();
                body.put("context", buildContext());
                body.put("query", query.trim());
                // Songs filter params for music.youtube.com (Треки)
                body.put("params", "EgWKAQIIAWoSEAUQAxAJEAQQChAQEBUQDhAR");

                String resp = post("search", body.toString(), cancelFlag);
                if (resp.isEmpty()) {
                    // Fallback without songs filter
                    body.remove("params");
                    resp = post("search", body.toString(), cancelFlag);
                }
                if (!resp.isEmpty()) {
                    JSONObject json = new JSONObject(resp);
                    collectSearchSongs(json, results, limit);
                }
                if (results.isEmpty() && body.has("params")) {
                    body.remove("params");
                    String respNoParams = post("search", body.toString(), cancelFlag);
                    if (!respNoParams.isEmpty()) {
                        JSONObject jsonNoParams = new JSONObject(respNoParams);
                        collectSearchSongs(jsonNoParams, results, limit);
                    }
                }
                if (!results.isEmpty()) {
                    return results;
                } else {
                    sLastDirectFailTime = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            FileLog.e(TAG + " searchSongs direct error: " + e);
            sLastDirectFailTime = System.currentTimeMillis();
        }
        return searchSongsViaRelay(query, limit, cancelFlag);
    }

    /**
     * Calls /browse to fetch tracks from a YouTube / YouTube Music playlist.
     */
    public static List<RawTrack> fetchPlaylist(String playlistId, AtomicBoolean cancelFlag) {
        List<RawTrack> results = new ArrayList<>();
        if (playlistId == null || playlistId.trim().isEmpty() || (cancelFlag != null && cancelFlag.get())) {
            return results;
        }
        String pId = playlistId.trim();
        String browseId = pId.startsWith("VL") ? pId : ("VL" + pId);
        try {
            JSONObject body = new JSONObject();
            body.put("context", buildContext());
            body.put("browseId", browseId);

            String resp = post("browse", body.toString(), cancelFlag);
            if (!resp.isEmpty()) {
                JSONObject json = new JSONObject(resp);
                collectSearchSongs(json, results, 300);
                if (results.isEmpty()) {
                    collectPanelVideos(json, results);
                }
            }
        } catch (Exception e) {
            FileLog.e(TAG + " fetchPlaylist browse error: " + e);
        }

        if (results.isEmpty() && (cancelFlag == null || !cancelFlag.get())) {
            for (String base : RELAY_BASES) {
                if (cancelFlag != null && cancelFlag.get()) break;
                try {
                    String resp = getFromRelay("/playlists/" + pId, cancelFlag);
                    if (resp != null && !resp.isEmpty()) {
                        JSONObject json = new JSONObject(resp);
                        JSONArray streams = json.optJSONArray("relatedStreams");
                        if (streams != null && streams.length() > 0) {
                            for (int i = 0; i < streams.length(); i++) {
                                JSONObject s = streams.getJSONObject(i);
                                String url = s.optString("url", "");
                                String vid = extractVideoId(url);
                                String title = s.optString("title", "");
                                String artist = s.optString("uploaderName", "");
                                int duration = s.optInt("duration", 0);
                                if (!title.isEmpty()) {
                                    results.add(new RawTrack(vid, title, artist, duration));
                                }
                            }
                            if (!results.isEmpty()) break;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return results;
    }

    private static String getRunsText(JSONObject obj) {
        if (obj == null) return null;
        try {
            JSONArray runs = obj.optJSONArray("runs");
            if (runs != null && runs.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < runs.length(); i++) {
                    String t = runs.getJSONObject(i).optString("text", null);
                    if (t != null) sb.append(t);
                }
                return sb.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int parseDurationString(String str) {
        if (str == null || str.isEmpty()) return 0;
        try {
            String[] parts = str.trim().split(":");
            if (parts.length == 2) {
                return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
            } else if (parts.length == 3) {
                return Integer.parseInt(parts[0].trim()) * 3600
                        + Integer.parseInt(parts[1].trim()) * 60
                        + Integer.parseInt(parts[2].trim());
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static void collectPanelVideos(Object node, List<RawTrack> out) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has("playlistPanelVideoRenderer")) {
                try {
                    JSONObject pvr = obj.getJSONObject("playlistPanelVideoRenderer");
                    String vid = pvr.optString("videoId", null);
                    String title = getRunsText(pvr.optJSONObject("title"));
                    String artist = getRunsText(pvr.optJSONObject("shortBylineText"));
                    if (artist == null || artist.trim().isEmpty()) {
                        artist = getRunsText(pvr.optJSONObject("longBylineText"));
                    }
                    String lengthText = getRunsText(pvr.optJSONObject("lengthText"));
                    int durSec = parseDurationString(lengthText);

                    if (vid != null && !vid.isEmpty() && title != null && !title.isEmpty()) {
                        out.add(new RawTrack(vid, title, artist != null ? artist : "", durSec));
                    }
                } catch (Exception ignored) {}
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try { collectPanelVideos(obj.get(k), out); } catch (Exception ignored) {}
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                try { collectPanelVideos(arr.get(i), out); } catch (Exception ignored) {}
            }
        }
    }

    private static void collectSearchSongs(Object node, List<RawTrack> out, int limit) {
        if (out.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has("musicResponsiveListItemRenderer")) {
                try {
                    JSONObject r = obj.getJSONObject("musicResponsiveListItemRenderer");
                    String vid = null;
                    if (r.has("playlistItemData")) {
                        vid = r.getJSONObject("playlistItemData").optString("videoId", null);
                    }
                    JSONArray flexColumns = r.optJSONArray("flexColumns");
                    String title = null;
                    String artist = null;
                    int durSec = 0;

                    if (flexColumns != null) {
                        if (flexColumns.length() > 0) {
                            JSONObject col0 = flexColumns.getJSONObject(0).optJSONObject("musicResponsiveListItemFlexColumnRenderer");
                            if (col0 != null) title = getRunsText(col0.optJSONObject("text"));
                        }
                        if (flexColumns.length() > 1) {
                            JSONObject col1 = flexColumns.getJSONObject(1).optJSONObject("musicResponsiveListItemFlexColumnRenderer");
                            if (col1 != null) {
                                JSONObject textObj = col1.optJSONObject("text");
                                if (textObj != null) {
                                    JSONArray runs = textObj.optJSONArray("runs");
                                    if (runs != null && runs.length() > 0) {
                                        artist = runs.getJSONObject(0).optString("text", null);
                                    }
                                }
                            }
                        }
                    }

                    if (vid == null && r.has("navigationEndpoint")) {
                        JSONObject ep = r.getJSONObject("navigationEndpoint");
                        if (ep.has("watchEndpoint")) {
                            vid = ep.getJSONObject("watchEndpoint").optString("videoId", null);
                        }
                    }

                    if (vid != null && !vid.isEmpty() && title != null && !title.isEmpty()) {
                        out.add(new RawTrack(vid, title, artist != null ? artist : "", durSec));
                        if (out.size() >= limit) return;
                    }
                } catch (Exception ignored) {}
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try { collectSearchSongs(obj.get(k), out, limit); } catch (Exception ignored) {}
                if (out.size() >= limit) return;
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                try { collectSearchSongs(arr.get(i), out, limit); } catch (Exception ignored) {}
                if (out.size() >= limit) return;
            }
        }
    }

    private static String findContinuationToken(Object node) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has("continuation") && obj.has("clickTrackingParams")) {
                String tok = obj.optString("continuation", null);
                if (tok != null && !tok.isEmpty()) return tok;
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try {
                    String f = findContinuationToken(obj.get(k));
                    if (f != null) return f;
                } catch (Exception ignored) {}
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                try {
                    String f = findContinuationToken(arr.get(i));
                    if (f != null) return f;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
