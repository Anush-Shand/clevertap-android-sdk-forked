package com.clevertap.android.sdk.pushnotification.fcm;

import static com.clevertap.android.sdk.pushnotification.PushNotificationUtil.buildPushNotificationRenderedListenerKey;
import static com.clevertap.android.sdk.pushnotification.PushNotificationUtil.getAccountIdFromNotificationBundle;
import static com.clevertap.android.sdk.pushnotification.PushNotificationUtil.getPushIdFromNotificationBundle;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;

import com.clevertap.android.sdk.CleverTapAPI;
import com.clevertap.android.sdk.Constants;
import com.clevertap.android.sdk.Logger;
import com.clevertap.android.sdk.Utils;
import com.clevertap.android.sdk.events.EventGroup;
import com.clevertap.android.sdk.interfaces.NotificationRenderedListener;
import com.clevertap.android.sdk.pushnotification.NotificationInfo;
import com.google.firebase.messaging.RemoteMessage;

import java.util.concurrent.TimeUnit;

/**
 * Receiver to receive firebase messaging broadcast directly from OS.
 * This guarantees OS delivered broadcast reaches to Receiver directly instead of FirebaseMessagingService.
 */
public class CTFirebaseMessagingReceiver extends BroadcastReceiver implements NotificationRenderedListener {

    private CountDownTimer countDownTimer;

    private String key = "";

    private boolean isPRFinished;

    private PendingResult pendingResult;

    private static final String TAG = "CTRM";

    private long start;


    /**
     * Callback when notification is rendered by core sdk.
     *
     * @param isRendered true if rendered successfully
     */
    @SuppressLint("RestrictedApi")
    @Override
    public void onNotificationRendered(final boolean isRendered) {
        Logger.v(TAG, "onNotificationRendered() called for key = " + key);
        finishReceiverAndCancelTimer("onNotificationRendered");
    }

    /**
     * This will finish {@link PendingResult} to signal OS that we are done with our
     * work and OS can kill App process.
     *
     * @param from name of the caller
     */
    private void finishReceiverAndCancelTimer(String from) {
        try {
            Logger.v(TAG, "finishCTRMAndCancelTimer() called");

            if (!key.trim().isEmpty())
            {
                CleverTapAPI.removeNotificationRenderedListener(key);
            }

            long end = System.nanoTime();
            Logger.v(TAG,
                    "finishing CTRM in " + TimeUnit.NANOSECONDS.toSeconds(end - start)
                            + " seconds from finishCTRMAndCancelTimer when " + from);
            if (pendingResult != null && !isPRFinished) {
                pendingResult.finish();
                isPRFinished = true;

                // rendered before timer can finish, so cancel now
                if (countDownTimer != null) {
                    countDownTimer.cancel();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @SuppressLint("RestrictedApi")
    @Override
    public void onReceive(Context context, Intent intent) {

        start = System.nanoTime();
        if (context == null || intent == null) {
            return;
        }

        RemoteMessage remoteMessage = new RemoteMessage(intent.getExtras());
        final Bundle messageBundle = new FcmNotificationParser().toBundle(remoteMessage);

        if (messageBundle == null) {
            return;
        }

        /*
          Configurable time, required to render push and send impressions
         */
        long receiverLifeSpan = Long.parseLong(messageBundle.getString("ctrmt", "4500"));


        pendingResult = goAsync();

        Logger.d(TAG, "CTRM received for message");

        NotificationInfo notificationInfo = CleverTapAPI.getNotificationInfo(messageBundle);

        if (notificationInfo.fromCleverTap) {

            final boolean isRenderFallback = Utils.isRenderFallback(remoteMessage, context);
            if (isRenderFallback) {
                key = buildPushNotificationRenderedListenerKey(
                        getAccountIdFromNotificationBundle(messageBundle),
                        getPushIdFromNotificationBundle(messageBundle)
                );
                CleverTapAPI.addNotificationRenderedListener(key, this);

                countDownTimer = new CountDownTimer(receiverLifeSpan, 1000) {
                    @Override
                    public void onFinish() {
                        finishReceiverAndCancelTimer("timer");
                    }

                    @Override
                    public void onTick(final long millisUntilFinished) {
                        // NO-OP
                    }
                };

                countDownTimer.start();

                new Thread(() -> {
                    try {
                        CleverTapAPI cleverTapAPI = CleverTapAPI.getGlobalInstance(context,
                                getAccountIdFromNotificationBundle(
                                        messageBundle));

                        if (cleverTapAPI != null) {
                            cleverTapAPI.getCoreState().getBaseEventQueueManager().
                                    flushQueueSync(context, EventGroup.PUSH_NOTIFICATION_VIEWED, Constants.D_SRC_PI_R);
                        }
                        //We are done flushing events
                    } catch (Exception e) {
                        e.printStackTrace();
                        Logger.v("CTRM", "Failed executing CTRM thread.", e);
                    } finally {
                        finishReceiverAndCancelTimer("flush from CTRM done!");
                    }

                }).start();

            } else {
                Logger.v(TAG, "Notification payload does not have a fallback key.");
                finishReceiverAndCancelTimer("isRenderFallback is false");
            }
        } else {
            Logger.v(TAG, "Notification payload is not from CleverTap.");
            finishReceiverAndCancelTimer("push is not from CleverTap.");
        }

    }

}