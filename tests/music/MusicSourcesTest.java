package soundgram.music;

import java.util.List;
import org.json.JSONObject;
import org.json.JSONArray;

public class MusicSourcesTest {
    private static int checks;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks);
    }
    private static JSONObject track() {
        return new JSONObject().put("id", "Ab123").put("title", "Song (Live)")
                .put("user", new JSONObject().put("name", "Artist"))
                .put("duration", 200).put("is_streamable", true).put("is_stream_gated", false)
                .put("access", new JSONObject().put("stream", true))
                .put("is_available", true).put("artwork", new JSONObject().put("480x480", "https://images.example/cover"));
    }
    private static String response(JSONObject... items) {
        JSONArray data = new JSONArray();
        for (JSONObject item : items) data.put(item);
        return new JSONObject().put("data", data).toString();
    }
    public static void main(String[] args) {
        List<AudiusClient.Track> parsed = AudiusClient.parseSearch(response(track()));
        check(parsed.size() == 1);
        AudiusClient.Track t = parsed.get(0);
        check(t.id.equals("Ab123") && t.title.equals("Song (Live)") && t.artist.equals("Artist") && t.duration == 200);
        check(t.artwork.equals("https://images.example/cover"));
        check(t.streamUrl.equals("https://api.audius.co/v1/tracks/Ab123/stream?app_name=Velora"));
        for (String flag : new String[]{"is_delete", "is_unlisted", "is_private", "is_scheduled_release", "is_stream_gated"}) {
            check(AudiusClient.parseSearch(response(track().put(flag, true))).isEmpty());
        }
        for (String flag : new String[]{"is_streamable", "is_available"}) {
            check(AudiusClient.parseSearch(response(track().put(flag, false))).isEmpty());
        }
        JSONObject missing = track(); missing.remove("is_streamable");
        check(AudiusClient.parseSearch(response(missing)).isEmpty());
        missing = track(); missing.remove("is_stream_gated");
        check(AudiusClient.parseSearch(response(missing)).isEmpty());
        check(AudiusClient.parseSearch(response(track().put("is_streamable", "true"))).isEmpty());
        check(AudiusClient.parseSearch(response(track().put("stream_conditions", new JSONObject().put("tip_user_id", "abc")))).isEmpty());
        check(AudiusClient.parseSearch(response(track().put("access_authorities", new JSONArray().put("wallet")))).isEmpty());
        check(AudiusClient.parseSearch(response(track().put("allowed_api_keys", new JSONArray().put("key")))).isEmpty());
        check(AudiusClient.parseSearch(response(track().put("access", new JSONObject().put("stream", false).put("preview", true)))).isEmpty());
        check(AudiusClient.parseSearch(response(track().put("id", "../bad"))).isEmpty());
        check(AudiusClient.parseSearch(response(track().put("duration", 0))).isEmpty());
        check(AudiusClient.parseSearch(response(track().put("title", "bad|||field"))).isEmpty());
        check(AudiusClient.parseSearch(response(track().put("user", JSONObject.NULL))).isEmpty());
        check(AudiusClient.parseSearch("not-json").isEmpty());
        check(AudiusClient.parseSearch("{}").isEmpty());
        check(AudiusClient.parseSearch(response(track().put("is_stream_gated", true), track())).size() == 1);
        check(MusicSourceResolver.matches("Artist", "Song (Live)", 200, " artist ", "SONG (LIVE)", 201));
        check(!MusicSourceResolver.matches("Artist", "Song", 200, "Other", "Song", 200));
        check(!MusicSourceResolver.matches("Artist", "Song", 200, "Artist", "Song (Live)", 200));
        check(!MusicSourceResolver.matches("Artist", "Song", 200, "Artist feat. Other", "Song", 200));
        check(!MusicSourceResolver.matches("Artist", "Song", 200, "Artist", "Song", 204));
        check(!MusicSourceResolver.matches("Artist", "Song", 0, "Artist", "Song", 200));
        check(!MusicSourceResolver.matches("", "Song", 200, "", "Song", 200));
        check(MusicSourceResolver.findMatch("Artist", "Song (Live)", 200, parsed) != null);
        check(MusicSourceResolver.findMatch("Artist", "Song (Live)", 200,
                AudiusClient.parseSearch(response(track(), track().put("id", "Other")))) == null);
        MusicWebSearcher.WebTrack primary = new MusicWebSearcher.WebTrack("Song", "Artist", "https://hitmos.example/audio", "3:20", "");
        MusicWebSearcher.WebTrack fallback = MusicSourceResolver.toWebTrack(t);
        check(MusicSourceResolver.choose(() -> primary, () -> { throw new AssertionError("Fallback called"); }) == primary);
        check(MusicSourceResolver.choose(() -> null, () -> fallback) == fallback);
        check(MusicSourceResolver.choose(() -> {throw new RuntimeException();}, () -> fallback) == fallback);
        check(MusicSourceResolver.choose(() -> null, () -> {throw new RuntimeException();}) == null);
        check(AudiusClient.isStreamUrl(t.streamUrl));
        check(!AudiusClient.isStreamUrl("https://api.audius.co.attacker.example/v1/tracks/Ab123/stream"));
        MusicNetworkHelper.body = response(track());
        check(AudiusClient.search("Artist + Song", 2).size() == 1);
        check(MusicNetworkHelper.requested.getQuery().contains("offset=20"));
        check(MusicNetworkHelper.requested.getQuery().contains("Artist+%2B+Song"));
        check(MusicNetworkHelper.disconnected);
        for (int code : new int[]{401,403,404,429,500}) {
            MusicNetworkHelper.code = code;
            check(AudiusClient.search("Artist", 1).isEmpty());
            check(MusicNetworkHelper.disconnected);
        }
        MusicNetworkHelper.code = 200;
        MusicNetworkHelper.body = "{";
        check(AudiusClient.search("Artist", 1).isEmpty());
        MusicNetworkHelper.fail = true;
        check(AudiusClient.search("Artist", 1).isEmpty());
        MusicNetworkHelper.fail = false;
        MusicNetworkHelper.body = response(track());
        MusicNetworkHelper.routeByHost = true;
        check(MusicSourceResolver.resolve("Artist", "Song (Live)", 200).downloadUrl.equals(t.streamUrl));
        check(MusicSourceResolver.resolve("Artist", "Song (Live)", 0) == null);
        check(MusicSourceResolver.resolve("Other", "Song (Live)", 200) == null);
        check(MusicSourceResolver.resolve("Artist", "Song", 200) == null);
        // Production search must deliver primary results while Audius is still waiting.
        MusicNetworkHelper.audiusGate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch initial = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch extra = new java.util.concurrent.CountDownLatch(1);
        MusicWebSearcher.search("Artist Song", 1, (tracks, artists, albums) -> {
            check(tracks.isEmpty());
            initial.countDown();
        }, tracks -> {
            check(initial.getCount() == 0);
            check(tracks.size() == 1 && tracks.get(0).downloadUrl.equals(t.streamUrl));
            extra.countDown();
        });
        try {
            check(initial.await(1, java.util.concurrent.TimeUnit.SECONDS));
            check(extra.getCount() == 1);
            MusicNetworkHelper.audiusGate.countDown();
            check(extra.await(1, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException e) {throw new AssertionError(e);}
        MusicNetworkHelper.audiusGate = null;
        MusicNetworkHelper.hitmosBody = "{\"title\":\"Primary Song\",\"artist\":\"Primary Artist\",\"duration\":200,\"play\":\"https://hitmos.example/primary.mp3\"}";
        int beforeAudius = MusicNetworkHelper.audiusCalls;
        MusicWebSearcher.WebTrack selected = MusicSourceResolver.resolve("Primary Artist", "Primary Song", 200);
        check(selected != null && selected.downloadUrl.equals("https://hitmos.example/primary.mp3"));
        check(MusicNetworkHelper.audiusCalls == beforeAudius);
        // Independent Audius uploads with identical names must both survive production search.
        MusicNetworkHelper.hitmosBody = "";
        MusicNetworkHelper.body = response(track(), track().put("id", "AnotherId"));
        java.util.concurrent.CountDownLatch separate = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Consumer<java.util.ArrayList<MusicWebSearcher.WebTrack>> accept = tracks -> {
            if (!tracks.isEmpty()) {
                check(tracks.size() == 2);
                check(!tracks.get(0).downloadUrl.equals(tracks.get(1).downloadUrl));
                separate.countDown();
            }
        };
        MusicWebSearcher.search("separate uploads", 1, (tracks, artists, albums) -> accept.accept(tracks), accept);
        try {check(separate.await(1, java.util.concurrent.TimeUnit.SECONDS));}
        catch (InterruptedException e) {throw new AssertionError(e);}
        // Offline save is rejected before any network operation or file access.
        int beforeDownload = MusicNetworkHelper.calls;
        check(!MusicWebSearcher.downloadTrackToExactFileSync(t.streamUrl, new java.io.File("unused.mp3")));
        check(MusicNetworkHelper.calls == beforeDownload);
        System.out.println("Music source checks: " + checks + " PASS");
        System.exit(0); // Production search executors are intentionally process-scoped.
    }
}
