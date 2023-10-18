package com.clevertap.android.sdk.response;

import android.content.Context;

import com.clevertap.android.sdk.CleverTapInstanceConfig;
import com.clevertap.android.sdk.Constants;
import com.clevertap.android.sdk.ControllerManager;
import com.clevertap.android.sdk.Logger;
import com.clevertap.android.sdk.Utils;
import com.clevertap.android.sdk.cryption.CryptHandler;
import com.clevertap.android.sdk.inapp.ImpressionStore;
import com.clevertap.android.sdk.inapp.InAppStore;
import com.clevertap.android.sdk.task.CTExecutorFactory;
import com.clevertap.android.sdk.task.Task;

import java.util.List;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

public class InAppResponse extends CleverTapResponseDecorator {

    private final CleverTapResponse cleverTapResponse;
    private final CleverTapInstanceConfig config;
    private final ControllerManager controllerManager;
    private final CryptHandler cryptHandler;
    private final boolean isSendTest;
    private final Logger logger;
    private final InAppStore inAppStore;
    private final ImpressionStore impressionStore;

    public InAppResponse(
            CleverTapResponse cleverTapResponse,
            CleverTapInstanceConfig config,
            ControllerManager controllerManager,
            CryptHandler cryptHandler,
            final boolean isSendTest,
            InAppStore inAppStore,
            ImpressionStore impressionStore
    ) {
        this.cleverTapResponse = cleverTapResponse;
        this.config = config;
        this.cryptHandler = cryptHandler;
        logger = this.config.getLogger();
        this.controllerManager = controllerManager;
        this.isSendTest = isSendTest;
        this.inAppStore = inAppStore;
        this.impressionStore = impressionStore;
    }

    @Override
    public void processResponse(final JSONObject response, final String stringBody, final Context context) {
        try {

            if (config.isAnalyticsOnly()) {
                logger.verbose(config.getAccountId(),
                        "CleverTap instance is configured to analytics only, not processing inapp messages");
                // process metadata response
                cleverTapResponse.processResponse(response, stringBody, context);
                return;
            }

            logger.verbose(config.getAccountId(), "InApp: Processing response");

            //TODO: need to remove below condn?
            if (!response.has("inapp_notifs")) {
                logger.verbose(config.getAccountId(),
                        "InApp: Response JSON object doesn't contain the inapp key, failing");
                // process metadata response
                cleverTapResponse.processResponse(response, stringBody, context);
                return;
            }

            // TODO get all types of inapps from response - ss, cs, applaunched - DONE
            // TODO store the inapps (get the old code and move it to some ***Store class) - DONE
            // TODO save the SS/CS mode from the json response - DONE
            //      add onChanged for this SS/CS mode to handle case when switching from SS/CS to CS/SS or
            //      from none to CS/SS to clear data. - DONE
            // TODO call EvaluationManager.evaluateOnAppLaunchedServerSide(appLaunchedNotifs) - DONE

            JSONArray inappNotifsToDisplay = response.optJSONArray("inapp_notifs");
            if (inappNotifsToDisplay != null && inappNotifsToDisplay.length() > 0) {
                // Send array of "inapp_notifs" to InAppController for display
                displayInApp(inappNotifsToDisplay);
            }

            JSONArray inappNotifsApplaunched = response.optJSONArray("inapp_notifs_applaunched");
            if (inappNotifsApplaunched != null && inappNotifsApplaunched.length() > 0) {
                //inapp_notifs_applaunched is received during SS mode only
                handleAppLaunchServerSide(inappNotifsApplaunched);
            }

            JSONArray inappNotifsClientSide = response.optJSONArray("inapp_notifs_cs");
            if (inappNotifsClientSide != null && inappNotifsClientSide.length() > 0) {
                inAppStore.storeClientSideInApps(inappNotifsClientSide);
            }

            JSONArray inappNotifsServerSide = response.optJSONArray("inapp_notifs_ss");
            if (inappNotifsServerSide != null) {
                inAppStore.storeServerSideInApps(inappNotifsServerSide);
            }

            String inappDeliveryMode = response.optString("inapp_delivery_mode", "");
            if (!inappDeliveryMode.isEmpty()) {
                //TODO: Mode will be received with every request but do we need to persist it?
                inAppStore.setMode(inappDeliveryMode);
            }

            JSONArray inappStaleList = response.optJSONArray("inapp_stale");
            if (inappStaleList != null) {
                clearStaleInAppImpressions(inappStaleList, impressionStore);
            }

            int perSession = 10;
            int perDay = 10;
            if (response.has(Constants.INAPP_MAX_PER_SESSION) && response
                    .get(Constants.INAPP_MAX_PER_SESSION) instanceof Integer) {
                perSession = response.getInt(Constants.INAPP_MAX_PER_SESSION);
            }

            if (response.has("imp") && response.get("imp") instanceof Integer) {
                perDay = response.getInt("imp");
            }

            if (!isSendTest && controllerManager.getInAppFCManager() != null) {
                Logger.v("Updating InAppFC Limits");
                controllerManager.getInAppFCManager().updateLimits(context, perDay, perSession);
                controllerManager.getInAppFCManager().processResponse(context, response);// Handle stale_inapp
            } else {
                logger.verbose(config.getAccountId(),
                        "controllerManager.getInAppFCManager() is NULL, not Updating InAppFC Limits");
            }
        } catch (Throwable t) {
            Logger.v("InAppManager: Failed to parse response", t);
        }

        // process metadata response
        cleverTapResponse.processResponse(response, stringBody, context);

    }

    private void clearStaleInAppImpressions(JSONArray inappStaleList, ImpressionStore impressionStore) {
        //Stale in-app ids used to remove in-app counts from impressionStore
        for (int i = 0; i < inappStaleList.length(); i++) {
            String inappStaleId = inappStaleList.optString(i);
            impressionStore.clear(inappStaleId);
        }
    }

    private void handleAppLaunchServerSide(JSONArray inappNotifsApplaunched) {
        try {
            List<JSONObject> inappNotifsApplaunchedList = Utils.toJSONObjectList(inappNotifsApplaunched);
            //TODO: inject EvaluationManager as a dependency?
//            new EvaluationManager(..)
//                    .evaluateOnAppLaunchedServerSide(inappNotifsApplaunchedList);
        } catch (Throwable e) {
            logger.verbose(config.getAccountId(), "InAppManager: Malformed AppLaunched ServerSide inApps");
            logger.verbose(config.getAccountId(), "InAppManager: Reason: " + e.getMessage(), e);
        }
    }

    private void displayInApp(JSONArray inappNotifsArray) {
        // Fire the first notification, if any
        Task<Void> task = CTExecutorFactory.executors(config)
                .postAsyncSafelyTask(Constants.TAG_FEATURE_IN_APPS);
        task.execute("InAppResponse#processResponse", new Callable<Void>() {
            @Override
            public Void call() {
                //TODO: send inappNotifsArray for display
                controllerManager.getInAppController().addInAppNotificationsToQueue(inappNotifsArray);
                return null;
            }
        });
    }
}
