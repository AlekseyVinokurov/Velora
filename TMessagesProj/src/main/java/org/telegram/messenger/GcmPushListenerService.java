/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class GcmPushListenerService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();
        long time = message.getSentTime();
        if (data.get("p") == null || data.get("p").isEmpty()) {
            PushListenerController.recordPushState("receive", "missing_payload");
            return;
        }
        PushListenerController.recordPushState("receive", "received");
        PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_FIREBASE, data.get("p"), time);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            SharedConfig.pushStringStatus = "__FIREBASE_TOKEN_READY__";
            PushListenerController.recordPushState("token", "ready");
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, token);
        });
    }
}
