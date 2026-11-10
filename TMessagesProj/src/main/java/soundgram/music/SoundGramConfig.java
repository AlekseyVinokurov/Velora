package soundgram.music;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.HashMap;
import java.util.Iterator;

public class SoundGramConfig {
    public static final String KEY = "soundgram_prefs";

    public static String chatListWallPath = "";
    public static String chatListWallVideoPath = "";
    public static String chatVideoPath = "";
    public static float chatListWallDimming = 0.0f;
    // Главный тумблер
    public static boolean stealthMode = true;
    public static boolean zeroChatGloballyEnabled = true;
    public static String activeZeroChatKey = null;
    public static HashMap<String, String> zeroChatKeys = new HashMap<>();
    public static int crossfadeDurationSec = 0; // 0 = disabled, 1..12 seconds

    public static int getCrossfadeDurationMs() {
        return crossfadeDurationSec * 1000;
    }

    public static void setCrossfadeDurationSec(int sec) {
        crossfadeDurationSec = Math.max(0, Math.min(12, sec));
        try {
            ApplicationLoader.applicationContext.getSharedPreferences(KEY, Context.MODE_PRIVATE)
                    .edit()
                    .putInt("crossfade_duration_sec", crossfadeDurationSec)
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void load() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(KEY, Context.MODE_PRIVATE);

        chatListWallPath = prefs.getString("chatlist_path", "");
        chatListWallVideoPath = prefs.getString("chatlist_video_path", "");
        chatVideoPath = prefs.getString("video_path", "");
        chatListWallDimming = prefs.getFloat("chatlist_dimming", 0.0f);
        stealthMode = prefs.getBoolean("stealth_mode", true);
        crossfadeDurationSec = prefs.getInt("crossfade_duration_sec", 0);


        zeroChatGloballyEnabled = prefs.getBoolean("zc_enabled", false);
        try {
            String keysJson = prefs.getString("zc_keys", "{}");
            JSONObject obj = new JSONObject(keysJson);
            Iterator<String> keys = obj.keys();
            zeroChatKeys.clear();
            while (keys.hasNext()) {
                String k = keys.next();
                zeroChatKeys.put(k, obj.getString(k));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }


    }

    public static void save() {
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences(KEY, Context.MODE_PRIVATE).edit();

        editor.putString("chatlist_path", chatListWallPath);
        editor.putString("chatlist_video_path", chatListWallVideoPath);
        editor.putString("video_path", chatVideoPath);
        editor.putFloat("chatlist_dimming", chatListWallDimming);
        editor.putBoolean("stealth_mode", stealthMode);
        editor.putInt("crossfade_duration_sec", crossfadeDurationSec);
        editor.putBoolean("zc_enabled", zeroChatGloballyEnabled);
        try {
            editor.putString("zc_keys", new JSONObject(zeroChatKeys).toString());
        } catch (Exception e) {
            FileLog.e(e);
        }
        editor.apply();
    }

}
