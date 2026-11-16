package soundgram.music;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Small pre-playback extension point; never edits stored tracks or running playback. */
public final class MusicSourceResolver {
    interface Source {
        MusicWebSearcher.WebTrack find();
    }

    private MusicSourceResolver() {}

    public static MusicWebSearcher.WebTrack resolve(String artist, String title, int duration) {
        return choose(() -> {
            MusicWebSearcher.WebTrack track = MusicWebSearcher.searchTrackStrict(artist, title, duration);
            if (track == null || track.downloadUrl == null || track.downloadUrl.isEmpty()) {
                track = MusicWebSearcher.resolveHitmosSync(title, artist);
            }
            return track;
        }, () -> choose(() -> resolveAudius(artist, title, duration),
                () -> resolveYouTubeMatch(artist, title, duration)));
    }

    /** For an existing InnerTube candidate the video ID identifies the recording; no fuzzy substitution. */
    public static MusicWebSearcher.WebTrack resolveYouTubeCandidate(soundgram.music.wave.InnerTubeClient.RawTrack candidate) {
        return YouTubeMusicClient.candidate(candidate);
    }

    private static MusicWebSearcher.WebTrack resolveYouTubeMatch(String artist, String title, int duration) {
        if (duration <= 0 || normalize(artist).isEmpty() || normalize(title).isEmpty()) return null;
        MusicWebSearcher.WebTrack match = null;
        for (MusicWebSearcher.WebTrack track : YouTubeMusicClient.search(artist + " " + title)) {
            int actualDuration = ChannelMusicFragment.parseDuration(track.duration);
            if (!matches(artist, title, duration, track.artist, track.title, actualDuration)) continue;
            if (match != null) return null;
            match = track;
        }
        return match;
    }

    /** M1/M2 stream-only sources must never enter legacy offline/export or name-based matching paths. */
    public static boolean isStreamingOnlyUrl(String value) {
        return AudiusClient.isStreamUrl(value) || YouTubeMusicClient.isReference(value);
    }

    /** Match original metadata, never stripped version/featured-artist qualifiers. */
    public static MusicWebSearcher.WebTrack resolveAudius(String artist, String title, int duration) {
        if (duration <= 0 || normalize(artist).isEmpty() || normalize(title).isEmpty()) return null;
        List<AudiusClient.Track> candidates = AudiusClient.search(artist + " " + title, 1);
        return findMatch(artist, title, duration, candidates);
    }

    static MusicWebSearcher.WebTrack findMatch(String artist, String title, int duration,
                                              List<AudiusClient.Track> candidates) {
        AudiusClient.Track match = null;
        for (AudiusClient.Track candidate : candidates) {
            if (!matches(artist, title, duration, candidate.artist, candidate.title, candidate.duration)) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match == null ? null : toWebTrack(match);
    }

    static MusicWebSearcher.WebTrack choose(Source primary, Source fallback) {
        MusicWebSearcher.WebTrack track = null;
        try { track = primary.find(); } catch (Exception ignored) {}
        if (track != null && track.downloadUrl != null && !track.downloadUrl.isEmpty()) return track;
        try { return fallback.find(); } catch (Exception ignored) { return null; }
    }

    static MusicWebSearcher.WebTrack toWebTrack(AudiusClient.Track track) {
        MusicWebSearcher.WebTrack result = new MusicWebSearcher.WebTrack(track.title, track.artist,
                track.streamUrl, String.format(Locale.US, "%d:%02d", track.duration / 60,
                track.duration % 60), track.artwork);
        // The legacy constructor URL-decodes metadata; Audius JSON is already decoded.
        result.title = track.title;
        result.artist = track.artist;
        return result;
    }

    static boolean matches(String artist, String title, int duration,
                           String otherArtist, String otherTitle, int otherDuration) {
        String normalizedArtist = normalize(artist), normalizedTitle = normalize(title);
        return !normalizedArtist.isEmpty() && !normalizedTitle.isEmpty()
                && normalizedArtist.equals(normalize(otherArtist))
                && normalizedTitle.equals(normalize(otherTitle))
                && duration > 0 && otherDuration > 0
                && Math.abs((long) duration - otherDuration) <= Math.min(3, Math.max(1, duration / 100));
    }

    private static String normalize(String value) {
        // Preserve version qualifiers, punctuation, accents and featured artists.
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC)
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
