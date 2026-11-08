package soundgram.music;

import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MusicWebSearcher {

    private static final ExecutorService SEARCH_EXECUTOR = Executors.newFixedThreadPool(8);

    private static final Pattern PATTERN_BRACKETS  = Pattern.compile("(?i)\\[.*?\\]|\\(.*?\\)");
    private static final Pattern PATTERN_HTML_TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern PATTERN_DATA_SRC  = Pattern.compile("data-src=[\"']([^\"']+)[\"']");
    private static final Pattern PATTERN_SRC       = Pattern.compile("src=[\"']([^\"']+)[\"']");
    private static final Pattern PATTERN_CSS_URL   = Pattern.compile("url\\(['\"]?([^'\")]+)['\"]?\\)");
    private static final Pattern PATTERN_ALPHANUM  = Pattern.compile("[^a-zа-я0-9]");

    public static String stripBrackets(String input) {
        if (input == null) return "";
        return PATTERN_BRACKETS.matcher(input).replaceAll("").trim();
    }

    public static String stripHtml(String input) {
        if (input == null) return "";
        return PATTERN_HTML_TAGS.matcher(input).replaceAll("").trim();
    }

    public static String stripNonAlphanumeric(String input) {
        if (input == null) return "";
        return PATTERN_ALPHANUM.matcher(input).replaceAll("");
    }
    // ── Кэш результатов поиска (TTL 10 минут, до 200 записей) ─────────────────
    private static final java.util.concurrent.ConcurrentHashMap<String, ArrayList<WebTrack>> searchResultCache
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> searchCacheTime
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L; // 10 минут
    private static final int  SEARCH_CACHE_MAX    = 200;

    private static ArrayList<WebTrack> getCachedSearch(String key) {
        Long ts = searchCacheTime.get(key);
        if (ts == null || System.currentTimeMillis() - ts > SEARCH_CACHE_TTL_MS) return null;
        return searchResultCache.get(key);
    }

    private static void putCachedSearch(String key, ArrayList<WebTrack> tracks) {
        if (searchResultCache.size() >= SEARCH_CACHE_MAX) {
            // Выгоняем самую старую запись
            String oldest = null; long minTs = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : searchCacheTime.entrySet()) {
                if (e.getValue() < minTs) { minTs = e.getValue(); oldest = e.getKey(); }
            }
            if (oldest != null) { searchResultCache.remove(oldest); searchCacheTime.remove(oldest); }
        }
        searchResultCache.put(key, new ArrayList<>(tracks));
        searchCacheTime.put(key, System.currentTimeMillis());
    }
    // ──────────────────────────────────────────────────────────────────────────

    public static class WebTrack {
        public String title;
        public String artist;
        public String downloadUrl;
        public String duration;
        public String coverUrl;

        public WebTrack(String title, String artist, String downloadUrl, String duration, String coverUrl) {
            this.title = cleanString(title);
            this.artist = cleanString(artist);
            this.downloadUrl = downloadUrl;
            this.duration = duration;
            this.coverUrl = coverUrl;
        }

        private String cleanString(String input) {
            if (input == null) return "Unknown";
            String noHtml = stripHtml(input);
            try { return URLDecoder.decode(noHtml, "UTF-8"); } catch (Exception e) { return noHtml; }
        }
    }

    public static class WebArtist {
        public String name;
        public String artistUrl;
        public String imageUrl;

        public WebArtist(String name, String artistUrl, String imageUrl) {
            this.name = name;
            this.artistUrl = artistUrl;
            this.imageUrl = imageUrl;
        }
    }

    public static class WebAlbum {
        public String title;
        public String artist;
        public String albumUrl;
        public String coverUrl;

        public WebAlbum(String title, String artist, String albumUrl, String coverUrl) {
            this.title = title;
            this.artist = artist;
            this.albumUrl = albumUrl;
            this.coverUrl = coverUrl;
        }
    }

    public interface SearchCallback {
        void onResult(ArrayList<WebTrack> tracks, ArrayList<WebArtist> artists, ArrayList<WebAlbum> albums);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onResult(File file);
        void onFailed();
    }

    public interface ArtistProfileCallback {
        void onResult(ArrayList<WebAlbum> albums, ArrayList<WebTrack> tracks);
        default void onTrackLoaded(WebTrack track) {}
        void onFailed();
    }

    public interface StringCallback {
        void run(String result);
    }

    public static ArrayList<WebTrack> searchTracksSync(String query) {
        ArrayList<WebTrack> tracks = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return tracks;
        try {
            String cleanQuery = query.replaceAll("\\s+$", "").trim();
            String searchUrl = "https://ru.hitmoz.org/search?q=" + URLEncoder.encode(cleanQuery, "UTF-8");
            String hitmoHtml = fetchHtml(searchUrl);
            if (hitmoHtml == null || hitmoHtml.length() < 500) {
                try {
                    String altHtml = fetchHtml("https://rus.hitmos.fm/search?q=" + URLEncoder.encode(cleanQuery, "UTF-8"));
                    if (altHtml != null && altHtml.length() > 500) hitmoHtml = altHtml;
                } catch (Exception ignored) {}
            }
            if (hitmoHtml != null && !hitmoHtml.isEmpty()) {
                parseHtmlToResults(hitmoHtml, tracks, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return tracks;
    }

    public static ArrayList<WebTrack> searchDeezerTracksSync(String query) {
        ArrayList<WebTrack> res = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return res;
        try {
            String dTrackSearch = "https://api.deezer.com/search?q=" + URLEncoder.encode(query.trim(), "UTF-8") + "&limit=30";
            String dResp = fetchHtml(dTrackSearch);
            if (dResp != null && !dResp.isEmpty()) {
                JSONObject dJson = new JSONObject(dResp);
                if (dJson.has("data")) {
                    JSONArray data = dJson.getJSONArray("data");
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject obj = data.getJSONObject(i);
                        String title = obj.optString("title", "");
                        String artist = obj.has("artist") ? obj.getJSONObject("artist").optString("name", "") : "";
                        String cover = obj.has("album") ? obj.getJSONObject("album").optString("cover_xl", "") : "";
                        int duration = obj.optInt("duration", 0);
                        String durStr = String.format(Locale.US, "%d:%02d", duration / 60, duration % 60);

                        if (!title.isEmpty()) {
                            res.add(new WebTrack(title, artist, "", durStr, cover));
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return res;
    }

    public static ArrayList<WebTrack> getGenreTracksSync(String genre) {
        ArrayList<WebTrack> res = new ArrayList<>();
        try {
            String searchHtml = fetchHtml("https://api.deezer.com/search/playlist?q=" + URLEncoder.encode(genre, "UTF-8"));
            JSONObject json = new JSONObject(searchHtml);
            if (json.has("data")) {
                JSONArray pList = json.getJSONArray("data");
                if (pList.length() > 0) {
                    int rIdx = new java.util.Random().nextInt(Math.min(15, pList.length()));
                    long pId = pList.getJSONObject(rIdx).getLong("id");

                    String tHtml = fetchHtml("https://api.deezer.com/playlist/" + pId + "/tracks?limit=50");
                    JSONArray tList = new JSONObject(tHtml).getJSONArray("data");

                    for (int i = 0; i < tList.length(); i++) {
                        JSONObject tObj = tList.getJSONObject(i);
                        String title = tObj.getString("title");
                        String artist = tObj.getJSONObject("artist").getString("name");
                        String cover = "";
                        if (tObj.has("album")) {
                            cover = tObj.getJSONObject("album").optString("cover_xl", "");
                        }
                        int dur = tObj.getInt("duration");
                        String durStr = (dur / 60) + ":" + String.format("%02d", dur % 60);
                        res.add(new WebTrack(title, artist, "", durStr, cover));
                    }
                }
            }
        } catch (Exception e) { FileLog.e(e); }
        return res;
    }

    public static ArrayList<String> getSimilarArtistsSync(String artistName) {
        ArrayList<String> res = new ArrayList<>();
        try {
            String searchHtml = fetchHtml("https://api.deezer.com/search/artist?q=" + URLEncoder.encode(artistName, "UTF-8"));
            JSONObject json = new JSONObject(searchHtml);
            if (json.has("data") && json.getJSONArray("data").length() > 0) {
                long id = json.getJSONArray("data").getJSONObject(0).getLong("id");

                String simHtml = fetchHtml("https://api.deezer.com/artist/" + id + "/related?limit=15");
                JSONArray arr = new JSONObject(simHtml).getJSONArray("data");
                for (int i = 0; i < arr.length(); i++) {
                    res.add(arr.getJSONObject(i).getString("name"));
                }
            }
        } catch (Exception e) { FileLog.e(e); }
        return res;
    }

    public static WebTrack resolveHitmosSync(String title, String artist) {
        if (title == null || title.isEmpty()) return null;
        try {
            String cleanTitle = stripBrackets(title);
            String cleanArtist = artist != null ? stripBrackets(artist) : "";
            String primaryArtist = cleanArtist.split("[,&/]|\\bfeat\\b|\\bft\\b")[0].trim();

            // 1. Поиск: "Артист Название"
            WebTrack match = searchAndMatchHitmos(cleanArtist + " " + cleanTitle, cleanTitle, cleanArtist);
            if (match != null) return match;

            // 2. Поиск: "ОсновнойАртист Название" (если несколько авторов или коллаб)
            if (!primaryArtist.isEmpty() && !primaryArtist.equalsIgnoreCase(cleanArtist)) {
                match = searchAndMatchHitmos(primaryArtist + " " + cleanTitle, cleanTitle, cleanArtist);
                if (match != null) return match;
            }

            // 3. Резервный поиск с транслитерацией кириллицы (для обхода ограничений поисковика)
            String translitQuery = (transliterate(cleanArtist) + " " + transliterate(cleanTitle)).trim();
            if (!translitQuery.equalsIgnoreCase(cleanArtist + " " + cleanTitle)) {
                match = searchAndMatchHitmos(translitQuery, cleanTitle, cleanArtist);
                if (match != null) return match;
            }

            // 4. Поиск только по названию (только если артист не задан или совпадает)
            if (!cleanTitle.isEmpty() && cleanTitle.length() > 2) {
                match = searchAndMatchHitmos(cleanTitle, cleanTitle, cleanArtist);
                if (match != null) return match;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private static WebTrack searchAndMatchHitmos(String query, String cleanTitle, String cleanArtist) {
        try {
            query = query.replaceAll("\\s+", " ").trim();
            if (query.isEmpty()) return null;

            String cacheKey = "hitmos:" + query.toLowerCase();
            ArrayList<WebTrack> list = getCachedSearch(cacheKey);
            if (list == null) {
                String html = fetchHtml("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(query, "UTF-8"));
                if (html == null || html.length() < 500) {
                    try {
                        String altHtml = fetchHtml("https://rus.hitmos.fm/search?q=" + URLEncoder.encode(query, "UTF-8"));
                        if (altHtml != null && altHtml.length() > 500) html = altHtml;
                    } catch (Exception ignored) {}
                }
                list = new ArrayList<>();
                parseHtmlToResults(html, list, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                putCachedSearch(cacheKey, list);
            }
            if (list == null || list.isEmpty()) return null;

            String lowerTitle = cleanTitle.toLowerCase();
            String lowerArtist = cleanArtist != null ? cleanArtist.toLowerCase().trim() : "";

            // Приоритет 1: совпадение названия и артиста
            for (WebTrack wt : list) {
                if (wt.downloadUrl == null || wt.downloadUrl.isEmpty()) continue;
                String wtTitle = wt.title != null ? wt.title.toLowerCase() : "";
                String wtArtist = wt.artist != null ? wt.artist.toLowerCase() : "";
                boolean tMatch = wtTitle.contains(lowerTitle) || lowerTitle.contains(wtTitle);
                boolean aMatch = lowerArtist.isEmpty() || isArtistMatch(wt.artist, cleanArtist);
                if (!aMatch && !lowerArtist.isEmpty() && !wtArtist.isEmpty()) {
                    if (lowerArtist.length() >= 4 && wtArtist.contains(lowerArtist)) aMatch = true;
                    else if (wtArtist.length() >= 4 && lowerArtist.contains(wtArtist)) aMatch = true;
                }
                if (tMatch && aMatch) {
                    return wt;
                }
            }

            // Приоритет 2: строгое совпадение по названию (только если артист совпадает или не задан)
            for (WebTrack wt : list) {
                if (wt.downloadUrl == null || wt.downloadUrl.isEmpty()) continue;
                if (!lowerArtist.isEmpty() && !isArtistMatch(wt.artist, cleanArtist)) continue;
                String wtTitle = wt.title != null ? wt.title.toLowerCase() : "";
                if (wtTitle.contains(lowerTitle) || lowerTitle.contains(wtTitle)) {
                    return wt;
                }
            }

            // Приоритет 3: если запрос включал артиста и название, проверить совпадение слов названия (но строго артист)
            for (WebTrack wt : list) {
                if (wt.downloadUrl == null || wt.downloadUrl.isEmpty()) continue;
                if (!lowerArtist.isEmpty() && !isArtistMatch(wt.artist, cleanArtist)) continue;
                String wtTitle = wt.title != null ? wt.title.toLowerCase() : "";
                String[] words = lowerTitle.split("\\s+");
                int matchCount = 0;
                for (String w : words) {
                    if (w.length() >= 3 && wtTitle.contains(w)) {
                        matchCount++;
                    }
                }
                if (matchCount > 0 && matchCount >= (words.length + 1) / 2) {
                    return wt;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private static String extractImageSafe(String content) {
        String img = "";
        Matcher m1 = PATTERN_DATA_SRC.matcher(content);
        if (m1.find()) img = m1.group(1);
        else {
            Matcher m2 = PATTERN_SRC.matcher(content);
            if (m2.find()) {
                String s = m2.group(1);
                if (!s.contains(".js") && !s.contains(".css")) img = s;
            }
            else {
                Matcher m3 = PATTERN_CSS_URL.matcher(content);
                if (m3.find()) img = m3.group(1);
            }
        }
        if (!img.isEmpty() && img.startsWith("/")) return "https://ru.hitmoz.org" + img;
        return img;
    }


    public static void loadAlbumWithDeezerFallback(String albumTitle, String artistName, ArtistProfileCallback callback) {
        new Thread(() -> {
            try {
                String cTitle = stripBrackets(albumTitle);
                String dSearch = "https://api.deezer.com/search/album?q=" + URLEncoder.encode(artistName + " " + cTitle, "UTF-8");
                String dResp = fetchHtml(dSearch);

                JSONObject dJson = new JSONObject(dResp);
                if (!dJson.has("data") || dJson.getJSONArray("data").length() == 0) {
                    dSearch = "https://api.deezer.com/search/album?q=" + URLEncoder.encode(cTitle, "UTF-8");
                    dResp = fetchHtml(dSearch);
                    dJson = new JSONObject(dResp);
                }

                if (dJson.has("data") && dJson.getJSONArray("data").length() > 0) {
                    long albumId = dJson.getJSONArray("data").getJSONObject(0).getLong("id");
                    String coverUrl = dJson.getJSONArray("data").getJSONObject(0).getString("cover_xl");
                    String tracksResp = fetchHtml("https://api.deezer.com/album/" + albumId + "/tracks");
                    JSONArray tracksArr = new JSONObject(tracksResp).getJSONArray("data");

                    ArrayList<WebTrack> initialTracks = new ArrayList<>();
                    for (int i = 0; i < tracksArr.length(); i++) {
                        String trackTitle = tracksArr.getJSONObject(i).getString("title");
                        int exactDuration = tracksArr.getJSONObject(i).getInt("duration");
                        String durStr = String.format(Locale.US, "%d:%02d", exactDuration / 60, exactDuration % 60);
                        initialTracks.add(new WebTrack(trackTitle, artistName, "", durStr, coverUrl));
                    }

                    if (initialTracks.isEmpty()) {
                        AndroidUtilities.runOnUIThread(callback::onFailed);
                        return;
                    }

                    // Emit tracklist instantly to UI so album displays in milliseconds
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(new ArrayList<>(), initialTracks));

                    // Asynchronously resolve direct stream URLs in background in parallel
                    for (int i = 0; i < tracksArr.length(); i++) {
                        JSONObject trackObj = tracksArr.optJSONObject(i);
                        if (trackObj == null) continue;
                        final String trackTitle = trackObj.optString("title", "");
                        final int exactDuration = trackObj.optInt("duration", 0);

                        SEARCH_EXECUTOR.execute(() -> {
                            try {
                                String trackClean = stripBrackets(trackTitle);

                                String trackSearchUrl = "https://ru.hitmoz.org/search?q=" + URLEncoder.encode(artistName + " " + trackClean, "UTF-8");
                                String trackHtml = fetchHtml(trackSearchUrl);
                                if (trackHtml == null || trackHtml.length() < 500) {
                                    try {
                                        String altHtml = fetchHtml("https://rus.hitmos.fm/search?q=" + URLEncoder.encode(artistName + " " + trackClean, "UTF-8"));
                                        if (altHtml != null && altHtml.length() > 500) trackHtml = altHtml;
                                    } catch (Exception ignored) {}
                                }

                                ArrayList<WebTrack> searchTracks = new ArrayList<>();
                                parseHtmlToResults(trackHtml, searchTracks, new ArrayList<>(), new ArrayList<>(), new HashSet<>());

                                WebTrack bestMatch = null;
                                for (WebTrack wt : searchTracks) {
                                    int hitmoDur = soundgram.music.ChannelMusicFragment.parseDuration(wt.duration);
                                    boolean titleMatch = wt.title.toLowerCase().contains(trackClean.toLowerCase()) || trackClean.toLowerCase().contains(wt.title.toLowerCase());
                                    boolean artistMatch = wt.artist.toLowerCase().contains(artistName.toLowerCase()) || artistName.toLowerCase().contains(wt.artist.toLowerCase());
                                    boolean durMatch = Math.abs(hitmoDur - exactDuration) <= 15;

                                    if (titleMatch && artistMatch && durMatch) {
                                        bestMatch = wt;
                                        break;
                                    }
                                    if (bestMatch == null && titleMatch && durMatch) {
                                        bestMatch = wt;
                                    }
                                }
                                if (bestMatch == null) {
                                    for (WebTrack wt : searchTracks) {
                                        if (wt.title.toLowerCase().contains(trackClean.toLowerCase()) || trackClean.toLowerCase().contains(wt.title.toLowerCase())) {
                                            bestMatch = wt;
                                            break;
                                        }
                                    }
                                }
                                if (bestMatch == null && !searchTracks.isEmpty()) {
                                    bestMatch = searchTracks.get(0);
                                }

                                if (bestMatch != null) {
                                    bestMatch.title = trackTitle;
                                    bestMatch.artist = artistName;
                                    if (bestMatch.coverUrl == null || bestMatch.coverUrl.isEmpty()) {
                                        bestMatch.coverUrl = coverUrl;
                                    }
                                    final WebTrack resolvedTrack = bestMatch;
                                    AndroidUtilities.runOnUIThread(() -> callback.onTrackLoaded(resolvedTrack));
                                }
                            } catch (Throwable t) {
                                FileLog.e(t);
                            }
                        });
                    }
                } else {
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(callback::onFailed);
            }
        }).start();
    }

    private static String transliterate(String input) {
        // BUGFIX: was "g" (Latin) at index 3, must be "г" (Cyrillic)
        String[] abcSearch = {"а","б","в","г","д","е","ё","ж","з","и","й","к","л","м","н","о","п","р","с","т","у","ф","х","ц","ч","ш","щ","ъ","ы","ь","э","ю","я","ё","капсайз","мэдкид"};
        String[] abcTrans  = {"a","b","v","g","d","e","yo","zh","z","i","y","k","l","m","n","o","p","r","s","t","u","f","kh","ts","ch","sh","sh","","y","","e","yu","ya","yo","cupsize","madk1d"};
        StringBuilder builder = new StringBuilder();
        String lower = input.toLowerCase();

        for (int i = 0; i < lower.length(); i++) {
            // multi-char entries (капсайз, мэдкид) — check them first
            boolean found = false;
            for (int j = abcSearch.length - 1; j >= 0; j--) {
                String src = abcSearch[j];
                if (src.length() > 1) {
                    if (i + src.length() <= lower.length() && lower.substring(i, i + src.length()).equals(src)) {
                        builder.append(abcTrans[j]);
                        i += src.length() - 1;
                        found = true;
                        break;
                    }
                } else {
                    if (lower.substring(i, i + 1).equals(src)) {
                        builder.append(abcTrans[j]);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) builder.append(lower.charAt(i));
        }
        return builder.toString();
    }

    public static void search(String query, SearchCallback callback) {
        search(query, 1, callback);
    }

    public static int computeRelevanceScore(String trackTitle, String trackArtist, String query, boolean hasStream) {
        if (trackTitle == null || query == null) return 0;
        String q = query.trim().toLowerCase();
        String t = trackTitle.trim().toLowerCase();
        String a = trackArtist != null ? trackArtist.trim().toLowerCase() : "";
        String full = (a + " " + t).trim();

        int score = 0;

        // 1. Точное совпадение
        if (q.equals(full) || q.equals(t) || q.equals(t + " " + a)) {
            score += 1000;
        } else if (full.startsWith(q) || t.startsWith(q) || a.startsWith(q)) {
            score += 500;
        } else if (full.contains(q) || t.contains(q) || a.contains(q)) {
            score += 200;
        }

        // 2. Вхождение слов запроса
        String[] qWords = q.split("\\s+");
        int matchedWords = 0;
        for (String w : qWords) {
            if (!w.isEmpty() && full.contains(w)) {
                matchedWords++;
                score += 50;
            }
        }
        if (qWords.length > 0 && matchedWords == qWords.length) {
            score += 150;
        }

        // 3. Пенализация ремиксов/каверов если пользователь явно не искал ремикс
        String[] remixKws = {"remix", "cover", "speed up", "slowed", "karaoke", "instrumental", "8-bit", "tribute", "ringtone"};
        boolean queryHasRemix = false;
        for (String r : remixKws) {
            if (q.contains(r)) { queryHasRemix = true; break; }
        }

        if (!queryHasRemix) {
            for (String r : remixKws) {
                if (t.contains(r)) { score -= 150; break; }
            }
        }

        // 4. Бонус за готовый прямой поток
        if (hasStream) {
            score += 250;
        }

        return score;
    }

    public static void search(String query, int page, SearchCallback callback) {
        new Thread(() -> {
            ArrayList<WebTrack> tracks = new ArrayList<>();
            ArrayList<WebArtist> artists = new ArrayList<>();
            ArrayList<WebAlbum> albums = new ArrayList<>();
            HashSet<String> seenIds = new HashSet<>();

            try {
                String cleanQuery = query != null ? query.replaceAll("\\s+$", "") : "";
                if (cleanQuery.isEmpty()) {
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(tracks, artists, albums));
                    return;
                }

                // Parallel search across Hitmos and Deezer (tracks, artists, albums)
                ArrayList<WebTrack> hitmosTracks = new ArrayList<>();
                ArrayList<WebTrack> deezerTracks = new ArrayList<>();
                CountDownLatch latch = new CountDownLatch(page == 1 ? 4 : 2);

                // 1. Поиск на Hitmos
                SEARCH_EXECUTOR.execute(() -> {
                    try {
                        String searchUrl;
                        if (page == 1) {
                            searchUrl = "https://ru.hitmoz.org/search?q=" + URLEncoder.encode(cleanQuery, "UTF-8");
                        } else {
                            searchUrl = "https://ru.hitmoz.org/search?q=" + URLEncoder.encode(cleanQuery, "UTF-8") + "&page=" + page;
                        }

                        String hitmoHtml = fetchHtml(searchUrl);
                        if (hitmoHtml == null || hitmoHtml.length() < 500) {
                            String altUrl = searchUrl.replace("ru.hitmoz.org", "rus.hitmos.fm");
                            try {
                                String altHtml = fetchHtml(altUrl);
                                if (altHtml != null && altHtml.length() > 500) {
                                    hitmoHtml = altHtml;
                                }
                            } catch (Exception ignored) {}
                        }

                        parseHtmlToResults(hitmoHtml, hitmosTracks, new ArrayList<>(), new ArrayList<>(), seenIds);
                    } catch (Exception e) {
                        FileLog.e(e);
                    } finally {
                        latch.countDown();
                    }
                });

                // 2. Поиск на Deezer (треки для обогащения метаданных/обложек)
                SEARCH_EXECUTOR.execute(() -> {
                    try {
                        String dTrackSearch = "https://api.deezer.com/search?q=" + URLEncoder.encode(cleanQuery, "UTF-8") + "&limit=40";
                        String dResp = fetchHtml(dTrackSearch);
                        if (dResp != null && !dResp.isEmpty()) {
                            JSONObject dJson = new JSONObject(dResp);
                            if (dJson.has("data")) {
                                JSONArray data = dJson.getJSONArray("data");
                                for (int i = 0; i < data.length(); i++) {
                                    JSONObject obj = data.getJSONObject(i);
                                    String title = obj.optString("title", "");
                                    String artist = obj.has("artist") ? obj.getJSONObject("artist").optString("name", "") : "";
                                    String cover = obj.has("album") ? obj.getJSONObject("album").optString("cover_xl", "") : "";
                                    int duration = obj.optInt("duration", 0);
                                    String durStr = String.format(Locale.US, "%d:%02d", duration / 60, duration % 60);
                                    String preview = obj.optString("preview", "");

                                    if (!title.isEmpty()) {
                                        synchronized (deezerTracks) {
                                            deezerTracks.add(new WebTrack(title, artist, preview, durStr, cover));
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    } finally {
                        latch.countDown();
                    }
                });

                if (page == 1) {
                    // 3. Поиск на Deezer (артисты)
                    SEARCH_EXECUTOR.execute(() -> {
                        try {
                            String artistSearch = "https://api.deezer.com/search/artist?q=" + URLEncoder.encode(cleanQuery, "UTF-8") + "&limit=25";
                            String aResp = fetchHtml(artistSearch);
                            if (aResp != null && !aResp.isEmpty()) {
                                JSONObject aJson = new JSONObject(aResp);
                                if (aJson.has("data")) {
                                    JSONArray data = aJson.getJSONArray("data");
                                    for (int i = 0; i < data.length() && artists.size() < 20; i++) {
                                        JSONObject obj = data.getJSONObject(i);
                                        String name = obj.optString("name", "");
                                        if (TextUtils.isEmpty(name)) continue;
                                        String pic = obj.optString("picture_xl", "");
                                        if (pic.isEmpty()) pic = obj.optString("picture_big", "");
                                        if (pic.isEmpty()) pic = obj.optString("picture_medium", "");
                                        synchronized (artists) {
                                            artists.add(new WebArtist(name, "DEEZER_ARTIST:" + obj.optLong("id", 0), pic));
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        } finally {
                            latch.countDown();
                        }
                    });

                    // 4. Поиск на Deezer (альбомы)
                    SEARCH_EXECUTOR.execute(() -> {
                        try {
                            String albumSearch = "https://api.deezer.com/search/album?q=" + URLEncoder.encode(cleanQuery, "UTF-8") + "&limit=25";
                            String alResp = fetchHtml(albumSearch);
                            if (alResp != null && !alResp.isEmpty()) {
                                JSONObject alJson = new JSONObject(alResp);
                                if (alJson.has("data")) {
                                    JSONArray data = alJson.getJSONArray("data");
                                    for (int i = 0; i < data.length() && albums.size() < 20; i++) {
                                        JSONObject obj = data.getJSONObject(i);
                                        String aTitle = obj.optString("title", "");
                                        if (TextUtils.isEmpty(aTitle)) continue;
                                        String artName = obj.has("artist") ? obj.getJSONObject("artist").optString("name", "") : "";
                                        String cover = obj.optString("cover_xl", "");
                                        if (cover.isEmpty()) cover = obj.optString("cover_big", "");
                                        if (cover.isEmpty()) cover = obj.optString("cover_medium", "");
                                        synchronized (albums) {
                                            albums.add(new WebAlbum(aTitle, artName, "SEARCH_ALBUM:" + aTitle + "::::" + artName, cover));
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                try {
                    latch.await(4500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {}

                // 3. Объединение результатов: Hitmos треки с прямым стримингом первичны
                HashSet<String> addedKeys = new HashSet<>();

                for (WebTrack ht : hitmosTracks) {
                    if (ht.downloadUrl == null || ht.downloadUrl.isEmpty()) continue;
                    String key = (ht.title + "|||" + ht.artist).toLowerCase();
                    if (!addedKeys.contains(key)) {
                        addedKeys.add(key);
                        tracks.add(ht);
                    }
                }



                // Обогащаем обложки треков Hitmos из Deezer
                for (WebTrack dt : deezerTracks) {
                    String cleanDtTitle = stripBrackets(dt.title).toLowerCase();
                    for (WebTrack existing : tracks) {
                        String cleanExTitle = stripBrackets(existing.title).toLowerCase();
                        if ((cleanExTitle.equals(cleanDtTitle) || cleanExTitle.contains(cleanDtTitle) || cleanDtTitle.contains(cleanExTitle))
                                && (isArtistMatch(existing.artist, dt.artist) || existing.artist.equalsIgnoreCase(dt.artist))) {
                            if ((existing.coverUrl == null || existing.coverUrl.isEmpty()) && !dt.coverUrl.isEmpty()) {
                                existing.coverUrl = dt.coverUrl;
                            }
                            break;
                        }
                    }
                }

                // 4. Умная сортировка треков по релевантности совпадения
                Collections.sort(tracks, (a, b) -> {
                    int scoreA = computeRelevanceScore(a.title, a.artist, cleanQuery, a.downloadUrl != null && !a.downloadUrl.isEmpty());
                    int scoreB = computeRelevanceScore(b.title, b.artist, cleanQuery, b.downloadUrl != null && !b.downloadUrl.isEmpty());
                    return Integer.compare(scoreB, scoreA);
                });

            } catch (Exception e) { FileLog.e(e); }
            AndroidUtilities.runOnUIThread(() -> callback.onResult(tracks, artists, albums));
        }).start();
    }


    public static boolean isArtistMatch(String trackArtist, String expectedArtist) {
        if (trackArtist == null || expectedArtist == null) return false;
        String a1 = trackArtist.trim().toLowerCase().replaceAll("\\s+", " ");
        String a2 = expectedArtist.trim().toLowerCase().replaceAll("\\s+", " ");
        if (a1.isEmpty() || a2.isEmpty()) return false;

        if (a1.equals(a2)) return true;

        String t1 = transliterate(a1).toLowerCase().replaceAll("\\s+", " ");
        String t2 = transliterate(a2).toLowerCase().replaceAll("\\s+", " ");
        if (t1.equals(t2) || a1.equals(t2) || t1.equals(a2)) return true;

        if (a1.startsWith(a2 + ",") || a1.startsWith(a2 + " &") || a1.startsWith(a2 + " feat") || a1.startsWith(a2 + " ft") || a1.startsWith(a2 + " x ") || a1.startsWith(a2 + " vs ")) return true;
        if (t1.startsWith(t2 + ",") || t1.startsWith(t2 + " &") || t1.startsWith(t2 + " feat") || t1.startsWith(t2 + " ft") || t1.startsWith(t2 + " x ") || t1.startsWith(t2 + " vs ")) return true;

        if (a1.contains(" feat. " + a2) || a1.contains(" ft. " + a2) || a1.contains(" & " + a2) || a1.contains(", " + a2) || a1.contains(" x " + a2)) return true;
        if (t1.contains(" feat. " + t2) || t1.contains(" ft. " + t2) || t1.contains(" & " + t2) || t1.contains(", " + t2) || t1.contains(" x " + t2)) return true;

        return false;
    }

    public static String fetchHitmosHtml(String urlString) {
        try {
            String html = fetchHtml(urlString);
            if (html != null && !html.isEmpty() && (html.contains("track-item") || html.contains(".mp3") || html.contains("track__info") || html.contains("artist"))) {
                return html;
            }
        } catch (Exception e) {
            FileLog.e("Primary fetch failed for " + urlString + ": " + e);
        }

        if (urlString.contains("hitmos.me") || urlString.contains("rus.hitmos.fm")) {
            String fallbackUrl = urlString.replace("hitmos.me", "ru.hitmoz.org").replace("rus.hitmos.fm", "ru.hitmoz.org");
            try {
                String fallbackHtml = fetchHtml(fallbackUrl);
                if (fallbackHtml != null && !fallbackHtml.isEmpty() && (fallbackHtml.contains("track-item") || fallbackHtml.contains(".mp3") || fallbackHtml.contains("track__info") || fallbackHtml.contains("artist"))) {
                    return fallbackHtml;
                }
            } catch (Exception e2) {
                FileLog.e("Hitmos fallback fetch failed for " + fallbackUrl + ": " + e2);
            }
        }
        return "";
    }

    public static void loadArtistProfile(String artistUrl, ArtistProfileCallback callback) {
        loadArtistProfile(artistUrl, "", callback);
    }

    public static void loadArtistProfile(String artistUrl, String artistNameHint, ArtistProfileCallback callback) {
        new Thread(() -> {
            try {
                if ((artistUrl == null || artistUrl.isEmpty()) && (artistNameHint == null || artistNameHint.isEmpty())) {
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                    return;
                }

                String cleanArtist = artistNameHint != null ? artistNameHint.trim() : "";
                if (cleanArtist.isEmpty() && artistUrl != null) {
                    String path = artistUrl.replace("https://hitmos.me", "").replace("https://rus.hitmos.fm", "").replace("https://ru.hitmoz.org", "");
                    cleanArtist = path.replace("/artist/", "").replace("artist/", "");
                    cleanArtist = cleanArtist.replaceAll("^[0-9]+-?", "").replace("-", " ").replaceAll("/.*", "").trim();
                }

                ArrayList<WebAlbum> albums = new ArrayList<>();
                ArrayList<WebTrack> tracks = new ArrayList<>();
                HashSet<String> seen = new HashSet<>();

                // 1. Попытка загрузить по прямой ссылке, если она валидна
                if (artistUrl != null && !artistUrl.isEmpty() && !artistUrl.startsWith("DEEZER_ARTIST:")) {
                    String path = artistUrl.replace("https://hitmos.me", "").replace("https://rus.hitmos.fm", "").replace("https://ru.hitmoz.org", "");
                    String baseUrl = artistUrl.startsWith("http") ? artistUrl : "https://ru.hitmoz.org" + (path.startsWith("/") ? path : "/" + path);

                    int emptyPageCount = 0;
                    for (int page = 1; page <= 50; page++) {
                        String pageUrl;
                        if (page == 1) {
                            pageUrl = baseUrl;
                        } else {
                            String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                            if (cleanBase.contains("?")) {
                                String[] parts = cleanBase.split("\\?", 2);
                                pageUrl = parts[0] + "/page/" + page + "/?" + parts[1];
                            } else {
                                pageUrl = cleanBase + "/page/" + page + "/";
                            }
                        }

                        String html = fetchHitmosHtml(pageUrl);
                        if (html == null || html.isEmpty()) {
                            emptyPageCount++;
                            if (emptyPageCount >= 2) break;
                            continue;
                        }

                        ArrayList<WebTrack> rawTracks = new ArrayList<>();
                        parseHtmlToResults(html, rawTracks, new ArrayList<>(), albums, seen);

                        ArrayList<WebTrack> newTracks = new ArrayList<>();
                        for (WebTrack t : rawTracks) {
                            if (isArtistMatch(t.artist, cleanArtist)) {
                                tracks.add(t);
                                newTracks.add(t);
                            }
                        }

                        if (!newTracks.isEmpty()) {
                            emptyPageCount = 0;
                            AndroidUtilities.runOnUIThread(() -> {
                                for (WebTrack t : newTracks) callback.onTrackLoaded(t);
                            });
                        } else {
                            emptyPageCount++;
                            if (emptyPageCount >= 2) break;
                        }
                    }
                }

                // 2. Если прямая ссылка пуста или перенаправлена, резолвим реальную страницу исполнителя на Hitmos через поиск
                if (tracks.isEmpty() && !cleanArtist.isEmpty()) {
                    String resolvedUrl = resolveHitmosArtistUrl(cleanArtist);
                    if (resolvedUrl != null && !resolvedUrl.isEmpty()) {
                        String baseUrl = resolvedUrl.startsWith("http") ? resolvedUrl : "https://ru.hitmoz.org" + (resolvedUrl.startsWith("/") ? resolvedUrl : "/" + resolvedUrl);

                        int emptyPageCount = 0;
                        for (int page = 1; page <= 50; page++) {
                            String pageUrl;
                            if (page == 1) {
                                pageUrl = baseUrl;
                            } else {
                                String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                                if (cleanBase.contains("?")) {
                                    String[] parts = cleanBase.split("\\?", 2);
                                    pageUrl = parts[0] + "/page/" + page + "/?" + parts[1];
                                } else {
                                    pageUrl = cleanBase + "/page/" + page + "/";
                                }
                            }

                            String html = fetchHitmosHtml(pageUrl);
                            if (html == null || html.isEmpty()) {
                                emptyPageCount++;
                                if (emptyPageCount >= 2) break;
                                continue;
                            }

                            ArrayList<WebTrack> rawTracks = new ArrayList<>();
                            parseHtmlToResults(html, rawTracks, new ArrayList<>(), albums, seen);

                            ArrayList<WebTrack> newTracks = new ArrayList<>();
                            for (WebTrack t : rawTracks) {
                                if (isArtistMatch(t.artist, cleanArtist)) {
                                    tracks.add(t);
                                    newTracks.add(t);
                                }
                            }

                            if (!newTracks.isEmpty()) {
                                emptyPageCount = 0;
                                AndroidUtilities.runOnUIThread(() -> {
                                    for (WebTrack t : newTracks) callback.onTrackLoaded(t);
                                });
                            } else {
                                emptyPageCount++;
                                if (emptyPageCount >= 2) break;
                            }
                        }
                    }
                }

                // 3. Фолбек: если страницы-профиля нет, ищем треки исполнителя на Hitmos с фильтрацией isArtistMatch
                if (tracks.isEmpty() && !cleanArtist.isEmpty()) {
                    loadArtistTracksFromHitmosSearchSync(cleanArtist, callback, tracks, seen);
                }

                if (tracks.isEmpty()) {
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                } else {
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(albums, tracks));
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(callback::onFailed);
            }
        }).start();
    }

    private static String resolveHitmosArtistUrl(String artistName) {
        String[] domains = {"https://ru.hitmoz.org", "https://rus.hitmos.fm", "https://hitmos.me"};
        String[] queries = {artistName, transliterate(artistName)};

        for (String domain : domains) {
            for (String q : queries) {
                if (q == null || q.isEmpty()) continue;
                try {
                    String searchUrl = domain + "/search?q=" + URLEncoder.encode(q, "UTF-8");
                    String html = fetchHtml(searchUrl);
                    if (html == null || html.isEmpty()) continue;

                    ArrayList<WebArtist> foundArtists = new ArrayList<>();
                    parseHtmlToResults(html, new ArrayList<>(), foundArtists, new ArrayList<>(), new HashSet<>());

                    for (WebArtist wa : foundArtists) {
                        if (isArtistMatch(wa.name, artistName)) {
                            return wa.artistUrl;
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        return null;
    }

    private static void loadArtistTracksFromHitmosSearchSync(String artistName, ArtistProfileCallback callback, ArrayList<WebTrack> outTracks, HashSet<String> seen) {
        String[] queries = {artistName, transliterate(artistName)};
        for (String q : queries) {
            if (q == null || q.isEmpty()) continue;
            for (int page = 1; page <= 20; page++) {
                try {
                    String searchUrl = page == 1
                            ? ("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(q, "UTF-8"))
                            : ("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(q, "UTF-8") + "&page=" + page);
                    String html = fetchHitmosHtml(searchUrl);
                    if (html == null || html.isEmpty()) break;

                    ArrayList<WebTrack> pageResults = new ArrayList<>();
                    parseHtmlToResults(html, pageResults, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                    if (pageResults.isEmpty()) break;

                    ArrayList<WebTrack> newTracks = new ArrayList<>();
                    for (WebTrack wt : pageResults) {
                        if (isArtistMatch(wt.artist, artistName)) {
                            String key = "TR_" + wt.downloadUrl;
                            if (!seen.contains(key)) {
                                seen.add(key);
                                outTracks.add(wt);
                                newTracks.add(wt);
                            }
                        }
                    }

                    if (!newTracks.isEmpty()) {
                        AndroidUtilities.runOnUIThread(() -> {
                            for (WebTrack t : newTracks) callback.onTrackLoaded(t);
                        });
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (!outTracks.isEmpty()) break;
        }
    }

    public static void loadArtistTracksDeezer(long artistId, String artistName, ArtistProfileCallback callback) {
        new Thread(() -> {
            try {
                String url = "https://api.deezer.com/artist/" + artistId + "/top?limit=100";
                String html = fetchHtml(url);
                if (html.isEmpty()) {
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                    return;
                }
                JSONObject json = new JSONObject(html);
                if (!json.has("data")) {
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                    return;
                }
                JSONArray data = json.getJSONArray("data");
                String translitName = transliterate(artistName);

                for (int i = 0; i < data.length(); i++) {
                    JSONObject obj = data.getJSONObject(i);
                    String title = obj.optString("title", "");
                    if (title.isEmpty()) continue;
                    int duration = obj.optInt("duration", 0);
                    // Безопасное извлечение обложки
                    String cover = "";
                    if (obj.has("album")) {
                        cover = obj.getJSONObject("album").optString("cover_xl", "");
                    }

                    String cleanTitle = stripBrackets(title);

                    // Стратегия 1: "Имя артиста + название"
                    ArrayList<WebTrack> results = new ArrayList<>();
                    try {
                        String q1 = artistName + " " + cleanTitle;
                        parseHtmlToResults(fetchHtml("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(q1, "UTF-8")), results, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                    } catch (Exception e) { FileLog.e(e); }

                    // Стратегия 2: транслитерация имени + название
                    if (results.isEmpty() && !translitName.equals(artistName)) {
                        try {
                            String q2 = translitName + " " + cleanTitle;
                            parseHtmlToResults(fetchHtml("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(q2, "UTF-8")), results, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                        } catch (Exception e) { FileLog.e(e); }
                    }

                    // Стратегия 3: только название
                    if (results.isEmpty()) {
                        try {
                            parseHtmlToResults(fetchHtml("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(cleanTitle, "UTF-8")), results, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                        } catch (Exception e) { FileLog.e(e); }
                    }

                    if (!results.isEmpty()) {
                        // Ищем лучший трек по длительности
                        WebTrack best = null;
                        for (WebTrack wt : results) {
                            int hitDur = soundgram.music.ChannelMusicFragment.parseDuration(wt.duration);
                            if (duration > 0 && Math.abs(hitDur - duration) <= 8) {
                                best = wt;
                                break;
                            }
                        }
                        if (best == null) best = results.get(0);

                        best.title = title;
                        best.artist = artistName;
                        best.coverUrl = cover;
                        final WebTrack finalTrack = best;
                        AndroidUtilities.runOnUIThread(() -> callback.onTrackLoaded(finalTrack));
                    }

                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
                AndroidUtilities.runOnUIThread(() -> callback.onResult(new ArrayList<>(), new ArrayList<>()));
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(callback::onFailed);
            }
        }).start();
    }
    /**
     * Следует редиректам (с правильными заголовками) и возвращает финальный URL.
     *
     * Проблема: hitmos.me редиректит на разные зеркала в зависимости от того,
     * включён ли VPN. ExoPlayer получает исходный URL и пытается открыть его
     * напрямую — без нужных заголовков (Referer, User-Agent) и без понимания
     * что нужно идти по редиректу на другой домен.
     *
     * Решение: мы сами делаем HEAD-запрос через системный прокси (который уже
     * настроен VPN), проходим все редиректы и возвращаем ExoPlayer уже
     * финальный прямой URL к аудио-файлу на зеркале.
     *
     * @param originalUrl исходный URL с hitmos.me
     * @return финальный URL после редиректов, или исходный если резолв не удался
     */
    /**
     * Резолвит финальный URL после всех редиректов.
     *
     * Ключевой момент: ExoPlayer НЕ умеет ходить через системный VPN-прокси,
     * поэтому нам нужен финальный прямой URL к аудио-файлу.
     * Стратегия:
     *  1. Пробуем через системный прокси (VPN) — получаем итоговый URL
     *  2. Если не получилось — пробуем напрямую без прокси
     *  Возвращённый URL ExoPlayer откроет сам напрямую.
     */
    public static String resolveRedirectUrl(String originalUrl) {
        if (originalUrl == null || originalUrl.isEmpty()) return originalUrl;
        if (originalUrl.startsWith("/")) return originalUrl;

        String resolved = null;

        try {
            resolved = resolveRedirectInternal(originalUrl, 0);
            if (resolved != null && !resolved.isEmpty()) return forceHttps(resolved);
        } catch (Exception e) {}


        try {
            resolved = resolveRedirectDirect(originalUrl, 0);
            if (resolved != null && !resolved.isEmpty()) return forceHttps(resolved);
        } catch (Exception e) {}

        try {
            resolved = resolveRedirectWithGet(originalUrl, 0);
            if (resolved != null && !resolved.isEmpty()) return forceHttps(resolved);
        } catch (Exception e) {}

        return forceHttps(originalUrl);
    }

    private static String forceHttps(String url) {
        if (url != null && url.startsWith("http://")) {
            return url.replaceFirst("http://", "https://");
        }
        return url;
    }


    private static String resolveRedirectWithGet(String urlString, int depth) throws Exception {
        if (depth > 10) return urlString;
        URL url = new URL(urlString);
        HttpURLConnection conn = MusicNetworkHelper.openConnection(url);
        // Используем GET вместо HEAD и Accept-Language: ru для обхода геоблока
        try { conn.setRequestMethod("GET"); } catch (Exception e) {}
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0");
        conn.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        conn.setRequestProperty("Referer", "https://ru.hitmoz.org/");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        // Не читаем тело — только заголовки
        int code = conn.getResponseCode();
        String location = conn.getHeaderField("Location");
        conn.disconnect();
        if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && location != null && !location.isEmpty()) {
            if (!location.startsWith("http")) location = new URL(url, location).toString();
            return resolveRedirectWithGet(location, depth + 1);
        }
        if (code >= 200 && code < 400) return urlString;
        return urlString;
    }

    private static String resolveRedirectDirect(String urlString, int depth) throws Exception {
        if (depth > 10) return urlString;
        URL url = new URL(urlString);
        HttpURLConnection conn = MusicNetworkHelper.openConnection(url);
        try { conn.setRequestMethod("HEAD"); } catch (ProtocolException e) { conn.setRequestMethod("GET"); }
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0");
        conn.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        conn.setRequestProperty("Referer", "https://ru.hitmoz.org/");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        int code = conn.getResponseCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || location.isEmpty()) return urlString;
            if (!location.startsWith("http")) location = new URL(url, location).toString();
            return resolveRedirectDirect(location, depth + 1);
        }
        conn.disconnect();
        return urlString;
    }

    private static String resolveRedirectInternal(String urlString, int depth)
            throws Exception {
        if (depth > 10) return urlString; // защита от петель

        URL url = new URL(urlString);
        HttpURLConnection conn = MusicNetworkHelper.openConnection(url);

        try {
            conn.setRequestMethod("HEAD"); // только заголовки — не качаем файл
        } catch (ProtocolException ignored) {
            conn.setRequestMethod("GET");
        }
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0");
        conn.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        conn.setRequestProperty("Referer", "https://ru.hitmoz.org/");
        conn.setInstanceFollowRedirects(false); // вручную, чтобы ловить http→https
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        try {
            int code = conn.getResponseCode();

            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) return urlString;
                // Относительный редирект → абсолютный
                if (!location.startsWith("http")) {
                    location = new URL(url, location).toString();
                }
                FileLog.d("resolveRedirectUrl: " + urlString + " → " + location);
                return resolveRedirectInternal(location, depth + 1);
            }

            conn.disconnect();
            // Финальный URL — это тот, на котором получили 200/206
            FileLog.d("resolveRedirectUrl: resolved → " + urlString + " (code=" + code + ")");
            return urlString;

        } catch (javax.net.ssl.SSLException e) {
            conn.disconnect();
            throw e;
        }
    }

    public static String fetchHtml(String urlString) throws Exception {
        try {
            return fetchHtmlInternal(urlString);
        } catch (javax.net.ssl.SSLException e) {
            // Certificate and hostname failures must never weaken TLS verification.
            throw e;
        } catch (java.net.SocketTimeoutException | java.net.SocketException e) {
            return fetchHtmlDirect(urlString);
        } catch (java.net.UnknownHostException e) {
            try {
                return fetchHtmlViaSystemProxy(urlString);
            } catch (Exception retryError) {
                return fetchHtmlDirect(urlString);
            }
        }
    }

    /** Прямое соединение через MusicNetworkHelper */
    private static String fetchHtmlDirect(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = MusicNetworkHelper.openConnection(url);
        applyStandardHeaders(conn);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(12000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code == 200 || code == 201 || code == 202) {
            java.io.InputStream raw = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            java.io.InputStream is = "gzip".equalsIgnoreCase(encoding)
                    ? new java.util.zip.GZIPInputStream(raw) : new BufferedInputStream(raw);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int len;
            while ((len = is.read(b)) != -1) bos.write(b, 0, len);
            is.close();
            return bos.toString("UTF-8");
        }
        return "";
    }

    /**
     * Фоллбек: запрос через MusicNetworkHelper.
     */
    private static String fetchHtmlViaSystemProxy(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = MusicNetworkHelper.openConnection(url);
        applyStandardHeaders(conn);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(12000);
        conn.setInstanceFollowRedirects(true);

        int code = conn.getResponseCode();
        if (code == 200 || code == 201) {
            java.io.InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int len;
            while ((len = is.read(b)) != -1) bos.write(b, 0, len);
            is.close();
            return bos.toString("UTF-8");
        }
        return "";
    }

    private static void applyStandardHeaders(HttpURLConnection conn) {
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn.getURL() != null && conn.getURL().getHost() != null && (conn.getURL().getHost().contains("hitmo") || conn.getURL().getHost().contains("hitmoz"))) {
                conn.setRequestProperty("Referer", "https://ru.hitmoz.org/");
            }
        } catch (ProtocolException e) {
            // Handle the case where the protocol is invalid (unlikely for "GET")
            e.printStackTrace();
        } catch (Exception e) {
            // Catch-all for other potential connection issues
            e.printStackTrace();
        }
    }


    private static String fetchHtmlInternal(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = MusicNetworkHelper.openConnection(url);

        applyStandardHeaders(conn);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);

        int responseCode = conn.getResponseCode();
        if (responseCode == 200 || responseCode == 201 || responseCode == 202) {
            String finalUrl = conn.getURL() != null ? conn.getURL().toString() : "";
            if (urlString.contains("/artist/") && !finalUrl.contains("/artist/")) {
                return "";
            }
            if (urlString.contains("/album/") && !finalUrl.contains("/album/")) {
                return "";
            }
            // Автоматическая распаковка gzip если сервер вернул его
            java.io.InputStream raw = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            java.io.InputStream is = "gzip".equalsIgnoreCase(encoding)
                    ? new java.util.zip.GZIPInputStream(raw) : new BufferedInputStream(raw);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int len;
            while ((len = is.read(b)) != -1) bos.write(b, 0, len);
            is.close();
            return bos.toString("UTF-8");
        }
        return "";
    }
    public static String transliteratePublic(String input) {
        return transliterate(input);
    }

    private static boolean isHtmlOrInvalidAudio(File file) {
        if (file == null || !file.exists() || file.length() < 1024) return true;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] header = new byte[256];
            int read = fis.read(header);
            if (read > 0) {
                String headStr = new String(header, 0, read, java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                if (headStr.contains("<!doctype") || headStr.contains("<html") || headStr.contains("<head")
                        || headStr.contains("{\"error") || headStr.contains("access denied") || headStr.contains("cloudflare")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean downloadTrackToExactFileSync(String fileUrl, File destFile) {
        if (fileUrl == null || fileUrl.isEmpty() || destFile == null) return false;
        if (destFile.exists() && destFile.length() > 1024 && !isHtmlOrInvalidAudio(destFile)) return true;

        String safeUrl = forceHttps(fileUrl);

        // 1. Загрузка с системной проверкой сертификата и имени сервера
        boolean ok = downloadTrackInternal(safeUrl, destFile);
        if (ok && destFile.exists() && destFile.length() > 1024 && !isHtmlOrInvalidAudio(destFile)) return true;

        // 2. Пробуем резолв финального URL редиректа и загрузку
        String resolved = resolveRedirectUrl(safeUrl);
        if (resolved != null && !resolved.isEmpty() && !resolved.equals(safeUrl)) {
            resolved = forceHttps(resolved);
            ok = downloadTrackInternal(resolved, destFile);
            if (ok && destFile.exists() && destFile.length() > 1024 && !isHtmlOrInvalidAudio(destFile)) return true;
        }

        // 3. Пробуем напрямую без прокси (NO_PROXY) для случая сломанного системного прокси VPN
        ok = downloadTrackDirect(safeUrl, destFile);
        if (ok && destFile.exists() && destFile.length() > 1024 && !isHtmlOrInvalidAudio(destFile)) return true;

        if (resolved != null && !resolved.isEmpty() && !resolved.equals(safeUrl)) {
            resolved = forceHttps(resolved);
            ok = downloadTrackDirect(resolved, destFile);
            if (ok && destFile.exists() && destFile.length() > 1024 && !isHtmlOrInvalidAudio(destFile)) return true;
        }

        return false;
    }

    private static boolean downloadTrackInternal(String fileUrl, File destFile) {
        HttpURLConnection conn = null;
        try {
            fileUrl = forceHttps(fileUrl);
            URL url = new URL(fileUrl);

            conn = MusicNetworkHelper.openConnection(url);

            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0");
            conn.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "identity");
            String hostReferer = (url.getHost() != null && (url.getHost().contains("hitmo") || url.getHost().contains("hitmoz") || url.getHost().contains("deliciousoranges")))
                    ? "https://ru.hitmoz.org/" : (url.getProtocol() + "://" + url.getHost() + "/");
            conn.setRequestProperty("Referer", hostReferer);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location != null && !location.isEmpty()) {
                    if (!location.startsWith("http://") && !location.startsWith("https://")) {
                        location = new URL(url, location).toString();
                    }
                    location = forceHttps(location);
                    return downloadTrackInternal(location, destFile);
                }
                return false;
            }

            if (code != 200 && code != 206) {
                conn.disconnect();
                return false;
            }

            String contentType = conn.getContentType();
            if (contentType != null) {
                String ctLower = contentType.toLowerCase();
                if (ctLower.contains("text/html") || ctLower.contains("text/plain") || ctLower.contains("application/json")) {
                    conn.disconnect();
                    return false;
                }
            }

            destFile.getParentFile().mkdirs();
            if (destFile.exists()) destFile.delete();

            java.io.InputStream is = new BufferedInputStream(conn.getInputStream());
            FileOutputStream os = new FileOutputStream(destFile);
            byte[] buffer = new byte[16384];
            int count;
            while ((count = is.read(buffer)) != -1) os.write(buffer, 0, count);
            os.flush();
            os.close();
            is.close();
            conn.disconnect();

            if (destFile.length() < 1024 || isHtmlOrInvalidAudio(destFile)) {
                destFile.delete();
                return false;
            }
            return true;

        } catch (javax.net.ssl.SSLException e) {
            if (conn != null) conn.disconnect();
            FileLog.e("downloadTrack SSL failed: " + e);
            return downloadTrackDirect(fileUrl, destFile);
        } catch (java.net.SocketTimeoutException | java.net.UnknownHostException | java.net.SocketException e) {
            if (conn != null) conn.disconnect();
            FileLog.e("downloadTrack connection failed, retrying direct: " + e);
            return downloadTrackDirect(fileUrl, destFile);
        } catch (Exception e) {
            if (conn != null) conn.disconnect();
            FileLog.e("downloadTrack failed: " + e);
            if (destFile.exists()) destFile.delete();
            return downloadTrackDirect(fileUrl, destFile);
        }
    }

    private static boolean downloadTrackDirect(String fileUrl, File destFile) {
        HttpURLConnection conn = null;
        try {
            fileUrl = forceHttps(fileUrl);
            URL url = new URL(fileUrl);
            conn = MusicNetworkHelper.openConnection(url);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0");
            conn.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "identity");
            String hostReferer = (url.getHost() != null && (url.getHost().contains("hitmo") || url.getHost().contains("hitmoz") || url.getHost().contains("deliciousoranges")))
                    ? "https://ru.hitmoz.org/" : (url.getProtocol() + "://" + url.getHost() + "/");
            conn.setRequestProperty("Referer", hostReferer);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location != null && !location.isEmpty()) {
                    if (!location.startsWith("http://") && !location.startsWith("https://")) {
                        location = new URL(url, location).toString();
                    }
                    location = forceHttps(location);
                    return downloadTrackDirect(location, destFile);
                }
                return false;
            }

            if (code != 200 && code != 206) { conn.disconnect(); return false; }

            String contentType = conn.getContentType();
            if (contentType != null) {
                String ctLower = contentType.toLowerCase();
                if (ctLower.contains("text/html") || ctLower.contains("text/plain") || ctLower.contains("application/json")) {
                    conn.disconnect();
                    return false;
                }
            }

            destFile.getParentFile().mkdirs();
            if (destFile.exists()) destFile.delete();
            java.io.InputStream is = new BufferedInputStream(conn.getInputStream());
            FileOutputStream os = new FileOutputStream(destFile);
            byte[] buffer = new byte[16384]; int count;
            while ((count = is.read(buffer)) != -1) os.write(buffer, 0, count);
            os.flush(); os.close(); is.close(); conn.disconnect();
            if (destFile.length() < 1024 || isHtmlOrInvalidAudio(destFile)) { destFile.delete(); return false; }
            return true;
        } catch (Exception e) {
            if (conn != null) conn.disconnect();
            FileLog.e("downloadTrackDirect failed: " + e);
            if (destFile.exists()) destFile.delete();
            return false;
        }
    }
    public static String getSafeFileName(String artist, String title) {
        String safe = (artist + " - " + title)
                .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (safe.length() > 200) safe = safe.substring(0, 200).trim();
        return safe + ".mp3";
    }
    public static void parseHtmlToResults(String html, ArrayList<WebTrack> tracks, ArrayList<WebArtist> artists, ArrayList<WebAlbum> albums, HashSet<String> seen) {
        if (html == null || html.isEmpty()) return;

        // 1. Next.js JSON формат (rus.hitmos.fm)
        if (html.contains("artist") && (html.contains("download") || html.contains("play"))) {
            try {
                // Universal Next.js track regex: matches "title":"...", "artist":"...", "duration":..., "play":"https://plc1.hitmos.fm/...mp3"
                // Поддерживает как title -> artist, так и artist -> title, а также любое количество эскейп-бэкслешей [\\\\]*
                Pattern pTrack1 = Pattern.compile("title[\\\\]*\"\\s*:\\s*[\\\\]*\"([^\"]+?)[\\\\]*\"\\s*,\\s*[\\\\]*\"artist[\\\\]*\"\\s*:\\s*[\\\\]*\"([^\"]+?)[\\\\]*\"\\s*,\\s*[\\\\]*\"duration[\\\\]*\"\\s*:\\s*(\\d+).*?[\\\\]*\"play[\\\\]*\"\\s*:\\s*[\\\\]*\"(https?://[^\"]+?\\.mp3[^\"]*?)[\\\\]*\"");
                Pattern pTrack2 = Pattern.compile("artist[\\\\]*\"\\s*:\\s*[\\\\]*\"([^\"]+?)[\\\\]*\"\\s*,\\s*[\\\\]*\"title[\\\\]*\"\\s*:\\s*[\\\\]*\"([^\"]+?)[\\\\]*\"\\s*,\\s*[\\\\]*\"duration[\\\\]*\"\\s*:\\s*(\\d+).*?[\\\\]*\"play[\\\\]*\"\\s*:\\s*[\\\\]*\"(https?://[^\"]+?\\.mp3[^\"]*?)[\\\\]*\"");
                Pattern pDl = Pattern.compile("[\\\\]*\"download[\\\\]*\"\\s*:\\s*[\\\\]*\"(https?://[^\"]+?\\.mp3[^\"]*?)[\\\\]*\"");
                Pattern pImg = Pattern.compile("[\\\\]*\"image(?:Url)?[\\\\]*\"\\s*:\\s*[\\\\]*\"(https?://[^\"]+?\\.(?:jpg|png|webp)[^\"]*?)[\\\\]*\"");

                Matcher tm = pTrack1.matcher(html);
                boolean foundAny = false;
                while (tm.find()) {
                    foundAny = true;
                    String title = tm.group(1).replace("\\\"", "\"").replace("\\/", "/").trim();
                    String artist = tm.group(2).replace("\\\"", "\"").replace("\\/", "/").trim();
                    int durSec = 0;
                    try { durSec = Integer.parseInt(tm.group(3)); } catch (Exception ignored) {}
                    String durStr = String.format(Locale.US, "%d:%02d", durSec / 60, durSec % 60);
                    String playUrl = tm.group(4).replace("\\/", "/").trim();

                    if (playUrl.isEmpty()) continue;
                    synchronized (seen) {
                        if (seen.contains("TR_" + playUrl)) continue;
                        seen.add("TR_" + playUrl);
                    }

                    int snipStart = tm.start();
                    int snipEnd = Math.min(html.length(), snipStart + 500);
                    String snippet = html.substring(snipStart, snipEnd);

                    Matcher dlm = pDl.matcher(snippet);
                    String downloadUrl = dlm.find() ? dlm.group(1).replace("\\/", "/").trim() : playUrl;

                    Matcher imgm = pImg.matcher(snippet);
                    String coverUrl = imgm.find() ? imgm.group(1).replace("\\/", "/").trim() : "";

                    synchronized (tracks) {
                        tracks.add(new WebTrack(title, artist, downloadUrl, durStr, coverUrl));
                    }
                }

                if (!foundAny) {
                    Matcher tm2 = pTrack2.matcher(html);
                    while (tm2.find()) {
                        String artist = tm2.group(1).replace("\\\"", "\"").replace("\\/", "/").trim();
                        String title = tm2.group(2).replace("\\\"", "\"").replace("\\/", "/").trim();
                        int durSec = 0;
                        try { durSec = Integer.parseInt(tm2.group(3)); } catch (Exception ignored) {}
                        String durStr = String.format(Locale.US, "%d:%02d", durSec / 60, durSec % 60);
                        String playUrl = tm2.group(4).replace("\\/", "/").trim();

                        if (playUrl.isEmpty()) continue;
                        synchronized (seen) {
                            if (seen.contains("TR_" + playUrl)) continue;
                            seen.add("TR_" + playUrl);
                        }

                        int snipStart = tm2.start();
                        int snipEnd = Math.min(html.length(), snipStart + 500);
                        String snippet = html.substring(snipStart, snipEnd);

                        Matcher dlm = pDl.matcher(snippet);
                        String downloadUrl = dlm.find() ? dlm.group(1).replace("\\/", "/").trim() : playUrl;

                        Matcher imgm = pImg.matcher(snippet);
                        String coverUrl = imgm.find() ? imgm.group(1).replace("\\/", "/").trim() : "";

                        synchronized (tracks) {
                            tracks.add(new WebTrack(title, artist, downloadUrl, durStr, coverUrl));
                        }
                    }
                }

                // Next.js artists
                Pattern artP = Pattern.compile("[\\\\]*\"href[\\\\]*\"\\s*:\\s*[\\\\]*\"(/artist/\\d+)[\\\\]*\"\\s*,\\s*[\\\\]*\"title[\\\\]*\"\\s*:\\s*[\\\\]*\"([^\"]+?)[\\\\]*\"");
                Matcher mArtNext = artP.matcher(html);
                while (mArtNext.find()) {
                    String url = "https://rus.hitmos.fm" + mArtNext.group(1);
                    String name = mArtNext.group(2).replace("\\\"", "\"").trim();
                    boolean alreadySeen;
                    synchronized (seen) {
                        alreadySeen = seen.contains("ART_" + url);
                        if (!alreadySeen) seen.add("ART_" + url);
                    }
                    if (!name.isEmpty() && !alreadySeen) {
                        int snipStart = mArtNext.start();
                        int snipEnd = Math.min(html.length(), snipStart + 400);
                        String snip = html.substring(snipStart, snipEnd);
                        Matcher mImg = Pattern.compile("[\\\\]*\"src[\\\\]*\"\\s*:\\s*[\\\\]*\"(https?://static\\.hitmos\\.fm/[^\"]+?)[\\\\]*\"").matcher(snip);
                        String img = mImg.find() ? mImg.group(1).replace("\\/", "/") : "";
                        synchronized (artists) {
                            artists.add(new WebArtist(name, url, img));
                        }
                    }
                }

                // Next.js albums
                Pattern albP = Pattern.compile("[\\\\]*\"href[\\\\]*\"\\s*:\\s*[\\\\]*\"(/album/\\d+)[\\\\]*\"\\s*,\\s*[\\\\]*\"title[\\\\]*\"\\s*:\\s*[\\\\]*\"([^\"]+?)[\\\\]*\"");
                Matcher mAlbNext = albP.matcher(html);
                while (mAlbNext.find()) {
                    String url = "https://rus.hitmos.fm" + mAlbNext.group(1);
                    String title = mAlbNext.group(2).replace("\\\"", "\"").trim();
                    boolean alreadySeen;
                    synchronized (seen) {
                        alreadySeen = seen.contains("ALB_" + url);
                        if (!alreadySeen) seen.add("ALB_" + url);
                    }
                    if (!title.isEmpty() && !title.toLowerCase().contains("скачать") && !alreadySeen) {
                        int snipStart = mAlbNext.start();
                        int snipEnd = Math.min(html.length(), snipStart + 400);
                        String snip = html.substring(snipStart, snipEnd);
                        Matcher mImg = Pattern.compile("[\\\\]*\"src[\\\\]*\"\\s*:\\s*[\\\\]*\"(https?://static\\.hitmos\\.fm/[^\"]+?)[\\\\]*\"").matcher(snip);
                        String img = mImg.find() ? mImg.group(1).replace("\\/", "/") : "";
                        String cardArtist = extract(snip, "class=[\"']card__subtitle[\"'][^>]*>\\s*(.*?)\\s*</");
                        String albArtist = (cardArtist != null && !cardArtist.isEmpty()) ? stripHtml(cardArtist) : "";
                        if (albArtist.isEmpty() && title.contains(" - ")) {
                            String[] p = title.split(" - ", 2);
                            albArtist = p[0].trim();
                            title = p[1].trim();
                        }
                        synchronized (albums) {
                            albums.add(new WebAlbum(title, !albArtist.isEmpty() ? albArtist : "", url, img));
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        // 2. Стандартный HTML формат (hitmos.me и зеркала)
        String delimiter = html.contains("class=\"track__info\"") ? "class=\"track__info\"" : "class=\"track-item\"";
        String[] blocks = html.split(delimiter);
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            String downloadUrl = extract(block, "href=\"([^\"]+\\.mp3[^\"]*)\"");
            if (downloadUrl == null) downloadUrl = extract(block, "data-url=\"([^\"]+)\"");
            if (downloadUrl == null) continue;

            if (!downloadUrl.startsWith("http")) downloadUrl = "https://ru.hitmoz.org" + downloadUrl;
            synchronized (seen) {
                if (seen.contains("TR_" + downloadUrl)) continue;
                seen.add("TR_" + downloadUrl);
            }

            String title = extract(block, "class=\"track__title\"[^>]*>\\s*(.*?)\\s*</");
            if (title == null) title = extract(block, "class=\"track-title\"[^>]*>\\s*(.*?)\\s*</");
            String artist = extract(block, "class=\"track__desc\"[^>]*>\\s*(.*?)\\s*</");
            if (artist == null) artist = extract(block, "class=\"track-artist\"[^>]*>\\s*(.*?)\\s*</");
            String duration = extract(block, "class=\"track__fulltime\"[^>]*>\\s*(.*?)\\s*</");
            if (duration == null) duration = extract(block, "class=\"track-time\"[^>]*>\\s*(.*?)\\s*</");
            String cover = extractImageSafe(block);

            synchronized (tracks) {
                tracks.add(new WebTrack(
                        title != null ? stripHtml(title) : "Unknown",
                        artist != null ? stripHtml(artist) : "Unknown",
                        downloadUrl,
                        duration != null ? duration : "0:00",
                        cover
                ));
            }
        }

        Matcher mArt = Pattern.compile("<a[^>]+href=[\"']([^\"']*/artist/[^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
        while (mArt.find()) {
            String url = mArt.group(1);
            if (!url.startsWith("http")) url = "https://ru.hitmoz.org" + url;
            String innerHtml = mArt.group(2);
            String name = stripHtml(innerHtml);

            boolean alreadySeen;
            synchronized (seen) {
                alreadySeen = seen.contains("ART_" + url);
                if (!alreadySeen && !android.text.TextUtils.isEmpty(name)) seen.add("ART_" + url);
            }
            if (!android.text.TextUtils.isEmpty(name) && !alreadySeen) {
                synchronized (artists) {
                    artists.add(new WebArtist(name, url, extractImageSafe(innerHtml)));
                }
            }
        }

        Matcher mAlb = Pattern.compile("<a[^>]+href=[\"']([^\"']*/album/[^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
        while (mAlb.find()) {
            String url = mAlb.group(1);
            if (!url.startsWith("http")) url = "https://ru.hitmoz.org" + url;
            String innerHtml = mAlb.group(2);
            String cardTitle = extract(innerHtml, "class=[\"']card__title[\"'][^>]*>\\s*(.*?)\\s*</");
            String title = (cardTitle != null && !cardTitle.isEmpty())
                    ? stripHtml(cardTitle)
                    : stripHtml(innerHtml);

            String cardArtist = extract(innerHtml, "class=[\"']card__subtitle[\"'][^>]*>\\s*(.*?)\\s*</");
            String artist = (cardArtist != null && !cardArtist.isEmpty())
                    ? stripHtml(cardArtist) : "";
            if (artist.isEmpty() && title.contains(" - ")) {
                String[] p = title.split(" - ", 2);
                artist = p[0].trim();
                title = p[1].trim();
            }

            boolean alreadySeen;
            synchronized (seen) {
                alreadySeen = seen.contains("ALB_" + url);
                if (!alreadySeen && !android.text.TextUtils.isEmpty(title) && !title.toLowerCase().contains("скачать")) seen.add("ALB_" + url);
            }
            if (!android.text.TextUtils.isEmpty(title) && !title.toLowerCase().contains("скачать") && !alreadySeen) {
                synchronized (albums) {
                    albums.add(new WebAlbum(title, !artist.isEmpty() ? artist : "", url, extractImageSafe(innerHtml)));
                }
            }
        }
    }

    private static String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, ArrayList<WebAlbum>> sArtistAlbumsCache = new java.util.concurrent.ConcurrentHashMap<>();

    public static void loadArtistAlbumsViaDeezer(String artistName, ArtistProfileCallback callback) {
        loadArtistAlbumsViaDeezer(artistName, null, callback);
    }

    public static void loadArtistAlbumsViaDeezer(String artistName, String artistUrl, ArtistProfileCallback callback) {
        if (artistName == null) artistName = "";
        final String finalArtistName = artistName;
        final String cacheKey = ((artistUrl != null && !artistUrl.isEmpty() ? artistUrl : artistName).trim().toLowerCase(java.util.Locale.ROOT));

        ArrayList<WebAlbum> cached = sArtistAlbumsCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(new ArrayList<>(cached), new ArrayList<>()));
            return;
        }

        new Thread(() -> {
            try {
                long artistId = -1;
                if (artistUrl != null && artistUrl.startsWith("DEEZER_ARTIST:")) {
                    try {
                        artistId = Long.parseLong(artistUrl.substring("DEEZER_ARTIST:".length()).trim());
                    } catch (Exception ignored) {}
                }

                if (artistId <= 0) {
                    String cleanArtist = stripBrackets(finalArtistName);
                    String searchUrl = "https://api.deezer.com/search/artist?q=" + URLEncoder.encode(cleanArtist, "UTF-8");
                    String response = fetchHtml(searchUrl);
                    if (!response.isEmpty()) {
                        JSONObject json = new JSONObject(response);
                        if (json.has("data") && json.getJSONArray("data").length() > 0) {
                            artistId = json.getJSONArray("data").getJSONObject(0).optLong("id", -1);
                        }
                    }
                    if (artistId <= 0 && (cleanArtist.contains(",") || cleanArtist.contains("&") || cleanArtist.toLowerCase().contains("feat") || cleanArtist.toLowerCase().contains("ft."))) {
                        String primary = cleanArtist.split("(?i),|&|\\bfeat\\.?\\b|\\bft\\.?\\b")[0].trim();
                        if (!primary.isEmpty()) {
                            String searchUrl2 = "https://api.deezer.com/search/artist?q=" + URLEncoder.encode(primary, "UTF-8");
                            String response2 = fetchHtml(searchUrl2);
                            if (!response2.isEmpty()) {
                                JSONObject json2 = new JSONObject(response2);
                                if (json2.has("data") && json2.getJSONArray("data").length() > 0) {
                                    artistId = json2.getJSONArray("data").getJSONObject(0).optLong("id", -1);
                                }
                            }
                        }
                    }
                }

                ArrayList<WebAlbum> finalAlbums = new ArrayList<>();
                if (artistId > 0) {
                    String albumsResp = fetchHtml("https://api.deezer.com/artist/" + artistId + "/albums?limit=50");
                    if (!albumsResp.isEmpty()) {
                        JSONObject albumsJson = new JSONObject(albumsResp);
                        if (albumsJson.has("data")) {
                            JSONArray albumsArr = albumsJson.getJSONArray("data");
                            for (int i = 0; i < albumsArr.length(); i++) {
                                JSONObject albObj = albumsArr.getJSONObject(i);
                                String title = albObj.optString("title", "");
                                if (title.isEmpty()) continue;
                                String cover = albObj.optString("cover_xl", "");
                                if (cover.isEmpty()) cover = albObj.optString("cover_big", "");
                                if (cover.isEmpty()) cover = albObj.optString("cover_medium", "");
                                if (cover.isEmpty()) cover = albObj.optString("cover_small", "");
                                if (cover.isEmpty()) cover = albObj.optString("cover", "");
                                finalAlbums.add(new WebAlbum(title, finalArtistName, "SEARCH_ALBUM:" + title + "::::" + finalArtistName, cover));
                            }
                        }
                    }
                }

                // If Deezer returned no albums, try Hitmos albums fallback
                if (finalAlbums.isEmpty() && !finalArtistName.isEmpty()) {
                    try {
                        String hitmosSearchUrl = "https://ru.hitmoz.org/search?q=" + URLEncoder.encode(finalArtistName, "UTF-8");
                        String html = fetchHitmosHtml(hitmosSearchUrl);
                        if (html != null && !html.isEmpty()) {
                            ArrayList<WebTrack> dummyTracks = new ArrayList<>();
                            ArrayList<WebArtist> dummyArtists = new ArrayList<>();
                            ArrayList<WebAlbum> hitmosAlbums = new ArrayList<>();
                            parseHtmlToResults(html, dummyTracks, dummyArtists, hitmosAlbums, new HashSet<>());
                            for (WebAlbum ha : hitmosAlbums) {
                                if (ha != null && ha.title != null && !ha.title.isEmpty()) {
                                    if (ha.artist == null || ha.artist.isEmpty()) {
                                        ha.artist = finalArtistName;
                                    }
                                    finalAlbums.add(ha);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (!finalAlbums.isEmpty()) {
                    sArtistAlbumsCache.put(cacheKey, finalAlbums);
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(finalAlbums, new ArrayList<>()));
                } else {
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(callback::onFailed);
            }
        }).start();
    }

    public static ArrayList<String> getArtistGenresSync(String artistName) {
        ArrayList<String> genres = new ArrayList<>();
        try {
            // Ищем артиста
            String searchResp = fetchHtml("https://api.deezer.com/search/artist?q="
                    + URLEncoder.encode(artistName, "UTF-8") + "&limit=1");
            JSONObject json = new JSONObject(searchResp);
            if (!json.has("data") || json.getJSONArray("data").length() == 0) return genres;

            long artistId = json.getJSONArray("data").getJSONObject(0).getLong("id");

            // Получаем топ-треки артиста и собираем жанры из их альбомов
            String topResp = fetchHtml("https://api.deezer.com/artist/" + artistId + "/top?limit=10");
            JSONObject topJson = new JSONObject(topResp);
            if (!topJson.has("data")) return genres;

            JSONArray tracks = topJson.getJSONArray("data");
            java.util.LinkedHashMap<String, Integer> genreCount = new java.util.LinkedHashMap<>();

            for (int i = 0; i < Math.min(tracks.length(), 5); i++) {
                JSONObject track = tracks.getJSONObject(i);
                if (!track.has("album")) continue;
                long albumId = track.getJSONObject("album").getLong("id");

                try {
                    String albumResp = fetchHtml("https://api.deezer.com/album/" + albumId);
                    JSONObject albumJson = new JSONObject(albumResp);
                    if (albumJson.has("genres")) {
                        JSONArray genresArr = albumJson.getJSONObject("genres").getJSONArray("data");
                        for (int j = 0; j < genresArr.length(); j++) {
                            String genre = genresArr.getJSONObject(j).getString("name");
                            genreCount.merge(genre, 1, Integer::sum);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Сортируем по частоте
            genres.addAll(genreCount.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toList()));

        } catch (Exception e) { FileLog.e(e); }
        return genres;
    }

    /**
     * Возвращает список жанров альбома через Deezer API.
     */
    public static ArrayList<String> getAlbumGenresSync(String albumTitle, String artistName) {
        ArrayList<String> genres = new ArrayList<>();
        try {
            String query = (artistName.isEmpty() ? albumTitle : artistName + " " + albumTitle);
            String searchResp = fetchHtml("https://api.deezer.com/search/album?q="
                    + URLEncoder.encode(query, "UTF-8") + "&limit=1");
            JSONObject json = new JSONObject(searchResp);
            if (!json.has("data") || json.getJSONArray("data").length() == 0) return genres;

            long albumId = json.getJSONArray("data").getJSONObject(0).getLong("id");
            String albumResp = fetchHtml("https://api.deezer.com/album/" + albumId);
            JSONObject albumJson = new JSONObject(albumResp);

            if (albumJson.has("genres")) {
                JSONArray genresArr = albumJson.getJSONObject("genres").getJSONArray("data");
                for (int i = 0; i < genresArr.length(); i++) {
                    genres.add(genresArr.getJSONObject(i).getString("name"));
                }
            }
        } catch (Exception e) { FileLog.e(e); }
        return genres;
    }
    /**
     * Полная загрузка треков артиста через дискографию Deezer.
     *
     * Стратегия:
     *  1. Ищем артиста в Deezer → получаем artistId
     *  2. Получаем все альбомы (/albums?limit=100, с пагинацией через next)
     *  3. Для каждого альбома: /album/{id}/tracks → список треков с duration
     *  4. Для каждого трека ищем скачиваемую версию на hitmoz (3 стратегии)
     *  5. Найденные треки отдаём через onTrackLoaded в реальном времени
     *
     * Лимит: до 500 треков (настраивается через MAX_TRACKS).
     * Дедупликация по «артист + title» чтобы одни и те же треки из разных
     * изданий альбома не дублировались.
     */
    public static void loadAllArtistTracksDeezer(long artistId, String artistName,
                                                 ArtistProfileCallback callback) {
        new Thread(() -> {
            final int MAX_TRACKS = 500;
            try {
                String translitName = transliterate(artistName);
                java.util.Set<String> seenTitles = java.util.Collections.synchronizedSet(new HashSet<>());

                // Шаг 1: получаем все альбомы артиста (пагинация)
                ArrayList<long[]> albumEntries = new ArrayList<>();
                ArrayList<String> albumCovers  = new ArrayList<>();
                String albumsUrl = "https://api.deezer.com/artist/" + artistId + "/albums?limit=100&index=0";
                while (albumsUrl != null) {
                    String resp = fetchHtml(albumsUrl);
                    if (resp == null || resp.isEmpty()) break;
                    JSONObject albumsJson = new JSONObject(resp);
                    if (!albumsJson.has("data")) break;
                    JSONArray albumsArr = albumsJson.getJSONArray("data");
                    for (int ai = 0; ai < albumsArr.length(); ai++) {
                        JSONObject albObj = albumsArr.getJSONObject(ai);
                        albumEntries.add(new long[]{albObj.getLong("id")});
                        albumCovers.add(albObj.optString("cover_xl", ""));
                    }
                    albumsUrl = albumsJson.has("next") ? albumsJson.getString("next") : null;
                }

                // Шаг 2: собираем все треки из всех альбомов
                ArrayList<String[]> allTracks = new ArrayList<>();
                for (int ai = 0; ai < albumEntries.size() && allTracks.size() < MAX_TRACKS; ai++) {
                    long albId = albumEntries.get(ai)[0];
                    String cover = albumCovers.get(ai);
                    String tracksResp = fetchHtml("https://api.deezer.com/album/" + albId + "/tracks");
                    if (tracksResp == null || tracksResp.isEmpty()) continue;
                    JSONObject tracksJson = new JSONObject(tracksResp);
                    if (!tracksJson.has("data")) continue;
                    JSONArray tracksArr = tracksJson.getJSONArray("data");
                    for (int ti = 0; ti < tracksArr.length() && allTracks.size() < MAX_TRACKS; ti++) {
                        JSONObject tObj = tracksArr.getJSONObject(ti);
                        String trackTitle = tObj.optString("title", "");
                        if (trackTitle.isEmpty()) continue;
                        String dedupeKey = (artistName + "|" + trackTitle).toLowerCase();
                        if (seenTitles.contains(dedupeKey)) continue;
                        seenTitles.add(dedupeKey);
                        allTracks.add(new String[]{trackTitle, String.valueOf(tObj.optInt("duration", 0)), cover});
                    }
                }

                if (allTracks.isEmpty()) {
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                    return;
                }

                // Шаг 3: параллельный поиск на Hitmos с сохранением порядка дискографии — 8 потоков
                int totalDiscographyTracks = allTracks.size();
                ExecutorService executor = Executors.newFixedThreadPool(8);
                AtomicInteger foundCount = new AtomicInteger(0);
                CountDownLatch latch = new CountDownLatch(totalDiscographyTracks);
                WebTrack[] orderedDiscographyResults = new WebTrack[totalDiscographyTracks];

                for (int idx = 0; idx < totalDiscographyTracks; idx++) {
                    final int trackIdx = idx;
                    final String trackTitle = allTracks.get(idx)[0];
                    final int duration = Integer.parseInt(allTracks.get(idx)[1]);
                    final String cover = allTracks.get(idx)[2];
                    final String cleanTitle = stripBrackets(trackTitle);

                    executor.submit(() -> {
                        try {
                            ArrayList<WebTrack> results = new ArrayList<>();
                            try {
                                parseHtmlToResults(fetchHtml("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(artistName + " " + cleanTitle, "UTF-8")), results, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                            } catch (Exception e) { FileLog.e(e); }
                            if (results.isEmpty() && !translitName.equals(artistName)) {
                                try {
                                    parseHtmlToResults(fetchHtml("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(translitName + " " + cleanTitle, "UTF-8")), results, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                                } catch (Exception e) { FileLog.e(e); }
                            }
                            WebTrack best = null;
                            if (!results.isEmpty()) {
                                best = findBestMatch(results, cleanTitle, artistName, duration, true);
                            }
                            if (best != null) {
                                best.title  = trackTitle;
                                best.artist = artistName;
                                if (!cover.isEmpty() && (best.coverUrl == null || best.coverUrl.isEmpty())) best.coverUrl = cover;
                            } else {
                                String durStr = String.format(Locale.US, "%d:%02d", duration / 60, duration % 60);
                                best = new WebTrack(trackTitle, artistName, "", durStr, cover);
                            }
                            foundCount.incrementAndGet();
                            orderedDiscographyResults[trackIdx] = best;
                        } catch (Exception e) {
                            FileLog.e(e);
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                executor.shutdown();
                latch.await();

                if (foundCount.get() == 0) {
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                } else {
                    ArrayList<WebTrack> orderedDiscographyTracks = new ArrayList<>();
                    for (WebTrack wt : orderedDiscographyResults) {
                        if (wt != null) orderedDiscographyTracks.add(wt);
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        for (WebTrack wt : orderedDiscographyTracks) callback.onTrackLoaded(wt);
                        callback.onResult(new ArrayList<>(), new ArrayList<>());
                    });
                }

            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(callback::onFailed);
            }
        }).start();
    }

    /**
     * Ищет треки исполнителя напрямую на Hitmos через текстовый поиск.
     * Принимает только треки, у которых поле artist совпадает с artistName 1 в 1
     * (без учёта регистра). Результаты отдаются через onTrackLoaded в реальном времени.
     * Используется когда у исполнителя нет страницы-профиля на Hitmos,
     * но он есть на Deezer.
     */
    public static void loadArtistTracksFromHitmosSearch(String artistName, ArtistProfileCallback callback) {
        new Thread(() -> {
            try {
                ArrayList<WebTrack> allFound = new ArrayList<>();
                HashSet<String> seen = new HashSet<>();
                String normalArtist = artistName.trim();
                String translit = transliterate(normalArtist);

                // Формируем список запросов: оригинал + транслит (если отличается)
                ArrayList<String> queries = new ArrayList<>();
                queries.add(normalArtist);
                if (!translit.equalsIgnoreCase(normalArtist)) {
                    queries.add(translit);
                }

                for (String q : queries) {
                    for (int page = 1; page <= 30; page++) {
                        String searchUrl = page == 1
                                ? ("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(q, "UTF-8"))
                                : ("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(q, "UTF-8") + "&page=" + page);
                        String html = fetchHtml(searchUrl);
                        if (html == null || html.length() < 500) {
                            try {
                                String altHtml = fetchHtml(searchUrl.replace("ru.hitmoz.org", "rus.hitmos.fm"));
                                if (altHtml != null && altHtml.length() > 500) html = altHtml;
                            } catch (Exception ignored) {}
                        }
                        if (html == null || html.isEmpty()) break;

                        ArrayList<WebTrack> pageResults = new ArrayList<>();
                        parseHtmlToResults(html, pageResults, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                        if (pageResults.isEmpty()) break;

                        boolean foundOnPage = false;
                        for (WebTrack wt : pageResults) {
                            // Точное совпадение имени исполнителя (без учёта регистра)
                            String wtArtist = wt.artist.trim();
                            boolean matches = wtArtist.equalsIgnoreCase(normalArtist)
                                    || wtArtist.equalsIgnoreCase(translit);
                            if (!matches) continue;

                            String dedupeKey = (wtArtist + "|" + wt.title).toLowerCase();
                            if (seen.contains(dedupeKey)) continue;
                            seen.add(dedupeKey);

                            wt.artist = normalArtist; // нормализуем имя к оригиналу
                            allFound.add(wt);
                            final WebTrack ft = wt;
                            AndroidUtilities.runOnUIThread(() -> callback.onTrackLoaded(ft));
                            foundOnPage = true;
                        }

                        // Если на трёх страницах подряд нет нужного артиста — прекращаем
                        if (!foundOnPage && page >= 3) break;
                    }
                }

                if (allFound.isEmpty()) {
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                } else {
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(new ArrayList<>(), allFound));
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(callback::onFailed);
            }
        }).start();
    }

    public static void fetchArtistPictureFromDeezer(String artistName, StringCallback callback) {
        new Thread(() -> {
            try {
                String urlString = "https://api.deezer.com/search/artist?q=" + URLEncoder.encode(artistName, "UTF-8");
                HttpURLConnection conn = MusicNetworkHelper.openConnection(urlString);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int len;
                    while ((len = is.read(b)) != -1) bos.write(b, 0, len);
                    String json = bos.toString("UTF-8");

                    org.json.JSONObject jsonObject = new org.json.JSONObject(json);
                    org.json.JSONArray data = jsonObject.getJSONArray("data");
                    if (data.length() > 0) {
                        String picUrl = data.getJSONObject(0).getString("picture_xl");
                        AndroidUtilities.runOnUIThread(() -> callback.run(picUrl));
                        return;
                    }
                }
                AndroidUtilities.runOnUIThread(() -> callback.run(null));
            } catch (Exception e) { AndroidUtilities.runOnUIThread(() -> callback.run(null)); }
        }).start();
    }

    public static void fetchRelatedArtistsFromDeezer(String artistName, org.telegram.messenger.Utilities.Callback<ArrayList<String>> callback) {
        new Thread(() -> {
            try {
                String searchUrl = "https://api.deezer.com/search/artist?q=" + URLEncoder.encode(artistName, "UTF-8");
                HttpURLConnection conn = MusicNetworkHelper.openConnection(searchUrl);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int len;
                    while ((len = is.read(b)) != -1) bos.write(b, 0, len);
                    String json = bos.toString("UTF-8");

                    org.json.JSONObject jsonObject = new org.json.JSONObject(json);
                    org.json.JSONArray data = jsonObject.optJSONArray("data");
                    if (data != null && data.length() > 0) {
                        long artistId = data.getJSONObject(0).optLong("id", 0);
                        if (artistId != 0) {
                            String relUrl = "https://api.deezer.com/artist/" + artistId + "/related";
                            HttpURLConnection relConn = MusicNetworkHelper.openConnection(relUrl);
                            relConn.setRequestMethod("GET");
                            relConn.setConnectTimeout(5000);
                            if (relConn.getResponseCode() == 200) {
                                InputStream relIs = relConn.getInputStream();
                                ByteArrayOutputStream relBos = new ByteArrayOutputStream();
                                while ((len = relIs.read(b)) != -1) relBos.write(b, 0, len);
                                String relJson = relBos.toString("UTF-8");
                                org.json.JSONObject relObj = new org.json.JSONObject(relJson);
                                org.json.JSONArray relData = relObj.optJSONArray("data");
                                if (relData != null && relData.length() > 0) {
                                    ArrayList<String> relatedList = new ArrayList<>();
                                    for (int i = 0; i < Math.min(relData.length(), 10); i++) {
                                        String rName = relData.getJSONObject(i).optString("name", "");
                                        if (!rName.isEmpty() && !rName.equalsIgnoreCase(artistName)) {
                                            relatedList.add(rName);
                                        }
                                    }
                                    if (!relatedList.isEmpty()) {
                                        AndroidUtilities.runOnUIThread(() -> callback.run(relatedList));
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
                AndroidUtilities.runOnUIThread(() -> callback.run(null));
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null));
            }
        }).start();
    }
    /**
     * Строгий синхронный поиск трека по артисту и названию.
     * Используется SmartRadioEngine для поиска конкретных треков.
     * Параметр flags зарезервирован для будущего использования (передавайте 0).
     */
    public static WebTrack searchTrackStrict(String artist, String title, int flags) {
        try {
            String cleanT = title != null ? stripBrackets(title) : "";
            String cleanA = artist != null ? stripBrackets(artist) : "";
            String query = (cleanA + " " + cleanT).trim();
            String cacheKey = "strict:" + query.toLowerCase();

            ArrayList<WebTrack> cached = getCachedSearch(cacheKey);
            if (cached == null) {
                String html = fetchHtml("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(query, "UTF-8"));
                if (html == null || html.length() < 500) {
                    try {
                        String altHtml = fetchHtml("https://rus.hitmos.fm/search?q=" + URLEncoder.encode(query, "UTF-8"));
                        if (altHtml != null && altHtml.length() > 500) html = altHtml;
                    } catch (Exception ignored) {}
                }
                cached = new ArrayList<>();
                parseHtmlToResults(html, cached, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                putCachedSearch(cacheKey, cached);
            }
            if (!cached.isEmpty()) {
                WebTrack best = findBestMatch(cached, title, artist, flags, false);
                if (best != null) return best;
            }

            // Резервный поиск с транслитерацией кириллицы
            String translitQuery = (transliterate(cleanA) + " " + transliterate(cleanT)).trim();
            if (!translitQuery.equalsIgnoreCase(query)) {
                String cacheKey2 = "strict:" + translitQuery.toLowerCase();
                ArrayList<WebTrack> cached2 = getCachedSearch(cacheKey2);
                if (cached2 == null) {
                    String html2 = fetchHtml("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(translitQuery, "UTF-8"));
                    if (html2 == null || html2.length() < 500) {
                        try {
                            String altHtml2 = fetchHtml("https://rus.hitmos.fm/search?q=" + URLEncoder.encode(translitQuery, "UTF-8"));
                            if (altHtml2 != null && altHtml2.length() > 500) html2 = altHtml2;
                        } catch (Exception ignored) {}
                    }
                    cached2 = new ArrayList<>();
                    parseHtmlToResults(html2, cached2, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                    putCachedSearch(cacheKey2, cached2);
                }
                if (!cached2.isEmpty()) {
                    WebTrack best = findBestMatch(cached2, title, artist, flags, false);
                    if (best != null) return best;
                }
            }

            // Резервный поиск по одному лишь названию трека (на Hitmos часто ищется надежнее)
            if (!cleanT.isEmpty()) {
                String cacheKey3 = "strict:t:" + cleanT.toLowerCase();
                ArrayList<WebTrack> cached3 = getCachedSearch(cacheKey3);
                if (cached3 == null) {
                    String html3 = fetchHtml("https://ru.hitmoz.org/search?q=" + URLEncoder.encode(cleanT, "UTF-8"));
                    if (html3 == null || html3.length() < 500) {
                        try {
                            String altHtml3 = fetchHtml("https://rus.hitmos.fm/search?q=" + URLEncoder.encode(cleanT, "UTF-8"));
                            if (altHtml3 != null && altHtml3.length() > 500) html3 = altHtml3;
                        } catch (Exception ignored) {}
                    }
                    cached3 = new ArrayList<>();
                    parseHtmlToResults(html3, cached3, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
                    putCachedSearch(cacheKey3, cached3);
                }
                if (!cached3.isEmpty()) {
                    WebTrack best = findBestMatch(cached3, title, artist, flags, false);
                    if (best != null) return best;
                }
            }
        } catch (Exception e) { FileLog.e(e); }
        return null;
    }

    private static WebTrack findBestMatch(ArrayList<WebTrack> hitmosResults, String expectedTitle, String expectedArtist, int expectedDuration, boolean strictArtist) {
        String cleanExpectedTitle = stripBrackets(expectedTitle).toLowerCase();
        String cleanExpectedArtist = expectedArtist.toLowerCase().trim();
        String normExpectedTitle = stripNonAlphanumeric(cleanExpectedTitle);
        String normExpectedArtist = stripNonAlphanumeric(cleanExpectedArtist);

        WebTrack best = null;
        int bestScore = -1;

        for (WebTrack wt : hitmosResults) {
            String hitTitle = wt.title.toLowerCase();
            String hitArtist = wt.artist.toLowerCase().trim();
            String normHitTitle = stripNonAlphanumeric(hitTitle);
            String normHitArtist = stripNonAlphanumeric(hitArtist);

            boolean titleMatch = hitTitle.contains(cleanExpectedTitle) || cleanExpectedTitle.contains(hitTitle)
                    || (!normExpectedTitle.isEmpty() && (normHitTitle.contains(normExpectedTitle) || normExpectedTitle.contains(normHitTitle)));

            boolean exactArtistMatch = isArtistMatch(wt.artist, expectedArtist)
                    || hitArtist.equals(cleanExpectedArtist)
                    || (!normExpectedArtist.isEmpty() && normHitArtist.equals(normExpectedArtist));
            boolean artistMatch = exactArtistMatch;
            if (!artistMatch && !cleanExpectedArtist.isEmpty() && !hitArtist.isEmpty()) {
                if (cleanExpectedArtist.length() >= 4 && hitArtist.contains(cleanExpectedArtist)) {
                    artistMatch = true;
                } else if (hitArtist.length() >= 4 && cleanExpectedArtist.contains(hitArtist)) {
                    artistMatch = true;
                }
            }

            if (!cleanExpectedArtist.isEmpty() && !artistMatch) {
                continue;
            }

            if (!titleMatch && !exactArtistMatch) continue;

            if (strictArtist && !exactArtistMatch) continue;

            int hitDur = soundgram.music.ChannelMusicFragment.parseDuration(wt.duration);
            int durDiff = expectedDuration > 0 ? Math.abs(hitDur - expectedDuration) : 0;

            int score = 0;
            if (titleMatch) score += 40;
            if (artistMatch) score += 40;
            if (exactArtistMatch) score += 40;

            if (expectedDuration > 0 && durDiff <= 5) score += 40;
            else if (expectedDuration > 0 && durDiff <= 15) score += 20;
            else if (expectedDuration > 0 && durDiff > 30) score -= 10;

            if (score > bestScore) {
                bestScore = score;
                best = wt;
            }
        }

        if (strictArtist && bestScore >= 0) return best;
        if (bestScore >= 35) return best;
        if (best == null && !hitmosResults.isEmpty()) {
            for (WebTrack wt : hitmosResults) {
                if (!cleanExpectedArtist.isEmpty() && !isArtistMatch(wt.artist, expectedArtist)) {
                    continue;
                }
                String hitTitle = wt.title.toLowerCase();
                String normHitTitle = stripNonAlphanumeric(hitTitle);
                if (hitTitle.contains(cleanExpectedTitle) || cleanExpectedTitle.contains(hitTitle)
                        || (!normExpectedTitle.isEmpty() && (normHitTitle.contains(normExpectedTitle) || normExpectedTitle.contains(normHitTitle)))) {
                    return wt;
                }
            }
        }
        return null;
    }

    public static boolean embedId3v2Cover(File mp3File, File imageFile, String title, String artist) {
        if (mp3File == null || !mp3File.exists() || mp3File.length() < 1024) return false;
        try {
            byte[] imageBytes = null;
            String mimeType = "image/jpeg";
            if (imageFile != null && imageFile.exists() && imageFile.length() > 0) {
                imageBytes = new byte[(int) imageFile.length()];
                try (FileInputStream fis = new FileInputStream(imageFile)) {
                    int read = 0;
                    while (read < imageBytes.length) {
                        int r = fis.read(imageBytes, read, imageBytes.length - read);
                        if (r <= 0) break;
                        read += r;
                    }
                }
                if (imageFile.getName().toLowerCase().endsWith(".png")) {
                    mimeType = "image/png";
                }
            }

            ByteArrayOutputStream tagStream = new ByteArrayOutputStream();

            // 1. TIT2 frame (Title)
            if (!android.text.TextUtils.isEmpty(title)) {
                ByteArrayOutputStream frameContent = new ByteArrayOutputStream();
                frameContent.write(1); // UTF-16 with BOM
                frameContent.write(0xFF);
                frameContent.write(0xFE);
                byte[] tBytes = title.getBytes(StandardCharsets.UTF_16LE);
                frameContent.write(tBytes);

                byte[] fData = frameContent.toByteArray();
                tagStream.write("TIT2".getBytes(StandardCharsets.US_ASCII));
                tagStream.write(intTo4Bytes(fData.length));
                tagStream.write(0); // flags
                tagStream.write(0);
                tagStream.write(fData);
            }

            // 2. TPE1 frame (Artist)
            if (!android.text.TextUtils.isEmpty(artist)) {
                ByteArrayOutputStream frameContent = new ByteArrayOutputStream();
                frameContent.write(1); // UTF-16 with BOM
                frameContent.write(0xFF);
                frameContent.write(0xFE);
                byte[] aBytes = artist.getBytes(StandardCharsets.UTF_16LE);
                frameContent.write(aBytes);

                byte[] fData = frameContent.toByteArray();
                tagStream.write("TPE1".getBytes(StandardCharsets.US_ASCII));
                tagStream.write(intTo4Bytes(fData.length));
                tagStream.write(0); // flags
                tagStream.write(0);
                tagStream.write(fData);
            }

            // 3. APIC frame (Attached Picture / Cover Art)
            if (imageBytes != null && imageBytes.length > 0) {
                ByteArrayOutputStream frameContent = new ByteArrayOutputStream();
                frameContent.write(0); // ISO-8859-1 for MIME and description
                frameContent.write(mimeType.getBytes(StandardCharsets.US_ASCII));
                frameContent.write(0); // null terminator for MIME
                frameContent.write(0x03); // Picture type: Cover (front)
                frameContent.write(0); // Description: empty (null-terminated)
                frameContent.write(imageBytes);

                byte[] fData = frameContent.toByteArray();
                tagStream.write("APIC".getBytes(StandardCharsets.US_ASCII));
                tagStream.write(intTo4Bytes(fData.length));
                tagStream.write(0); // flags
                tagStream.write(0);
                tagStream.write(fData);
            }

            byte[] framesData = tagStream.toByteArray();
            if (framesData.length == 0) return false;

            int tagSize = framesData.length;
            byte[] header = new byte[10];
            header[0] = 'I';
            header[1] = 'D';
            header[2] = '3';
            header[3] = 3; // ID3v2.3
            header[4] = 0; // Revision
            header[5] = 0; // Flags
            header[6] = (byte) ((tagSize >> 21) & 0x7F);
            header[7] = (byte) ((tagSize >> 14) & 0x7F);
            header[8] = (byte) ((tagSize >> 7) & 0x7F);
            header[9] = (byte) (tagSize & 0x7F);

            File tempOut = new File(mp3File.getParentFile(), mp3File.getName() + ".tmp_id3");
            try (FileInputStream fis = new FileInputStream(mp3File);
                 FileOutputStream fos = new FileOutputStream(tempOut)) {

                byte[] headCheck = new byte[10];
                int readHead = fis.read(headCheck);
                long skipBytes = 0;
                if (readHead == 10 && headCheck[0] == 'I' && headCheck[1] == 'D' && headCheck[2] == '3') {
                    int oldSize = ((headCheck[6] & 0x7F) << 21) |
                                  ((headCheck[7] & 0x7F) << 14) |
                                  ((headCheck[8] & 0x7F) << 7) |
                                  (headCheck[9] & 0x7F);
                    skipBytes = oldSize;
                } else {
                    fos.write(header);
                    fos.write(framesData);
                    if (readHead > 0) {
                        fos.write(headCheck, 0, readHead);
                    }
                }

                if (skipBytes > 0) {
                    fos.write(header);
                    fos.write(framesData);
                    long skipped = 0;
                    while (skipped < skipBytes) {
                        long s = fis.skip(skipBytes - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                }

                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = fis.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
            }

            if (tempOut.exists() && tempOut.length() > 1024) {
                if (mp3File.delete()) {
                    tempOut.renameTo(mp3File);
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    private static byte[] intTo4Bytes(int value) {
        return new byte[] {
            (byte) ((value >> 24) & 0xFF),
            (byte) ((value >> 16) & 0xFF),
            (byte) ((value >> 8) & 0xFF),
            (byte) (value & 0xFF)
        };
    }

}
