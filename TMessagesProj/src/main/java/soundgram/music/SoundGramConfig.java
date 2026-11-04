package soundgram.music;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Scanner;

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
    public static HashMap<Long, String> customUserBadges = new HashMap<>();
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

    public static boolean hasBadge(long id) {
        if (id == 0) return false;
        try {
            for (int i = 0; i < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; i++) {
                if (org.telegram.messenger.UserConfig.getInstance(i).getClientUserId() == id) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        if (customUserBadges.containsKey(id)) return true;
        if (id < -1000000000000L) {
            long rawId = -id - 1000000000000L;
            if (customUserBadges.containsKey(rawId) || customUserBadges.containsKey(-rawId)) return true;
        } else if (id > 0) {
            long channelDialogId = -1000000000000L - id;
            if (customUserBadges.containsKey(channelDialogId) || customUserBadges.containsKey(-id)) return true;
        } else if (id < 0) {
            long rawId = -id;
            long channelDialogId = -1000000000000L - rawId;
            if (customUserBadges.containsKey(rawId) || customUserBadges.containsKey(channelDialogId)) return true;
        }
        return false;
    }

    public static String getBadge(long id) {
        if (id == 0) return null;
        String b = customUserBadges.get(id);
        if (b != null) return b;
        try {
            for (int i = 0; i < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; i++) {
                if (org.telegram.messenger.UserConfig.getInstance(i).getClientUserId() == id) {
                    return "𝄞";
                }
            }
        } catch (Throwable ignored) {}
        if (id < -1000000000000L) {
            long rawId = -id - 1000000000000L;
            b = customUserBadges.get(rawId);
            if (b != null) return b;
            return customUserBadges.get(-rawId);
        } else if (id > 0) {
            long channelDialogId = -1000000000000L - id;
            b = customUserBadges.get(channelDialogId);
            if (b != null) return b;
            return customUserBadges.get(-id);
        } else {
            long rawId = -id;
            b = customUserBadges.get(rawId);
            if (b != null) return b;
            long channelDialogId = -1000000000000L - rawId;
            return customUserBadges.get(channelDialogId);
        }
    }

    public static boolean hasBadgeForChat(TLRPC.Chat chat) {
        if (chat == null) return false;
        long dialogId;
        if (org.telegram.messenger.ChatObject.isChannel(chat)) {
            dialogId = -1000000000000L - chat.id;
        } else {
            dialogId = -chat.id;
        }
        return hasBadge(dialogId) || hasBadge(chat.id) || hasBadge(-chat.id);
    }

    public static boolean hasBadgeForUser(TLRPC.User user) {
        if (user == null) return false;
        return hasBadge(user.id);
    }

    public static boolean hasBadgeForDialog(long dialogId, TLRPC.User user, TLRPC.Chat chat) {
        if (user != null && hasBadgeForUser(user)) return true;
        if (chat != null && hasBadgeForChat(chat)) return true;
        if (dialogId != 0 && hasBadge(dialogId)) return true;
        return false;
    }

    public static class SoundGramBadgeWrapDrawable extends org.telegram.ui.Components.AnimatedEmojiDrawable.WrapSizeDrawable {
        public SoundGramBadgeWrapDrawable(android.graphics.drawable.Drawable drawable) {
            super(drawable, org.telegram.messenger.AndroidUtilities.dp(20), org.telegram.messenger.AndroidUtilities.dp(20));
        }

        @Override
        public void draw(@androidx.annotation.NonNull android.graphics.Canvas canvas) {
            canvas.save();
            canvas.translate(0, org.telegram.messenger.AndroidUtilities.dp(1));
            super.draw(canvas);
            canvas.restore();
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

        try {
            String badgesJson = prefs.getString("custom_user_badges", "{}");
            JSONObject obj = new JSONObject(badgesJson);
            Iterator<String> keys = obj.keys();
            customUserBadges.clear();
            while (keys.hasNext()) {
                String k = keys.next();
                customUserBadges.put(Long.parseLong(k), obj.getString(k));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        fetchCustomBadges();
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

    public static void fetchCustomBadges() {
        if (org.telegram.messenger.BuildConfig.VELORA_BADGES_URL.isEmpty()) return;
        new Thread(() -> {
            try {
                String urlString = org.telegram.messenger.BuildConfig.VELORA_BADGES_URL;
                URL url = new URL(urlString);
                URLConnection connection = url.openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setUseCaches(false);
                connection.setDefaultUseCaches(false);
                connection.setRequestProperty("Cache-Control", "no-cache");

                Scanner s = new Scanner(connection.getInputStream());
                HashMap<Long, String> tempMap = new HashMap<>();
                while (s.hasNextLine()) {
                    String line = s.nextLine().trim();
                    if (line.contains(":")) {
                        String[] parts = line.split(":", 2);
                        String badge = parts[1].trim();
                        String[] ids = parts[0].split(",");
                        for (String idStr : ids) {
                            try { tempMap.put(Long.parseLong(idStr.trim()), badge); } catch (Exception e) {}
                        }
                    }
                }
                s.close();

                AndroidUtilities.runOnUIThread(() -> {
                    customUserBadges.clear();
                    customUserBadges.putAll(tempMap);
                    try {
                        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences(KEY, Context.MODE_PRIVATE).edit();
                        JSONObject obj = new JSONObject();
                        for (HashMap.Entry<Long, String> entry : customUserBadges.entrySet()) {
                            obj.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        editor.putString("custom_user_badges", obj.toString());
                        editor.apply();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                    }
                });
            } catch (Exception e) {
                FileLog.e("SoundGram Error: " + e.getMessage());
            }
        }).start();
    }
}
