package tw.nekomimi.nekogram.helpers.remote;

import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdateHelper extends BaseRemoteHelper {

    public static final int UPDATE_OFF = 0;
    public static final int UPDATE_CHANNEL_RELEASE = 1;
    public static final int UPDATE_CHANNEL_BETA = 2;
    private boolean updateAlways = false;

    public static UpdateHelper getInstance() {
        return InstanceHolder.instance;
    }

    public static void cleanAppUpdate() {
        if (SharedConfig.pendingAppUpdate != null && SharedConfig.pendingAppUpdate.document != null) {
            File path = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(SharedConfig.pendingAppUpdate.document, true);
            if (path != null && path.exists()) {
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        if (!path.delete()) path.deleteOnExit();
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        SharedConfig.pendingAppUpdate = null;
        SharedConfig.saveConfig();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    @Override
    protected void onError(String text, Delegate delegate) {
        if (delegate != null) {
            delegate.onTLResponse(null, text);
        }
    }

    @Override
    protected String getTag() {
        return "update";
    }

    @SuppressWarnings("ConstantConditions")
    private int getPreferredAbiFile(Map<String, Integer> files) {
        if (files == null || files.isEmpty()) return 0;
        if (files.containsKey("uni")) return files.get("uni");
        if (files.containsKey("universal")) return files.get("universal");
        for (String abi : Build.SUPPORTED_ABIS) {
            if (files.containsKey(abi)) {
                return files.get(abi);
            }
        }
        if (files.containsKey("arm64-v8a")) return files.get("arm64-v8a");
        if (files.containsKey("armeabi-v7a")) return files.get("armeabi-v7a");
        return files.values().iterator().next();
    }

    private Map<String, Integer> jsonToMap(JSONObject obj) {
        Map<String, Integer> map = new HashMap<>();
        if (obj == null) return map;
        var keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                map.put(key, obj.getInt(key));
            } catch (JSONException ignored) {
            }
        }
        return map;
    }

    public static int getCurrentVersionCode() {
        try {
            android.content.Context ctx = ApplicationLoader.applicationContext;
            android.content.pm.PackageInfo pInfo = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return (int) androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo);
        } catch (Exception e) {
            return 0;
        }
    }

    public Update lastParsedUpdate;

    private Update getShouldUpdateVersion(List<JSONObject> responses) {
        int currentVersion = getCurrentVersionCode();
        Update ref = null;
        for (var string : responses) {
            try {
                int remoteVersion = string.optInt("version_code", string.optInt("versionCode", 0));
                if (remoteVersion > currentVersion) {
                    if (ref == null || remoteVersion > ref.versionCode) {
                        JSONObject docObj = string.optJSONObject("document");
                        ref = new Update(
                                string.optBoolean("can_not_skip", false),
                                string.optString("version", ""),
                                remoteVersion,
                                string.optInt("sticker", 0),
                                string.optInt("message", 0),
                                docObj != null ? jsonToMap(docObj) : new HashMap<>(),
                                string.optString("url", "")
                        );
                    }
                }
            } catch (Exception ignored) {
            }
        }
        lastParsedUpdate = ref;
        return ref;
    }

    private void getNewVersionMessagesCallback(Delegate delegate, Update json, HashMap<String, Integer> ids, TLObject response) {
        var update = new TLRPC.TL_help_appUpdate();
        update.version = json.version;
        update.can_not_skip = json.canNotSkip != null && json.canNotSkip;
        if (json.url != null && !json.url.isEmpty()) {
            update.url = json.url;
            update.flags |= 4;
        }
        if (response != null) {
            var res = (TLRPC.messages_Messages) response;
            var messages = new HashMap<Integer, TLRPC.Message>();
            if (res.messages != null) {
                for (var message : res.messages) {
                    if (message != null && !(message instanceof TLRPC.TL_messageEmpty)) {
                        messages.put(message.id, message);
                    }
                }
            }
            if (ids != null && ids.containsKey("sticker")) {
                var sticker = messages.get(ids.get("sticker"));
                if (sticker != null && sticker.media != null) {
                    update.sticker = sticker.media.document;
                    update.flags |= 8;
                }
            }
            if (ids != null && ids.containsKey("message")) {
                var message = messages.get(ids.get("message"));
                if (message != null) {
                    update.text = message.message;
                    update.entities = message.entities;
                }
            }
            if (ids != null && ids.containsKey("document")) {
                var file = messages.get(ids.get("document"));
                if (file != null && file.media != null) {
                    update.document = file.media.document;
                    update.flags |= 2;
                }
            }
        }
        if (delegate != null) {
            delegate.onTLResponse(update, null);
        }
    }

    @Override
    protected void onLoadSuccess(ArrayList<JSONObject> responses, Delegate delegate) {
        var update = getShouldUpdateVersion(responses);
        if (update == null) {
            if (delegate != null) {
                delegate.onTLResponse(null, null);
            }
            return;
        }
        var ids = new HashMap<String, Integer>();
        if (update.sticker != null && update.sticker > 0) {
            ids.put("sticker", update.sticker);
        }
        if (update.message != null && update.message > 0) {
            ids.put("message", update.message);
        }
        if (update.document != null && !update.document.isEmpty()) {
            int docId = getPreferredAbiFile(update.document);
            if (docId > 0) {
                ids.put("document", docId);
            }
        }
        if (ids.isEmpty()) {
            getNewVersionMessagesCallback(delegate, update, null, null);
        } else {
            var req = new TLRPC.TL_channels_getMessages();
            long resolvedId = getResolvedChannelId();
            req.channel = getMessagesController().getInputChannel(resolvedId);
            if (req.channel == null || req.channel.access_hash == 0) {
                TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
                inputChannel.channel_id = resolvedId;
                inputChannel.access_hash = getResolvedChannelAccessHash();
                req.channel = inputChannel;
            }
            req.id = new ArrayList<>(ids.values());
            getConnectionsManager().sendRequest(req, (response1, error1) -> {
                if (error1 == null) {
                    getNewVersionMessagesCallback(delegate, update, ids, response1);
                } else {
                    if (delegate != null) {
                        delegate.onTLResponse(null, error1.text);
                    }
                }
            });
        }
    }

    public void checkNewVersionAvailable(Delegate delegate) {
        checkNewVersionAvailable(delegate, false);
    }

    public void checkNewVersionAvailable(Delegate delegate, boolean updateAlways) {
        if (delegate != null) delegate.onTLResponse(null, "Legacy Telegram-channel updates are disabled in Velora");
    }

    private static final class InstanceHolder {
        private static final UpdateHelper instance = new UpdateHelper();
    }

    public static class Update {
        public Boolean canNotSkip;
        public String version;
        public Integer versionCode;
        public Integer sticker;
        public Integer message;
        public Map<String, Integer> document;
        public String url;

        public Update(Boolean canNotSkip, String version, int versionCode, int sticker, int message, Map<String, Integer> document, String url) {
            this.canNotSkip = canNotSkip;
            this.version = version;
            this.versionCode = versionCode;
            this.sticker = sticker;
            this.message = message;
            this.document = document;
            this.url = url;
        }
    }
}
