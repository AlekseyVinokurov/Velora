#!/usr/bin/env python3
"""Small host JVM checks. No Android toolchain and no audio/network-provider requests."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'TMessagesProj/src/main/java/soundgram/music'
# Same pinned host org.json artifact as the existing release tests; not an app dependency.
URL = 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar'
SHA256 = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
STUBS = {
    'android/text/TextUtils.java': 'package android.text; public class TextUtils { public static boolean isEmpty(CharSequence v) {return v==null || v.length()==0;} }',
    'org/telegram/messenger/AndroidUtilities.java': 'package org.telegram.messenger; public class AndroidUtilities { public static void runOnUIThread(Runnable r) {r.run();} }',
    'org/telegram/messenger/FileLog.java': 'package org.telegram.messenger; public class FileLog { public static void e(Object e) {} public static void d(String s) {} public static void w(String s) {} }',
    'org/telegram/messenger/Utilities.java': 'package org.telegram.messenger; public class Utilities { public interface Callback<T> {void run(T value);} }',
    'soundgram/music/ChannelMusicFragment.java': 'package soundgram.music; public class ChannelMusicFragment { public static int parseDuration(String s) { try {String[] p=s.split(":"); return Integer.parseInt(p[0])*60+Integer.parseInt(p[1]);} catch(Exception e) {return 0;} } }',
    'soundgram/music/MusicNetworkHelper.java': '''package soundgram.music;
import java.net.*;import java.io.*;
public class MusicNetworkHelper {
 public static int code=200, calls, audiusCalls; public static String body="", hitmosBody=""; public static boolean fail;
 public static boolean disconnected; public static URL requested;
 public static String playerBody="", searchBody=""; public static int youtubeCode=200, youtubeCalls;
 public static String requestBody=""; public static java.util.Map<String,java.util.List<String>> requestHeaders;
 public static boolean routeByHost; public static java.util.concurrent.CountDownLatch audiusGate;
 public static HttpURLConnection openConnection(String u) throws IOException {return openConnection(new URL(u));}
 public static HttpURLConnection openConnection(URL u) throws IOException {
  if(u.getHost().equals("music.youtube.com")) youtubeCalls++; else calls++;
  if(u.getHost().equals("api.audius.co")) audiusCalls++;
  requested=u;disconnected=false;
  if(fail) throw new java.net.SocketTimeoutException();
  return new HttpURLConnection(u) {
   public OutputStream getOutputStream(){return new ByteArrayOutputStream() {
    public void close() throws IOException {requestBody=toString("UTF-8");requestHeaders=getRequestProperties();super.close();}
   };}
   public int getResponseCode() {
    if(u.getHost().equals("music.youtube.com")) return youtubeCode;
    if(routeByHost && u.getHost().equals("api.audius.co") && audiusGate!=null) {
     try { if(!audiusGate.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("gate timeout"); }
     catch(InterruptedException e) {throw new AssertionError(e);}
    }
    return code;
   }
   public InputStream getInputStream(){return new ByteArrayInputStream(
    (u.getHost().equals("music.youtube.com") ? (u.getPath().endsWith("/player") ? playerBody : searchBody) :
     routeByHost && !u.getHost().equals("api.audius.co")
     ? (u.getHost().contains("hitmo") ? hitmosBody : "{\\"data\\":[]}") : body).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
   public void disconnect(){disconnected=true;} public boolean usingProxy(){return false;}
   public void connect(){}
  };
 }
}'''
}

# Minimal host API surfaces for the existing Media3 ResolvingDataSource interaction.
STUBS.update({
 'android/net/Uri.java': 'package android.net; public final class Uri { private final String v; private Uri(String v){this.v=v;} public static Uri parse(String v){return new Uri(v);} public String toString(){return v;} }',
 'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
 'androidx/media3/common/util/UnstableApi.java': 'package androidx.media3.common.util; public @interface UnstableApi {}',
 'com/google/common/base/Preconditions.java': 'package com.google.common.base; public class Preconditions { public static <T>T checkNotNull(T x){if(x==null)throw new NullPointerException(); return x;} }',
 'androidx/media3/datasource/TransferListener.java': 'package androidx.media3.datasource; public interface TransferListener {}',
 'androidx/media3/datasource/DataSource.java': '''package androidx.media3.datasource;
import java.io.*;import java.util.*;import android.net.Uri;
public interface DataSource {
 interface Factory {DataSource createDataSource();}
 void addTransferListener(TransferListener l); long open(DataSpec d) throws IOException;
 int read(byte[] b,int o,int l) throws IOException; Uri getUri(); void close() throws IOException;
 default Map<String,List<String>> getResponseHeaders(){return Collections.emptyMap();}
}''',
 'androidx/media3/datasource/DataSpec.java': '''package androidx.media3.datasource;
import android.net.Uri;import java.util.*;
public class DataSpec {
 public final Uri uri; public final long position,length; public final String key;
 public final Map<String,String> httpRequestHeaders;
 DataSpec(Builder b){uri=b.uri;position=b.position;length=b.length;key=b.key;httpRequestHeaders=b.headers;}
 public Builder buildUpon(){return new Builder().setUri(uri).setPosition(position).setLength(length).setKey(key).setHttpRequestHeaders(httpRequestHeaders);}
 public static class Builder {
  Uri uri;long position,length=-1;String key;Map<String,String> headers=Collections.emptyMap();
  public Builder setUri(String v){uri=Uri.parse(v);return this;} public Builder setUri(Uri v){uri=v;return this;}
  public Builder setPosition(long v){position=v;return this;} public Builder setLength(long v){length=v;return this;}
  public Builder setKey(String v){key=v;return this;} public Builder setHttpRequestHeaders(Map<String,String> v){headers=v;return this;}
  public DataSpec build(){return new DataSpec(this);}
 }
}''',
 'androidx/media3/datasource/DefaultHttpDataSource.java': '''package androidx.media3.datasource;
import android.net.Uri;import java.io.*;
public class DefaultHttpDataSource implements DataSource {
 public static DataSpec opened; public static int closes;
 public static class Factory implements DataSource.Factory {
  public Factory setConnectTimeoutMs(int v){return this;} public Factory setReadTimeoutMs(int v){return this;}
  public Factory setAllowCrossProtocolRedirects(boolean v){return this;}
  public DataSource createDataSource(){return new DefaultHttpDataSource();}
 }
 public void addTransferListener(TransferListener l){} public long open(DataSpec d){opened=d;return 100;}
 public int read(byte[] b,int o,int l){return -1;} public Uri getUri(){return opened.uri;} public void close(){closes++;}
}''',
})
# Exercise the actual object-construction method without compiling the Android screen.
channel_source = (SOURCE / 'ChannelMusicFragment.java').read_text()
method_start = channel_source.index('    public static MessageObject createWebMessageObject(')
method_end = channel_source.index('    private void syncLocalLibraryDownloads()', method_start)
STUBS['soundgram/music/ChannelMusicFragment.java'] = STUBS['soundgram/music/ChannelMusicFragment.java'].replace(
    'package soundgram.music;', 'package soundgram.music; import java.io.File; import org.telegram.messenger.MessageObject; import org.telegram.tgnet.TLRPC;')
STUBS['soundgram/music/ChannelMusicFragment.java'] = STUBS['soundgram/music/ChannelMusicFragment.java'][:-1] + '''
 public static File getMusicStorageDir() {return new File(System.getProperty("velora.music.fixture"));}
 public static File getLocalTrackFile(String artist,String title) {return new File(getMusicStorageDir(),artist+"-"+title+".mp3");}
''' + channel_source[method_start:method_end] + '}'
STUBS.update({
 'org/telegram/messenger/MessageObject.java': '''package org.telegram.messenger;
public class MessageObject {public final org.telegram.tgnet.TLRPC.TL_message messageOwner;
 public MessageObject(int account,org.telegram.tgnet.TLRPC.TL_message m,boolean a,boolean b){messageOwner=m;}}
''',
 'org/telegram/tgnet/TLRPC.java': '''package org.telegram.tgnet;
public class TLRPC {
 public static class TL_message {public int id,date;public long dialog_id;public String message,attachPath;public TL_peerChannel peer_id;public TL_messageMediaDocument media;}
 public static class TL_peerChannel {public long channel_id;}
 public static class TL_messageMediaDocument {public TL_document document;}
 public static class TL_document {public long id,size;public int dc_id;public String mime_type;public java.util.ArrayList<TL_documentAttributeAudio> attributes=new java.util.ArrayList<>();}
 public static class TL_documentAttributeAudio {public String title,performer;public int duration;}
}''',
})
STUBS['soundgram/music/MusicNetworkHelper.java'] = STUBS['soundgram/music/MusicNetworkHelper.java'].replace(
    ' public static HttpURLConnection openConnection(String u)', ' public static void init() {}\n public static HttpURLConnection openConnection(String u)')


def main():
    with tempfile.TemporaryDirectory(prefix='velora-music-') as tmp:
        work = Path(tmp)
        jar = work / 'json.jar'
        jar.write_bytes(urllib.request.urlopen(URL, timeout=30).read())
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == SHA256
        sources = []
        for name, body in STUBS.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body)
            sources.append(path)
        subprocess.run(['javac', '--release', '17', '-cp', str(jar), '-d', str(work),
                        *map(str, sources), str(SOURCE / 'AudiusClient.java'),
                        str(SOURCE / 'MusicSourceResolver.java'), str(SOURCE / 'MusicWebSearcher.java'),
                        str(SOURCE / 'YouTubeMusicClient.java'), str(SOURCE / 'YouTubeMusicDataSource.java'),
                        str(SOURCE / 'wave/InnerTubeClient.java'),
                        str(ROOT / 'TMessagesProj_Modules/media/libraries/datasource/src/main/java/androidx/media3/datasource/ResolvingDataSource.java'),
                        str(ROOT / 'tests/music/YouTubeMusicTest.java'),
                        str(ROOT / 'tests/music/MusicSourcesTest.java')], check=True)
        subprocess.run(['java', '-cp', f'{work}:{jar}', 'soundgram.music.MusicSourcesTest'], check=True, timeout=20)
        subprocess.run(['java', f'-Dvelora.music.fixture={work}', '-cp', f'{work}:{jar}', 'soundgram.music.YouTubeMusicTest'], check=True, timeout=20)
        # Syntax-only parsing of all changed Java files, including large Android callers.
        parser = work / 'Parse.java'
        parser.write_text('''import javax.tools.*;import com.sun.source.util.*;
public class Parse { public static void main(String[] args) throws Exception {
 DiagnosticCollector<JavaFileObject> ds=new DiagnosticCollector<>();
 JavaCompiler c=ToolProvider.getSystemJavaCompiler();
 try(StandardJavaFileManager fm=c.getStandardFileManager(ds,null,null)) {
  JavacTask t=(JavacTask)c.getTask(null,fm,ds,java.util.List.of("-proc:none"),null,fm.getJavaFileObjects(args));
  t.parse();
  for(Diagnostic<?> d:ds.getDiagnostics()) if(d.getKind()==Diagnostic.Kind.ERROR) throw new AssertionError(d);
 }
 System.out.println("Changed Java syntax: PASS");
}}''')
        subprocess.run(['javac', '-d', str(work), str(parser)], check=True)
        callers = [SOURCE / name for name in ['AudiusClient.java', 'MusicSourceResolver.java',
                   'MusicWebSearcher.java', 'ChannelMusicFragment.java', 'wave/WaveQueueManager.java',
                   'YouTubeMusicClient.java', 'YouTubeMusicDataSource.java', 'wave/InnerTubeClient.java']]
        callers.append(ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/Components/AudioPlayerAlert.java')
        callers.append(ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/Components/VideoPlayer.java')
        subprocess.run(['java', '-cp', str(work), 'Parse', *map(str, callers)], check=True)


if __name__ == '__main__':
    main()
