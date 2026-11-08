package soundgram.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import android.widget.Toast;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PublicLibraryManager {

    private static final String PREF_KEY = "soundgram_public_library";
    private static final String KEY_PHANTOM_DOC_ID = "phantom_doc_id_";
    private static final String KEY_PHANTOM_ACCESS_HASH = "phantom_access_hash_";
    private static final String KEY_PHANTOM_USERNAME = "phantom_username_";

    // 1-second silent MP3 raw audio frame payload
    private static final String SILENT_AUDIO_B64 =
            "//sQxAADwAABpAAAACAAADSAAAAETEFNRTMuMTAwVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUzLjEwMFX/+xLEKYPAAAGkAAAAIAAA" +
            "NIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUzLjEwMFX/+xDEU4PAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVTEFNRTMuMTAwVf/7EsR9A8AAAaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVTEFNRTMuMTAwVf/7" +
            "EMSnA8AAAaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVMQU1FMy4xMDBV//sSxNCDwAABpAAAACAAADSA" +
            "AAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVMQU1FMy4xMDBV//sQxNYDwAABpAAAACAAADSAAAAEVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VUxBTUUzLjEwMFX/+xLE1YPAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUzLjEwMFX/+xDE" +
            "1gPAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVTEFNRTMuMTAwVf/7EsTVg8AAAaQAAAAgAAA0gAAA" +
            "BFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVTEFNRTMuMTAwVf/7EMTWA8AAAaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVM" +
            "QU1FMy4xMDBV//sSxNWDwAABpAAAACAAADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVMQU1FMy4xMDBV//sQxNYD" +
            "wAABpAAAACAAADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUzLjEwMFX/+xLE1YPAAAGkAAAAIAAANIAAAARV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVUxBTUUzLjEwMFX/+xDE1gPAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVTEFN" +
            "RTMuMTAwVf/7EsTVg8AAAaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVTEFNRTMuMTAwVf/7EMTWA8AA" +
            "AaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVMQU1FMy4xMDBV//sSxNWDwAABpAAAACAAADSAAAAEVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVMQU1FMy4xMDBV//sQxNYDwAABpAAAACAAADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUz" +
            "LjEwMFX/+xLE1YPAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUzLjEwMFX/+xDE1gPAAAGk" +
            "AAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVTEFNRTMuMTAwVf/7EsTVg8AAAaQAAAAgAAA0gAAABFVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVTEFNRTMuMTAwVf/7EMTWA8AAAaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVMQU1FMy4x" +
            "MDBV//sSxNWDwAABpAAAACAAADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVMQU1FMy4xMDBV//sQxNYDwAABpAAA" +
            "ACAAADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUzLjEwMFX/+xLE1YPAAAGkAAAAIAAANIAAAARVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVUxBTUUzLjEwMFX/+xDE1gPAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVTEFNRTMuMTAw" +
            "Vf/7EsTVg8AAAaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVTEFNRTMuMTAwVf/7EMTWA8AAAaQAAAAg" +
            "AAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVMQU1FMy4xMDBV//sSxNWDwAABpAAAACAAADSAAAAEVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVMQU1FMy4xMDBV//sQxNYDwAABpAAAACAAADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUzLjEwMFX/+x" +
            "LE1YPAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUUzLjEwMFX/+xDE1gPAAAGkAAAAIAAA" +
            "NIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVTEFNRTMuMTAwVf/7EsTVg8AAAaQAAAAgAAA0gAAABFVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVTEFNRTMuMTAwVf/7EMTWA8AAAaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//sSx" +
            "NWDwAABpAAAACAAADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//sQxNYDwAABpAAAACAAADSAAAAE" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+xLE1YPAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVX/+xDE1gPAAAGkAAAAIAAANIAAAARVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7EsTVg8AA" +
            "AaQAAAAgAAA0gAAABFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVQ==";

    public static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_KEY, Context.MODE_PRIVATE);
    }

    public static boolean isLibraryPublic(int account) {
        long userId = ChannelMusicFragment.getValidUserId(account);
        if (userId == 0) userId = UserConfig.getInstance(account).getClientUserId();
        String key = userId != 0 ? ("library_public_" + userId) : ("library_public_acc" + account);
        return getPrefs().getBoolean(key, false);
    }

    public static void setLibraryPublic(int account, boolean value) {
        long userId = ChannelMusicFragment.getValidUserId(account);
        if (userId == 0) userId = UserConfig.getInstance(account).getClientUserId();
        String key = userId != 0 ? ("library_public_" + userId) : ("library_public_acc" + account);
        getPrefs().edit().putBoolean(key, value).apply();
    }

    public static final char ZW_0 = '\u200B'; // Zero-width space
    public static final char ZW_1 = '\u200C'; // Zero-width non-joiner
    public static final String ZW_START = "\u200D\u200C";
    public static final String ZW_END = "\u200C\u200D";

    public static String encodeInvisible(String text) {
        if (TextUtils.isEmpty(text)) return "";
        StringBuilder sb = new StringBuilder(ZW_START);
        for (char c : text.toCharArray()) {
            for (int i = 7; i >= 0; i--) {
                sb.append(((c >> i) & 1) == 1 ? ZW_1 : ZW_0);
            }
        }
        sb.append(ZW_END);
        return sb.toString();
    }

    public static String decodeInvisible(String text) {
        if (text == null) return null;
        int start = text.indexOf(ZW_START);
        int end = text.indexOf(ZW_END, start + ZW_START.length());
        if (start != -1 && end != -1) {
            String bits = text.substring(start + ZW_START.length(), end);
            if (bits.length() % 8 == 0) {
                StringBuilder decoded = new StringBuilder();
                for (int i = 0; i < bits.length(); i += 8) {
                    int c = 0;
                    for (int j = 0; j < 8; j++) {
                        c = (c << 1) | (bits.charAt(i + j) == ZW_1 ? 1 : 0);
                    }
                    decoded.append((char) c);
                }
                String res = decoded.toString();
                if (res.startsWith("@")) res = res.substring(1);
                return res;
            }
        }
        return null;
    }

    /**
     * Extracts channel username referenced in profile music document.
     */
    public static String extractLibraryUsername(TLRPC.Document document) {
        if (document == null) return null;
        String title = MessageObject.getMusicTitle(document, false);
        if (!TextUtils.isEmpty(title)) {
            String cleaned = title.replaceAll("[\u200B\u200C\u200D\uFEFF]", "");
            Matcher m = Pattern.compile("@([a-zA-Z0-9_]{3,32})").matcher(cleaned);
            if (m.find()) return m.group(1);
            String decoded = decodeInvisible(title);
            if (!TextUtils.isEmpty(decoded)) return decoded;
        }
        String author = MessageObject.getMusicAuthor(document, false);
        if (!TextUtils.isEmpty(author)) {
            String cleaned = author.replaceAll("[\u200B\u200C\u200D\uFEFF]", "");
            Matcher m = Pattern.compile("@([a-zA-Z0-9_]{3,32})").matcher(cleaned);
            if (m.find()) return m.group(1);
            String decoded = decodeInvisible(author);
            if (!TextUtils.isEmpty(decoded)) return decoded;
        }
        String fileName = FileLoader.getDocumentFileName(document);
        if (!TextUtils.isEmpty(fileName)) {
            Matcher m = Pattern.compile("@([a-zA-Z0-9_]{3,32})").matcher(fileName);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Checks if a channel's description contains SoundGram statistics.
     */
    public static boolean isLibraryChannel(TLRPC.ChatFull chatFull) {
        if (chatFull == null || TextUtils.isEmpty(chatFull.about)) return false;
        return chatFull.about.contains("[SG_STATS:");
    }

    public static class ChannelMusicStats {
        public long totalSeconds;
        public long trackCount;

        public ChannelMusicStats(long totalSeconds, long trackCount) {
            this.totalSeconds = totalSeconds;
            this.trackCount = trackCount;
        }

        public String formatStats(String listeningTimePrefix) {
            long hours = totalSeconds / 3600;
            long mins = (totalSeconds % 3600) / 60;
            StringBuilder sb = new StringBuilder();
            sb.append(trackCount).append(" ").append(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.trck));
            if (hours > 0) {
                sb.append(" • ").append(hours).append(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.hour)).append(" ").append(mins).append(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.min));
            } else if (mins > 0) {
                sb.append(" • ").append(mins).append(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.min));
            }
            return sb.toString();
        }
    }

    /**
     * Parses tracks and seconds from chatFull description if present.
     */
    public static ChannelMusicStats parseStatsFromDescription(String about) {
        if (TextUtils.isEmpty(about)) return null;
        Pattern pattern = Pattern.compile("\\[SG_STATS:(\\d+):(\\d+)(?::(\\d+))?\\]");
        Matcher matcher = pattern.matcher(about);
        if (matcher.find()) {
            try {
                long seconds = Long.parseLong(matcher.group(1));
                long tracks = Long.parseLong(matcher.group(2));
                return new ChannelMusicStats(seconds, tracks);
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static ChannelMusicStats parseStatsFromDescription(TLRPC.ChatFull chatFull) {
        return parseStatsFromDescription(chatFull != null ? chatFull.about : null);
    }

    /**
     * Generates a 1-second silent MP3 with ID3v2 tags (TIT2 title, TPE1 artist).
     */
    public static boolean createSilentMp3WithTitle(File destFile, String title, String performer) {
        try {
            byte[] audioBytes = Base64.decode(SILENT_AUDIO_B64, Base64.DEFAULT);

            ByteArrayOutputStream id3Frames = new ByteArrayOutputStream();
            writeId3Frame(id3Frames, "TIT2", title != null ? title : "");
            writeId3Frame(id3Frames, "TPE1", performer != null ? performer : "Velora");

            byte[] framesBytes = id3Frames.toByteArray();
            int tagSize = framesBytes.length;

            ByteArrayOutputStream header = new ByteArrayOutputStream();
            header.write("ID3".getBytes(StandardCharsets.US_ASCII));
            header.write(3); // v2.3
            header.write(0);
            header.write(0); // flags

            // 4 bytes syncsafe size
            header.write((tagSize >> 21) & 0x7F);
            header.write((tagSize >> 14) & 0x7F);
            header.write((tagSize >> 7) & 0x7F);
            header.write(tagSize & 0x7F);

            if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
                destFile.getParentFile().mkdirs();
            }

            try (FileOutputStream fos = new FileOutputStream(destFile)) {
                fos.write(header.toByteArray());
                fos.write(framesBytes);
                fos.write(audioBytes);
                fos.flush();
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void writeId3Frame(ByteArrayOutputStream out, String frameId, String text) throws Exception {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + textBytes.length];
        payload[0] = 3; // UTF-8 encoding
        System.arraycopy(textBytes, 0, payload, 1, textBytes.length);

        out.write(frameId.getBytes(StandardCharsets.US_ASCII));
        int size = payload.length;
        out.write((size >> 24) & 0xFF);
        out.write((size >> 16) & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write(size & 0xFF);
        out.write(0);
        out.write(0); // flags
        out.write(payload);
    }

    /**
     * Modifies an MP3 file's ID3v2 TIT2 frame to inject invisible channel username
     * while retaining all other ID3 tags (artist, album, cover art) and raw audio.
     */
    public static boolean writeModifiedMp3(File sourceFile, File destFile, String newTitle) {
        if (sourceFile == null || !sourceFile.exists() || destFile == null) return false;
        try {
            byte[] header = new byte[10];
            int audioOffset = 0;
            byte[] existingFrames = null;

            try (java.io.FileInputStream fis = new java.io.FileInputStream(sourceFile)) {
                int read = fis.read(header);
                if (read == 10 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                    int tagSize = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14) | ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
                    audioOffset = 10 + tagSize;
                    if ((header[5] & 0x10) != 0) {
                        audioOffset += 10;
                    }
                    if (tagSize > 0) {
                        existingFrames = new byte[tagSize];
                        int framesRead = fis.read(existingFrames);
                        if (framesRead < tagSize) {
                            existingFrames = java.util.Arrays.copyOf(existingFrames, Math.max(0, framesRead));
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }

            ByteArrayOutputStream newId3Frames = new ByteArrayOutputStream();
            // Write updated TIT2 frame
            writeId3Frame(newId3Frames, "TIT2", newTitle != null ? newTitle : "");

            // Copy all other frames from existing ID3 (skipping existing TIT2)
            if (existingFrames != null && existingFrames.length > 10) {
                int pos = 0;
                while (pos + 10 <= existingFrames.length) {
                    if (existingFrames[pos] == 0) break; // padding reached
                    String frameId = new String(existingFrames, pos, 4, StandardCharsets.US_ASCII);
                    int frameSize = ((existingFrames[pos + 4] & 0xFF) << 24)
                            | ((existingFrames[pos + 5] & 0xFF) << 16)
                            | ((existingFrames[pos + 6] & 0xFF) << 8)
                            | (existingFrames[pos + 7] & 0xFF);
                    int totalFrameLen = 10 + frameSize;
                    if (pos + totalFrameLen > existingFrames.length || frameSize <= 0) break;

                    if (!"TIT2".equals(frameId)) {
                        newId3Frames.write(existingFrames, pos, totalFrameLen);
                    }
                    pos += totalFrameLen;
                }
            }

            byte[] framesBytes = newId3Frames.toByteArray();
            int tagSize = framesBytes.length;

            ByteArrayOutputStream newHeader = new ByteArrayOutputStream();
            newHeader.write("ID3".getBytes(StandardCharsets.US_ASCII));
            newHeader.write(3); // v2.3
            newHeader.write(0);
            newHeader.write(0); // flags

            // 4 bytes syncsafe size
            newHeader.write((tagSize >> 21) & 0x7F);
            newHeader.write((tagSize >> 14) & 0x7F);
            newHeader.write((tagSize >> 7) & 0x7F);
            newHeader.write(tagSize & 0x7F);

            if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
                destFile.getParentFile().mkdirs();
            }

            try (FileOutputStream fos = new FileOutputStream(destFile);
                 java.io.FileInputStream fis = new java.io.FileInputStream(sourceFile)) {
                fos.write(newHeader.toByteArray());
                fos.write(framesBytes);
                // Skip ID3 header from source to get raw audio
                if (audioOffset > 0) {
                    long skipped = fis.skip(audioOffset);
                    while (skipped < audioOffset && fis.available() > 0) {
                        skipped += fis.skip(audioOffset - skipped);
                    }
                }
                byte[] buffer = new byte[65536];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.flush();
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public interface PublishListener {
        void onProgress(float progress);
        void onDone(boolean success);
    }

    public static void publishLibraryWithTrack(int currentAccount, Context context, PublishListener listener) {
        publishLibraryWithTrack(currentAccount, context, null, listener);
    }

    public static void publishLibraryWithTrack(int currentAccount, Context context, TLRPC.Document docOverride, PublishListener listener) {
        long userId = ChannelMusicFragment.getValidUserId(currentAccount);
        if (userId == 0) userId = UserConfig.getInstance(currentAccount).getClientUserId();
        final long finalUserId = userId;

        long channelId = ChannelMusicFragment.getChannelIdFromFile(currentAccount);
        if (channelId == 0) {
            if (listener != null) listener.onDone(false);
            return;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(channelId);
        String username = chat != null ? ChatObject.getPublicUsername(chat) : null;
        if (TextUtils.isEmpty(username)) {
            if (listener != null) listener.onDone(false);
            return;
        }
        final String finalUsername = username;

        TLRPC.Document targetDoc = docOverride;
        if (targetDoc == null) {
            TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(finalUserId);
            targetDoc = userFull != null ? userFull.saved_music : null;
        }

        if (targetDoc != null) {
            prepareAndSendTrack(currentAccount, context, targetDoc, finalUsername, finalUserId, listener);
        } else {
            ArrayList<MessageObject> myTracks = ChannelMusicFragment.getAudioMessages(currentAccount);
            MessageObject lastTrack = (myTracks != null && !myTracks.isEmpty()) ? myTracks.get(0) : null;
            if (lastTrack == null || lastTrack.getDocument() == null) {
                if (context != null) {
                    Toast.makeText(context, "В вашей библиотеке нет треков", Toast.LENGTH_SHORT).show();
                }
                if (listener != null) listener.onDone(false);
                return;
            }

            prepareAndSendTrack(currentAccount, context, lastTrack.getDocument(), finalUsername, finalUserId, listener);
        }
    }

    public static void publishLibraryWithTrack(int currentAccount, Context context, Utilities.Callback<Boolean> callback) {
        publishLibraryWithTrack(currentAccount, context, new PublishListener() {
            @Override
            public void onProgress(float progress) {}

            @Override
            public void onDone(boolean success) {
                if (callback != null) callback.run(success);
            }
        });
    }

    private static void prepareAndSendTrack(int currentAccount, Context context, TLRPC.Document doc, String username, long userId, PublishListener listener) {
        File cacheDir = ApplicationLoader.applicationContext != null ? ApplicationLoader.applicationContext.getCacheDir() : null;
        if (cacheDir == null) {
            if (listener != null) listener.onDone(false);
            return;
        }

        File localFile = FileLoader.getInstance(currentAccount).getPathToAttach(doc, true);
        if (localFile != null && localFile.exists() && localFile.length() > 0) {
            modifyAndUploadTrack(currentAccount, localFile, doc, username, userId, listener);
            return;
        }

        NotificationCenter.NotificationCenterDelegate loadDelegate = new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int account, Object... args) {
                if (id == NotificationCenter.fileLoaded && account == currentAccount) {
                    String fileName = (String) args[0];
                    String attachFileName = FileLoader.getAttachFileName(doc);
                    if (attachFileName != null && attachFileName.equals(fileName)) {
                        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
                        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadFailed);
                        File downloaded = (File) args[1];
                        if (downloaded == null || !downloaded.exists()) {
                            downloaded = FileLoader.getInstance(currentAccount).getPathToAttach(doc, true);
                        }
                        modifyAndUploadTrack(currentAccount, downloaded, doc, username, userId, listener);
                    }
                } else if (id == NotificationCenter.fileLoadFailed && account == currentAccount) {
                    String fileName = (String) args[0];
                    String attachFileName = FileLoader.getAttachFileName(doc);
                    if (attachFileName != null && attachFileName.equals(fileName)) {
                        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
                        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadFailed);
                        if (listener != null) listener.onDone(false);
                    }
                }
            }
        };

        NotificationCenter.getInstance(currentAccount).addObserver(loadDelegate, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(loadDelegate, NotificationCenter.fileLoadFailed);
        FileLoader.getInstance(currentAccount).loadFile(doc, null, 0, 0);
    }

    private static void modifyAndUploadTrack(int currentAccount, File sourceFile, TLRPC.Document doc, String username, long userId, PublishListener listener) {
        File cacheDir = ApplicationLoader.applicationContext.getCacheDir();
        File destFile = new File(cacheDir, "sg_phantom_pinned_" + userId + ".mp3");
        if (destFile.exists()) {
            destFile.delete();
        }

        String origTitle = MessageObject.getMusicTitle(doc, false);
        if (TextUtils.isEmpty(origTitle)) origTitle = "Audio";
        origTitle = origTitle.replaceAll("[\u200B\u200C\u200D\uFEFF]", "").trim();
        int pipeIdx = origTitle.lastIndexOf(" | @");
        if (pipeIdx != -1) {
            origTitle = origTitle.substring(0, pipeIdx).trim();
        } else {
            int atIdx = origTitle.lastIndexOf(" @");
            if (atIdx != -1 && atIdx > 0) {
                origTitle = origTitle.substring(0, atIdx).trim();
            }
        }
        if (TextUtils.isEmpty(origTitle)) origTitle = "Audio";

        String cleanUsername = username.startsWith("@") ? username.substring(1) : username;
        String newTitle = origTitle + " | @" + cleanUsername;

        boolean written = writeModifiedMp3(sourceFile, destFile, newTitle);
        if (!written || !destFile.exists() || destFile.length() == 0) {
            FileLog.e("modifyAndUploadTrack: failed to write modified mp3 to " + destFile.getAbsolutePath());
            if (listener != null) listener.onDone(false);
            return;
        }

        long clientUserId = UserConfig.getInstance(currentAccount).getClientUserId();
        AccountInstance accountInstance = AccountInstance.getInstance(currentAccount);

        final int[] tempLocalId = new int[]{0};
        final boolean[] completed = new boolean[]{false};

        NotificationCenter.NotificationCenterDelegate msgDelegate = new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int account, Object... args) {
                if (account != currentAccount) return;
                if (id == NotificationCenter.didReceiveNewMessages) {
                    long dialogId = (Long) args[0];
                    if (dialogId == clientUserId) {
                        ArrayList<MessageObject> arr = (ArrayList<MessageObject>) args[1];
                        if (arr != null) {
                            for (MessageObject mo : arr) {
                                if (mo != null && mo.isMusic()) {
                                    String t = mo.getMusicTitle(false);
                                    if (t != null && t.contains("@" + cleanUsername)) {
                                        tempLocalId[0] = mo.getId();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } else if (id == NotificationCenter.fileUploadProgressChanged) {
                    String location = (String) args[0];
                    if (location != null && location.equals(destFile.getAbsolutePath())) {
                        long uploadedSize = (Long) args[1];
                        long totalSize = (Long) args[2];
                        if (totalSize > 0) {
                            float progress = uploadedSize / (float) totalSize;
                            if (listener != null) {
                                AndroidUtilities.runOnUIThread(() -> listener.onProgress(progress));
                            }
                        }
                    }
                } else if (id == NotificationCenter.messageReceivedByServer) {
                    int oldId = (Integer) args[0];
                    int newId = (Integer) args[1];
                    long dialogId = (Long) args[3];
                    TLRPC.Message msg = (args[2] instanceof TLRPC.Message) ? (TLRPC.Message) args[2] : null;

                    boolean match = false;
                    if (tempLocalId[0] != 0 && oldId == tempLocalId[0]) {
                        match = true;
                    } else if (dialogId == clientUserId && msg != null && msg.media instanceof TLRPC.TL_messageMediaDocument) {
                        TLRPC.Document docCandidate = msg.media.document;
                        String t = MessageObject.getMusicTitle(docCandidate, false);
                        if (t != null && t.contains("@" + cleanUsername)) {
                            match = true;
                        }
                    }

                    if (match && !completed[0]) {
                        completed[0] = true;
                        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
                        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageReceivedByServer);
                        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageSendError);
                        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploadProgressChanged);

                        if (listener != null) {
                            AndroidUtilities.runOnUIThread(() -> listener.onProgress(1.0f));
                        }

                        TLRPC.Document serverDoc = null;
                        if (msg != null && msg.media instanceof TLRPC.TL_messageMediaDocument) {
                            serverDoc = msg.media.document;
                        }
                        if (serverDoc == null) {
                            MessageObject mo = MessagesController.getInstance(currentAccount).dialogMessagesByIds.get(newId);
                            if (mo != null) {
                                serverDoc = mo.getDocument();
                            }
                        }

                        if (serverDoc != null) {
                            final TLRPC.Document finalDoc = serverDoc;
                            saveDocumentToProfile(currentAccount, finalDoc, true, success -> {
                                if (success) {
                                    setLibraryPublic(currentAccount, true);
                                    getPrefs().edit()
                                            .putLong(KEY_PHANTOM_DOC_ID + userId, finalDoc.id)
                                            .putLong(KEY_PHANTOM_ACCESS_HASH + userId, finalDoc.access_hash)
                                            .putString(KEY_PHANTOM_USERNAME + userId, "@" + cleanUsername)
                                            .apply();

                                    // Delete uploaded track from Saved Messages
                                    ArrayList<Integer> markAsDelete = new ArrayList<>();
                                    markAsDelete.add(newId);
                                    MessagesController.getInstance(currentAccount).deleteMessages(markAsDelete, null, null, clientUserId, 0, true, 0);

                                    if (destFile.exists()) destFile.delete();
                                }
                                if (listener != null) listener.onDone(success);
                            });
                        } else {
                            if (destFile.exists()) destFile.delete();
                            if (listener != null) listener.onDone(false);
                        }
                    }
                } else if (id == NotificationCenter.messageSendError) {
                    int failedId = (Integer) args[0];
                    if (tempLocalId[0] != 0 && failedId == tempLocalId[0]) {
                        if (!completed[0]) {
                            completed[0] = true;
                            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
                            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageReceivedByServer);
                            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageSendError);
                            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploadProgressChanged);
                            if (destFile.exists()) destFile.delete();
                            if (listener != null) listener.onDone(false);
                        }
                    }
                }
            }
        };

        NotificationCenter.getInstance(currentAccount).addObserver(msgDelegate, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(currentAccount).addObserver(msgDelegate, NotificationCenter.messageReceivedByServer);
        NotificationCenter.getInstance(currentAccount).addObserver(msgDelegate, NotificationCenter.messageSendError);
        NotificationCenter.getInstance(currentAccount).addObserver(msgDelegate, NotificationCenter.fileUploadProgressChanged);

        AndroidUtilities.runOnUIThread(() -> {
            if (!completed[0]) {
                completed[0] = true;
                NotificationCenter.getInstance(currentAccount).removeObserver(msgDelegate, NotificationCenter.didReceiveNewMessages);
                NotificationCenter.getInstance(currentAccount).removeObserver(msgDelegate, NotificationCenter.messageReceivedByServer);
                NotificationCenter.getInstance(currentAccount).removeObserver(msgDelegate, NotificationCenter.messageSendError);
                NotificationCenter.getInstance(currentAccount).removeObserver(msgDelegate, NotificationCenter.fileUploadProgressChanged);
                if (destFile.exists()) destFile.delete();
                if (listener != null) listener.onDone(false);
            }
        }, 60000);

        // Send modified audio to Saved Messages (dialogId = clientUserId)
        SendMessagesHelper.prepareSendingDocument(
                accountInstance,
                destFile.getAbsolutePath(),
                destFile.getAbsolutePath(),
                null,
                "",
                "audio/mpeg",
                clientUserId,
                null, null, null, null, null, false, 0, null, null, false
        );
    }

    /**
     * Backward-compatible delegation.
     */
    public static void setupPhantomTrack(int currentAccount, long channelId, String username, Utilities.Callback<Boolean> callback) {
        publishLibraryWithTrack(currentAccount, null, callback);
    }

    /**
     * Unpins the music track from user profile and disables public library.
     */
    public static void unsavePhantomTrack(int currentAccount) {
        long userId = ChannelMusicFragment.getValidUserId(currentAccount);
        if (userId == 0) {
            userId = UserConfig.getInstance(currentAccount).getClientUserId();
        }
        TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(userId);
        TLRPC.Document targetDoc = null;
        if (userFull != null && userFull.saved_music != null) {
            targetDoc = userFull.saved_music;
        }

        if (targetDoc != null) {
            saveDocumentToProfile(currentAccount, targetDoc, false, null);
        }
        setLibraryPublic(currentAccount, false);
    }

    public static void saveDocumentToProfile(int currentAccount, TLRPC.Document document, boolean save, Utilities.Callback<Boolean> callback) {
        if (document == null) {
            if (callback != null) callback.run(false);
            return;
        }
        TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = !save;
        req.id = new TLRPC.TL_inputDocument();
        req.id.id = document.id;
        req.id.access_hash = document.access_hash;
        req.id.file_reference = document.file_reference;
        if (req.id.file_reference == null) {
            req.id.file_reference = new byte[0];
        }

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
            boolean success = res instanceof TLRPC.TL_boolTrue;
            if (err != null) {
                FileLog.e("saveDocumentToProfile error: " + err.code + " " + err.text);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (success) {
                    long clientUserId = UserConfig.getInstance(currentAccount).getClientUserId();
                    TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(clientUserId);
                    if (userFull != null) {
                        if (save) {
                            userFull.flags2 |= TLObject.FLAG_21;
                            userFull.saved_music = document;
                        } else {
                            userFull.flags2 &= ~TLObject.FLAG_21;
                            userFull.saved_music = null;
                        }
                        MessagesStorage.getInstance(currentAccount).updateUserInfo(userFull, false);
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.profileMusicUpdated, clientUserId);
                    }
                }
                if (callback != null) callback.run(success);
            });
        });
    }

    /**
     * Clones a complete playlist from a foreign channel into the user's personal music channel.
     * All tracks are forwarded into the user's storage channel, generating new message IDs,
     * maintaining the exact original order, and creating/syncing a matching playlist file.
     */
    public static void clonePlaylistToMyLibrary(int currentAccount, long foreignChannelId, String playlistTitle, ArrayList<MessageObject> playlistMessages, Runnable onDone, Utilities.Callback<String> onError) {
        clonePlaylistToMyLibrary(currentAccount, foreignChannelId, playlistTitle, playlistMessages, null, onDone, onError);
    }

    public static void clonePlaylistToMyLibrary(int currentAccount, long foreignChannelId, String playlistTitle, ArrayList<MessageObject> playlistMessages, MessageObject foreignCoverMsg, Runnable onDone, Utilities.Callback<String> onError) {
        long myChannelId = ChannelMusicFragment.getChannelIdFromFile(currentAccount);
        if (myChannelId == 0) {
            if (onError != null) onError.run("No target music channel");
            return;
        }

        TLRPC.InputPeer toPeer = MessagesController.getInstance(currentAccount).getInputPeer(-myChannelId);
        TLRPC.InputPeer fromPeer = MessagesController.getInstance(currentAccount).getInputPeer(-foreignChannelId);
        if (toPeer == null || fromPeer == null) {
            if (onError != null) onError.run("Invalid channel peer");
            return;
        }

        ArrayList<MessageObject> channelTracks = new ArrayList<>();
        for (MessageObject msg : playlistMessages) {
            if (msg.messageOwner != null && msg.messageOwner.message != null && msg.messageOwner.message.startsWith("SG_STREAM|||")) {
                // Stream track
            } else if (msg.getId() > 0) {
                channelTracks.add(msg);
            }
        }

        if (channelTracks.isEmpty()) {
            ArrayList<String> lines = new ArrayList<>();
            for (MessageObject msg : playlistMessages) {
                if (msg.messageOwner != null && msg.messageOwner.message != null && msg.messageOwner.message.startsWith("SG_STREAM|||")) {
                    lines.add(msg.messageOwner.message);
                }
            }
            saveAndSyncClonedPlaylist(currentAccount, myChannelId, playlistTitle, lines, foreignCoverMsg);
            if (onDone != null) onDone.run();
            return;
        }

        TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
        req.from_peer = fromPeer;
        req.to_peer = toPeer;
        req.drop_author = true;

        android.util.LongSparseArray<Integer> randomIdToIndex = new android.util.LongSparseArray<>();
        for (int i = 0; i < channelTracks.size(); i++) {
            MessageObject msg = channelTracks.get(i);
            long rId = Utilities.random.nextLong();
            req.id.add(msg.getId());
            req.random_id.add(rId);
            randomIdToIndex.put(rId, i);
        }

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.Updates)) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (onError != null) onError.run(error != null ? error.text : "Forward failed");
                });
                return;
            }

            TLRPC.Updates updates = (TLRPC.Updates) response;
            int[] orderedNewIds = new int[channelTracks.size()];
            boolean mappedAny = false;
            ArrayList<Integer> fallbackNewIds = new ArrayList<>();

            for (TLRPC.Update u : updates.updates) {
                if (u instanceof TL_update.TL_updateMessageID) {
                    TL_update.TL_updateMessageID updateMsgId = (TL_update.TL_updateMessageID) u;
                    int idx = randomIdToIndex.get(updateMsgId.random_id, -1);
                    if (idx >= 0 && idx < orderedNewIds.length) {
                        orderedNewIds[idx] = updateMsgId.id;
                        mappedAny = true;
                    }
                } else if (u instanceof TL_update.TL_updateNewChannelMessage) {
                    fallbackNewIds.add(((TL_update.TL_updateNewChannelMessage) u).message.id);
                } else if (u instanceof TL_update.TL_updateNewMessage) {
                    fallbackNewIds.add(((TL_update.TL_updateNewMessage) u).message.id);
                }
            }

            if (!mappedAny && !fallbackNewIds.isEmpty()) {
                java.util.Collections.sort(fallbackNewIds);
                for (int i = 0; i < Math.min(orderedNewIds.length, fallbackNewIds.size()); i++) {
                    orderedNewIds[i] = fallbackNewIds.get(i);
                }
            }

            ArrayList<String> newTrackLines = new ArrayList<>();
            int channelTrackIdx = 0;
            for (MessageObject msg : playlistMessages) {
                if (channelTrackIdx < orderedNewIds.length && orderedNewIds[channelTrackIdx] > 0) {
                    newTrackLines.add(String.valueOf(orderedNewIds[channelTrackIdx++]));
                } else if (msg != null && msg.getId() > 0) {
                    newTrackLines.add(String.valueOf(msg.getId()));
                }
            }

            AndroidUtilities.runOnUIThread(() -> {
                saveAndSyncClonedPlaylist(currentAccount, myChannelId, playlistTitle, newTrackLines, foreignCoverMsg);
                if (onDone != null) onDone.run();
            });
        });
    }

    private static void saveAndSyncClonedPlaylist(int currentAccount, long myChannelId, String playlistTitle, ArrayList<String> newTrackLines, MessageObject foreignCoverMsg) {
        File dir = PlaylistSyncManager.getPlaylistsDir(currentAccount);
        if (dir == null) return;
        String safeTitle = playlistTitle != null && !playlistTitle.isEmpty() ? playlistTitle : "Playlist";
        String safeFileName = safeTitle.replaceAll("[/\\\\:*?\"<>|]", "_");
        File pFile = new File(dir, safeFileName + ".txt");
        int counter = 1;
        while (pFile.exists()) {
            PlaylistSyncManager.PlaylistData existing = PlaylistSyncManager.readPlaylistFile(pFile);
            if (existing.isDeleted) break;
            safeFileName = safeTitle + " (" + counter++ + ")";
            pFile = new File(dir, safeFileName + ".txt");
        }

        // Copy playlist cover photo if present
        if (foreignCoverMsg != null && foreignCoverMsg.messageOwner != null && foreignCoverMsg.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto) {
            TLRPC.TL_messageMediaPhoto mediaPhoto = (TLRPC.TL_messageMediaPhoto) foreignCoverMsg.messageOwner.media;
            TLRPC.PhotoSize closest = FileLoader.getClosestPhotoSizeWithSize(mediaPhoto.photo.sizes, 600);
            if (closest != null) {
                File photoFile = FileLoader.getInstance(currentAccount).getPathToAttach(closest, true);
                File localCover = new File(dir, safeFileName + "_cover.jpg");
                if (photoFile != null && photoFile.exists()) {
                    try {
                        AndroidUtilities.copyFile(photoFile, localCover);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        }

        PlaylistSyncManager.PlaylistData pData = new PlaylistSyncManager.PlaylistData();
        pData.title = pFile.getName().replace(".txt", "");
        pData.trackLines.addAll(newTrackLines);
        PlaylistSyncManager.writePlaylistFile(pFile, pData);
        PlaylistSyncManager.syncPlaylistToChannel(currentAccount, myChannelId, pFile);
    }

    /**
     * Forwards an individual track into the user's personal storage channel.
     */
    public static void copyTrackToMyLibrary(int currentAccount, long foreignChannelId, MessageObject msg, Runnable onDone) {
        long myChannelId = ChannelMusicFragment.getChannelIdFromFile(currentAccount);
        if (myChannelId == 0) return;
        if (msg.messageOwner != null && msg.messageOwner.message != null && msg.messageOwner.message.startsWith("SG_STREAM|||")) {
            SendMessagesHelper.getInstance(currentAccount).sendMessage(
                    SendMessagesHelper.SendMessageParams.of(msg.messageOwner.message, -myChannelId, null, null, null, false, null, null, null, true, 0, 0, null, false)
            );
        } else {
            ArrayList<MessageObject> arr = new ArrayList<>();
            arr.add(msg);
            SendMessagesHelper.getInstance(currentAccount).sendMessage(arr, -myChannelId, false, false, true, 0, 0);
        }
        if (onDone != null) onDone.run();
    }
}
