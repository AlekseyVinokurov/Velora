package soundgram.music;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import java.io.IOException;

/** Just-in-time URI/header adaptation over the existing Media3 HTTP source; no disk cache. */
public final class YouTubeMusicDataSource {
    private YouTubeMusicDataSource() {}

    public static DataSource.Factory factory() {
        MusicNetworkHelper.init();
        DataSource.Factory upstream = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8000).setReadTimeoutMs(12000)
                .setAllowCrossProtocolRedirects(false);
        java.util.concurrent.atomic.AtomicReference<YouTubeMusicClient.Stream> previous =
                new java.util.concurrent.atomic.AtomicReference<>();
        return () -> new ResolvingDataSource(upstream.createDataSource(), new ResolvingDataSource.Resolver() {
            private Uri reference;

            @Override
            public DataSpec resolveDataSpec(DataSpec spec) throws IOException {
                if (!YouTubeMusicClient.isReference(spec.uri.toString())) return spec;
                reference = spec.uri;
                YouTubeMusicClient.Stream stream = YouTubeMusicClient.resolve(YouTubeMusicClient.videoId(spec.uri.toString()));
                DataSpec resolved = resolveRequest(spec, stream, previous.get());
                previous.set(stream);
                return resolved;
            }

            @Override
            public Uri resolveReportedUri(Uri uri) { return reference == null ? uri : reference; }
        });
    }

    static DataSpec resolveRequest(DataSpec original, YouTubeMusicClient.Stream stream) throws IOException {
        return resolveRequest(original, stream, null);
    }

    static DataSpec resolveRequest(DataSpec original, YouTubeMusicClient.Stream stream,
                                   YouTubeMusicClient.Stream previous) throws IOException {
        String videoId = YouTubeMusicClient.videoId(original.uri.toString());
        if (videoId == null) return original;
        if (stream == null || !videoId.equals(stream.videoId)) {
            throw new IOException("Compatible full YouTube audio is unavailable");
        }
        if (previous != null && (!previous.videoId.equals(stream.videoId)
                || previous.itag != stream.itag || previous.contentLength != stream.contentLength)) {
            throw new IOException("YouTube audio format changed during playback");
        }
        // buildUpon retains Range/position/length and the stable key. No cookie/auth propagation.
        return original.buildUpon().setUri(stream.url).setHttpRequestHeaders(stream.headers).build();
    }
}
