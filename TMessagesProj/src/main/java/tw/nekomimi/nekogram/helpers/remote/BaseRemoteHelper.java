package tw.nekomimi.nekogram.helpers.remote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public abstract class BaseRemoteHelper {
    public static final long CHANNEL_METADATA_ID = 0L;
    public static final String CHANNEL_METADATA_NAME = org.telegram.messenger.BuildConfig.VELORA_UPDATE_CHANNEL;

    protected static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoremoteconfig", Activity.MODE_PRIVATE);

    protected MessagesController getMessagesController() {
        return MessagesController.getInstance(UserConfig.selectedAccount);
    }

    protected ConnectionsManager getConnectionsManager() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount);
    }

    protected MessagesStorage getMessagesStorage() {
        return MessagesStorage.getInstance(UserConfig.selectedAccount);
    }

    protected FileLoader getFileLoader() {
        return FileLoader.getInstance(UserConfig.selectedAccount);
    }

    abstract protected void onError(String text, Delegate delegate);

    abstract protected String getTag();

    protected JSONObject getJSON() {
        var tag = getTag();
        var json = preferences.getString(tag, "");
        if (TextUtils.isEmpty(json)) {
            load();
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            FileLog.e(e);
            load();
            return null;
        }
    }

    protected void onLoadSuccess(ArrayList<JSONObject> responses, Delegate delegate) {
        var tag = getTag();
        var json = responses.size() > 0 ? responses.get(0) : null;
        if (json == null) {
            preferences.edit()
                    .remove(tag + "_update_time")
                    .remove(tag)
                    .apply();
        } else {
            preferences.edit()
                    .putLong(tag + "_update_time", System.currentTimeMillis())
                    .putString(tag, json.toString())
                    .apply();
        }
    }

    public static long getResolvedChannelId() {
        return preferences.getLong("resolved_channel_id", CHANNEL_METADATA_ID);
    }

    public static long getResolvedChannelAccessHash() {
        return preferences.getLong("resolved_channel_access_hash", 0L);
    }

    private void onGetMessageSuccess(TLObject response, Delegate delegate) {
        final var res = (TLRPC.messages_Messages) response;
        ArrayList<JSONObject> responses = new ArrayList<>();
        if (res != null && res.messages != null) {
            for (var message : res.messages) {
                if (message == null || message instanceof TLRPC.TL_messageEmpty || TextUtils.isEmpty(message.message)) {
                    continue;
                }
                String msgText = message.message.replace('\u00A0', ' ');
                String tag = "#" + getTag();
                if (!msgText.contains(tag) && !msgText.contains("#updateRelease") && !msgText.contains("#update")) {
                    continue;
                }
                int firstBrace = msgText.indexOf('{');
                int lastBrace = msgText.lastIndexOf('}');
                if (firstBrace != -1 && lastBrace > firstBrace) {
                    try {
                        String jsonStr = msgText.substring(firstBrace, lastBrace + 1);
                        responses.add(new JSONObject(jsonStr));
                    } catch (JSONException e) {
                        FileLog.e(e);
                    }
                }
            }
        }
        onLoadSuccess(responses, delegate);
    }

    public void load() {
        load(false, null);
    }

    public void load(Delegate delegate) {
        load(false, delegate);
    }

    private void load(boolean forceRefreshAccessHash, Delegate delegate) {
        if (TextUtils.isEmpty(CHANNEL_METADATA_NAME)) {
            if (delegate != null) onError("Velora update channel is not configured", delegate);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req1 = new TLRPC.TL_contacts_resolveUsername();
        req1.username = CHANNEL_METADATA_NAME;
        getConnectionsManager().sendRequest(req1, (response1, error1) -> {
            if (error1 != null) {
                if (delegate != null) {
                    onError(error1.text, delegate);
                }
                return;
            }
            if (!(response1 instanceof TLRPC.TL_contacts_resolvedPeer)) {
                if (delegate != null) {
                    onError("Not resolved", delegate);
                }
                return;
            }
            TLRPC.TL_contacts_resolvedPeer resolvedPeer = (TLRPC.TL_contacts_resolvedPeer) response1;
            getMessagesController().putUsers(resolvedPeer.users, false);
            getMessagesController().putChats(resolvedPeer.chats, false);
            getMessagesStorage().putUsersAndChats(resolvedPeer.users, resolvedPeer.chats, false, true);
            if (resolvedPeer.chats == null || resolvedPeer.chats.isEmpty()) {
                if (delegate != null) {
                    onError("Channel not found", delegate);
                }
                return;
            }
            TLRPC.Chat chat = resolvedPeer.chats.get(0);
            preferences.edit()
                    .putLong("resolved_channel_id", chat.id)
                    .putLong("resolved_channel_access_hash", chat.access_hash)
                    .apply();

            TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.limit = 10;
            req.offset_id = 0;
            req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            req.q = "#update";
            req.peer = new TLRPC.TL_inputPeerChannel();
            req.peer.channel_id = chat.id;
            req.peer.access_hash = chat.access_hash;

            getConnectionsManager().sendRequest(req, (response, error) -> {
                if (error == null) {
                    final var res = (TLRPC.messages_Messages) response;
                    if (res != null && res.messages != null && !res.messages.isEmpty()) {
                        onGetMessageSuccess(response, delegate);
                    } else {
                        fetchHistoryFallback(chat, delegate);
                    }
                } else {
                    fetchHistoryFallback(chat, delegate);
                }
            });
        });
    }

    private void fetchHistoryFallback(TLRPC.Chat chat, Delegate delegate) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        TLRPC.TL_inputPeerChannel inputPeer = new TLRPC.TL_inputPeerChannel();
        inputPeer.channel_id = chat.id;
        inputPeer.access_hash = chat.access_hash;
        req.peer = inputPeer;
        req.limit = 20;
        req.offset_id = 0;
        getConnectionsManager().sendRequest(req, (response, error) -> {
            if (error == null) {
                onGetMessageSuccess(response, delegate);
            } else {
                onError(error.text, delegate);
            }
        });
    }

    public interface Delegate {
        void onTLResponse(TLRPC.TL_help_appUpdate res, String error);
    }
}
