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
    'org/telegram/messenger/FileLog.java': 'package org.telegram.messenger; public class FileLog { public static void e(Object e) {} public static void d(String s) {} }',
    'org/telegram/messenger/Utilities.java': 'package org.telegram.messenger; public class Utilities { public interface Callback<T> {void run(T value);} }',
    'soundgram/music/ChannelMusicFragment.java': 'package soundgram.music; public class ChannelMusicFragment { public static int parseDuration(String s) { try {String[] p=s.split(":"); return Integer.parseInt(p[0])*60+Integer.parseInt(p[1]);} catch(Exception e) {return 0;} } }',
    'soundgram/music/MusicNetworkHelper.java': '''package soundgram.music;
import java.net.*;import java.io.*;
public class MusicNetworkHelper {
 public static int code=200, calls, audiusCalls; public static String body="", hitmosBody=""; public static boolean fail;
 public static boolean disconnected; public static URL requested;
 public static boolean routeByHost; public static java.util.concurrent.CountDownLatch audiusGate;
 public static HttpURLConnection openConnection(String u) throws IOException {return openConnection(new URL(u));}
 public static HttpURLConnection openConnection(URL u) throws IOException {
  calls++; if(u.getHost().equals("api.audius.co")) audiusCalls++;
  requested=u;disconnected=false;
  if(fail) throw new java.net.SocketTimeoutException();
  return new HttpURLConnection(u) {
   public int getResponseCode() {
    if(routeByHost && u.getHost().equals("api.audius.co") && audiusGate!=null) {
     try { if(!audiusGate.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("gate timeout"); }
     catch(InterruptedException e) {throw new AssertionError(e);}
    }
    return code;
   }
   public InputStream getInputStream(){return new ByteArrayInputStream(
    (routeByHost && !u.getHost().equals("api.audius.co")
     ? (u.getHost().contains("hitmo") ? hitmosBody : "{\\"data\\":[]}") : body).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
   public void disconnect(){disconnected=true;} public boolean usingProxy(){return false;}
   public void connect(){}
  };
 }
}'''
}


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
                        str(ROOT / 'tests/music/MusicSourcesTest.java')], check=True)
        subprocess.run(['java', '-cp', f'{work}:{jar}', 'soundgram.music.MusicSourcesTest'], check=True, timeout=20)
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
                   'MusicWebSearcher.java', 'ChannelMusicFragment.java', 'wave/WaveQueueManager.java']]
        callers.append(ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/Components/AudioPlayerAlert.java')
        subprocess.run(['java', '-cp', str(work), 'Parse', *map(str, callers)], check=True)


if __name__ == '__main__':
    main()
