package soundgram.music;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import soundgram.music.wave.InnerTubeClient;

public final class YouTubeMusicTest {
    private static final String ID = "abcdefghijk";
    private static int checks;
    private static void check(boolean value) {
        if (!value) throw new AssertionError("YouTube check " + (checks + 1));
        checks++;
    }
    private static JSONObject format(long now) {
        return new JSONObject().put("itag", 140).put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                .put("bitrate", 128000).put("contentLength", "3000000").put("approxDurationMs", "200000")
                .put("url", "https://r1.example.googlevideo.com/videoplayback?expire=" + (now / 1000 + 3600) + "&sig=already-signed");
    }
    private static JSONObject player(long now) {
        return new JSONObject().put("playabilityStatus", new JSONObject().put("status", "OK"))
                .put("videoDetails", new JSONObject().put("videoId", ID).put("lengthSeconds", "200")
                        .put("title", "Song").put("author", "Artist").put("isLiveContent", false))
                .put("streamingData", new JSONObject().put("expiresInSeconds", "3600")
                        .put("adaptiveFormats", new JSONArray().put(format(now))));
    }
    private static YouTubeMusicClient.Stream parse(JSONObject root, long now) {
        return YouTubeMusicClient.parsePlayer(root.toString(), ID, now);
    }
    private static JSONObject withFormat(JSONObject f, long now) {
        JSONObject root = player(now);
        root.getJSONObject("streamingData").put("adaptiveFormats", new JSONArray().put(f));
        return root;
    }
    public static void main(String[] args) throws Exception {
        long now = System.currentTimeMillis();
        YouTubeMusicClient.Stream stream = parse(player(now), now);
        check(stream != null && stream.duration == 200 && stream.videoId.equals(ID));
        check(stream.headers.get("User-Agent").equals(InnerTubeClient.AUDIO_USER_AGENT));
        check(stream.headers.get("Referer").equals("https://music.youtube.com/"));
        check(!stream.headers.containsKey("Cookie") && !stream.headers.containsKey("Authorization"));
        String ref = YouTubeMusicClient.reference(ID);
        check(YouTubeMusicClient.videoId(ref).equals(ID));
        check(!ref.equals(stream.url) && !ref.contains("expire"));
        check(YouTubeMusicClient.reference("bad") == null);
        check(!YouTubeMusicClient.isReference(ref + "&v=another"));
        check(!YouTubeMusicClient.isReference("https://music.youtube.com.attacker.example/watch?v=" + ID));
        for (String status : new String[]{"UNPLAYABLE", "LOGIN_REQUIRED", "ERROR", "LIVE_STREAM_OFFLINE", "CONTENT_CHECK_REQUIRED"}) {
            JSONObject root = player(now); root.getJSONObject("playabilityStatus").put("status", status);
            check(parse(root, now) == null);
        }
        JSONObject root = player(now); root.getJSONObject("videoDetails").put("videoId", "otherid1234");
        check(parse(root, now) == null);
        root = player(now); root.getJSONObject("videoDetails").put("isLiveContent", true);
        check(parse(root, now) == null);
        root = player(now); root.getJSONObject("streamingData").put("licenseInfos", new JSONArray().put(new JSONObject()));
        check(parse(root, now) == null);
        for (String field : new String[]{"signatureCipher", "cipher", "drmTrackType"}) {
            check(parse(withFormat(format(now).put(field, "protected"), now), now) == null);
        }
        check(parse(withFormat(format(now).put("approxDurationMs", "30000"), now), now) == null);
        check(parse(withFormat(format(now).put("width", 1920), now), now) == null);
        check(parse(withFormat(format(now).put("contentLength", "0"), now), now) == null);
        check(parse(withFormat(format(now).put("audioTrack", new JSONObject().put("audioIsDefault", false)), now), now) == null);
        for (String mime : new String[]{"video/mp4; codecs=\"avc1\"", "audio/webm; codecs=\"opus\"", "audio/mp4", "audio/mp4; codecs=\"mp4a.40.5\""}) {
            check(parse(withFormat(format(now).put("mimeType", mime), now), now) == null);
        }
        String direct = format(now).getString("url");
        for (String bad : new String[]{direct + "&n=challenge", direct + "&s=cipher", direct + "&pot=token",
                direct.replace("https://", "http://"), direct.replace(".googlevideo.com", ".googlevideo.com.attacker.example"),
                direct + "#fragment", direct + "&expire=1", "https://r.googlevideo.com/videoplayback"}) {
            check(parse(withFormat(format(now).put("url", bad), now), now) == null);
        }
        root = player(now); root.getJSONObject("streamingData").put("expiresInSeconds", "20");
        check(parse(root, now) == null);
        root = player(now); root.getJSONObject("streamingData").put("expiresInSeconds", "60");
        check(parse(root, now).expiresAtMs == now + 60000);
        root = player(now); root.getJSONObject("streamingData").remove("adaptiveFormats");
        root.getJSONObject("streamingData").put("serverAbrStreamingUrl", "https://r.googlevideo.com/sabr");
        check(parse(root, now) == null);
        check(YouTubeMusicClient.parsePlayer("bad-json", ID, now) == null);
        JSONObject high = format(now).put("bitrate", 192000).put("url", direct + "&id=high");
        root = player(now); root.getJSONObject("streamingData").getJSONArray("adaptiveFormats").put(high);
        check(parse(root, now).url.contains("id=high"));
        AtomicLong clock = new AtomicLong(now);
        AtomicInteger loads = new AtomicInteger();
        YouTubeMusicClient.Cache cache = new YouTubeMusicClient.Cache(id -> {loads.incrementAndGet(); return player(clock.get()).toString();}, clock::get);
        check(cache.resolve(ID) != null && loads.get() == 1);
        check(cache.resolve(ID) != null && loads.get() == 1);
        clock.addAndGet(3600_000);
        check(cache.resolve(ID) != null && loads.get() == 2);
        YouTubeMusicClient.Cache failing = new YouTubeMusicClient.Cache(id -> "{}", clock::get);
        check(failing.resolve(ID) == null);
        check(failing.resolve("bad") == null);
        // Existing Media3 adapter: range, key and identity survive, stale headers do not.
        DataSpec original = new DataSpec.Builder().setUri(ref).setPosition(1024).setLength(4096).setKey(ID)
                .setHttpRequestHeaders(java.util.Map.of("Authorization", "must-not-forward")).build();
        DataSpec adapted = YouTubeMusicDataSource.resolveRequest(original, stream);
        check(adapted.position == 1024 && adapted.length == 4096 && adapted.key.equals(ID));
        check(adapted.uri.toString().equals(stream.url));
        check(adapted.httpRequestHeaders.equals(stream.headers));
        check(!adapted.httpRequestHeaders.containsKey("Authorization"));
        check(YouTubeMusicDataSource.resolveRequest(original, stream, stream).position == 1024);
        YouTubeMusicClient.Stream changed = parse(withFormat(format(now).put("itag", 141), now), now);
        try {YouTubeMusicDataSource.resolveRequest(original, changed, stream); throw new AssertionError();}
        catch (java.io.IOException expected) {check(true);}
        changed = parse(withFormat(format(now).put("contentLength", "3000001"), now), now);
        try {YouTubeMusicDataSource.resolveRequest(original, changed, stream); throw new AssertionError();}
        catch (java.io.IOException expected) {check(true);}

        DataSpec legacy = new DataSpec.Builder().setUri("https://hitmos.example/song.mp3").build();
        check(YouTubeMusicDataSource.resolveRequest(legacy, null) == legacy);
        try {YouTubeMusicDataSource.resolveRequest(original, null); throw new AssertionError();}
        catch (java.io.IOException expected) {check(true);}
        // Real InnerTube POST construction and source selection, using test network responses.
        MusicNetworkHelper.routeByHost = true;
        MusicNetworkHelper.playerBody = player(now).toString();
        check(!InnerTubeClient.requestAudioPlayer(ID).isEmpty());
        check(new JSONObject(MusicNetworkHelper.requestBody).getString("videoId").equals(ID));
        check(new JSONObject(MusicNetworkHelper.requestBody).getJSONObject("context").getJSONObject("client").getString("clientName").equals("ANDROID_VR"));
        check(!MusicNetworkHelper.requestHeaders.containsKey("Cookie"));
        check(MusicNetworkHelper.requestHeaders.get("User-Agent").get(0).equals(InnerTubeClient.AUDIO_USER_AGENT));
        for (int code : new int[]{401,403,404,429,500}) {
            MusicNetworkHelper.youtubeCode = code;
            check(InnerTubeClient.requestAudioPlayer(ID).isEmpty());
        }
        MusicNetworkHelper.youtubeCode = 200;
        MusicNetworkHelper.fail = true;
        check(InnerTubeClient.requestAudioPlayer(ID).isEmpty());
        MusicNetworkHelper.fail = false;
        InnerTubeClient.RawTrack candidate = new InnerTubeClient.RawTrack(ID, "Song", "Artist", 200);
        MusicWebSearcher.WebTrack track = MusicSourceResolver.resolveYouTubeCandidate(candidate);
        check(track != null && track.downloadUrl.equals(ref));
        check(track.title.equals("Song") && track.duration.equals("3:20"));
        check(MusicSourceResolver.isStreamingOnlyUrl(ref));
        check(MusicSourceResolver.isStreamingOnlyUrl("https://api.audius.co/v1/tracks/Ab123/stream?app_name=Velora"));
        check(!MusicSourceResolver.isStreamingOnlyUrl("https://hitmos.example/song.mp3"));
        check(!MusicWebSearcher.downloadTrackToExactFileSync(ref, new java.io.File("unused.mp3")));
        DataSource source = YouTubeMusicDataSource.factory().createDataSource();
        check(source.open(original) == 100);
        check(DefaultHttpDataSource.opened.position == 1024 && DefaultHttpDataSource.opened.length == 4096);
        check(source.getUri().toString().equals(ref));
        check(source.read(new byte[1], 0, 1) == -1);
        source.close();
        check(DefaultHttpDataSource.closes > 0);
        // Actual existing catalog parser -> adapter -> standalone search result.
        MusicNetworkHelper.searchBody = new JSONObject().put("musicResponsiveListItemRenderer",
                new JSONObject().put("playlistItemData", new JSONObject().put("videoId", ID))
                        .put("flexColumns", new JSONArray()
                                .put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                                        new JSONObject().put("text", new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", "Song"))))))
                                .put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                                        new JSONObject().put("text", new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", "Artist"))))))))
                .toString();
        check(InnerTubeClient.searchSongsForPlayback("Song", 3).size() == 1);
        check(YouTubeMusicClient.search("Song").get(0).downloadUrl.equals(ref));
        // Third-provider matching cannot silently use an unrelated artist or recording.
        check(MusicSourceResolver.resolve("Artist", "Song", 200).downloadUrl.equals(ref));
        check(MusicSourceResolver.resolve("Other", "Song", 200) == null);
        check(MusicSourceResolver.resolve("Artist", "Song (Live)", 200) == null);
        java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Consumer<java.util.ArrayList<MusicWebSearcher.WebTrack>> receive = tracks -> {
            for (MusicWebSearcher.WebTrack result : tracks) {
                if (ref.equals(result.downloadUrl)) delivered.countDown();
            }
        };
        MusicWebSearcher.search("Song", 1, (tracks, artists, albums) -> receive.accept(tracks), receive);
        check(delivered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        MusicNetworkHelper.playerBody = "{}";
        check(MusicSourceResolver.resolveYouTubeCandidate(new InnerTubeClient.RawTrack("00000000000", "Song", "Artist", 200)) == null);
        // Once a cached stream expires, a failed renewal never returns the stale URL.
        AtomicInteger renewal = new AtomicInteger();
        AtomicLong renewalClock = new AtomicLong(now);
        YouTubeMusicClient.Cache expiring = new YouTubeMusicClient.Cache(id ->
                renewal.getAndIncrement() == 0 ? player(now).toString() : "{}", renewalClock::get);
        check(expiring.resolve(ID) != null);
        renewalClock.addAndGet(3600_000);
        check(expiring.resolve(ID) == null && renewal.get() == 2);
        root = player(now).put("drmFamilies", new JSONArray().put("WIDEVINE"));
        check(parse(root, now) == null);
        // Compile and exercise the production SG_STREAM constructor extracted by the host runner.
        java.io.File local = ChannelMusicFragment.getLocalTrackFile("Artist", "Song");
        java.nio.file.Files.write(local.toPath(), new byte[2048]);
        org.telegram.messenger.MessageObject oldTrack = ChannelMusicFragment.createWebMessageObject(
                0, "Song", "Artist", "https://hitmos.example/song.mp3", 200, 42, "");
        check(oldTrack.messageOwner.attachPath.equals(local.getAbsolutePath()));
        check(oldTrack.messageOwner.id == 42 && oldTrack.messageOwner.media.document.id == 42);
        check(oldTrack.messageOwner.message.startsWith("SG_STREAM|||Song|||Artist|||https://hitmos.example/song.mp3|||200|||"));
        check(oldTrack.messageOwner.media.document.mime_type.equals("audio/mp3"));
        org.telegram.messenger.MessageObject ytTrack = ChannelMusicFragment.createWebMessageObject(
                0, "Song", "Artist", ref, 200, -123, "https://images.example/image");
        check(ytTrack.messageOwner.attachPath.equals(ref));
        check(ytTrack.messageOwner.media.document.mime_type.equals("audio/mp4"));
        check(ytTrack.messageOwner.id == -123);
        check(!ytTrack.messageOwner.message.contains("googlevideo.com") && !ytTrack.messageOwner.message.contains("expire="));
        check(ytTrack.messageOwner.message.split("\\|\\|\\|")[3].equals(ref));
        check(ytTrack.messageOwner.media.document.attributes.get(0).duration == 200);
        String audius = "https://api.audius.co/v1/tracks/Ab123/stream?app_name=Velora";
        org.telegram.messenger.MessageObject audiusTrack = ChannelMusicFragment.createWebMessageObject(
                0, "Song", "Artist", audius, 200, -456, "");
        check(audiusTrack.messageOwner.attachPath.equals(audius));
        check(audiusTrack.messageOwner.media.document.mime_type.equals("audio/mp3"));
        System.out.println("YouTube M2 checks: " + checks + " PASS");
        System.exit(0);
    }
}
