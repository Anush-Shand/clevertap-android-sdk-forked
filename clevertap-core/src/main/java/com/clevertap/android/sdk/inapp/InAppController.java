package com.clevertap.android.sdk.inapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import com.clevertap.android.sdk.AnalyticsManager;
import com.clevertap.android.sdk.BaseCallbackManager;
import com.clevertap.android.sdk.CleverTapInstanceConfig;
import com.clevertap.android.sdk.Constants;
import com.clevertap.android.sdk.ControllerManager;
import com.clevertap.android.sdk.CoreMetaData;
import com.clevertap.android.sdk.DeviceInfo;
import com.clevertap.android.sdk.InAppNotificationActivity;
import com.clevertap.android.sdk.InAppNotificationListener;
import com.clevertap.android.sdk.Logger;
import com.clevertap.android.sdk.ManifestInfo;
import com.clevertap.android.sdk.PushPermissionNotificationResponseListener;
import com.clevertap.android.sdk.StorageHelper;
import com.clevertap.android.sdk.Utils;
import com.clevertap.android.sdk.task.CTExecutorFactory;
import com.clevertap.android.sdk.task.MainLooperHandler;
import com.clevertap.android.sdk.task.Task;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

public class InAppController implements CTInAppNotification.CTInAppNotificationListener, InAppListener,
        InAppNotificationActivity.PermissionCallback {

    //InApp
    private final class NotificationPrepareRunnable implements Runnable {

        private final WeakReference<InAppController> inAppControllerWeakReference;

        private JSONObject jsonObject;

        private CTHalfInterstitialLocalInAppBuilder halfInterstitialLocalInAppBuilder;
        private CTAlertLocalInAppBuilder alertLocalInAppBuilder;

        private final boolean videoSupport = Utils.haveVideoPlayerSupport;

        NotificationPrepareRunnable(InAppController inAppController, JSONObject jsonObject) {
            this.inAppControllerWeakReference = new WeakReference<>(inAppController);
            this.jsonObject = jsonObject;
        }

        NotificationPrepareRunnable(InAppController inAppController,
                                    CTHalfInterstitialLocalInAppBuilder halfInterstitialLocalInAppBuilder) {
            this.inAppControllerWeakReference = new WeakReference<>(inAppController);
            this.halfInterstitialLocalInAppBuilder = halfInterstitialLocalInAppBuilder;
        }

        NotificationPrepareRunnable(InAppController inAppController,
                                    CTAlertLocalInAppBuilder alertLocalInAppBuilder) {
            this.inAppControllerWeakReference = new WeakReference<>(inAppController);
            this.alertLocalInAppBuilder = alertLocalInAppBuilder;
        }

        @Override
        public void run() {
            final CTInAppNotification inAppNotification;
            if (jsonObject != null) {//If jsonObj is available then render in-app via JSON
                inAppNotification = new CTInAppNotification()
                        .initWithJSON(jsonObject, videoSupport);
            }else {//render in-app via local in-app builder
                inAppNotification = halfInterstitialLocalInAppBuilder != null ? new CTInAppNotification()
                        .configureHalfInterstitialLocalInApp(halfInterstitialLocalInAppBuilder,
                                DeviceInfo.getDeviceType(context)) :
                        new CTInAppNotification()
                        .configureAlertLocalInApp(alertLocalInAppBuilder,
                                DeviceInfo.getDeviceType(context));
            }

            if (inAppNotification.getError() != null) {
                logger
                        .debug(config.getAccountId(),
                                "Unable to parse inapp notification " + inAppNotification.getError());
                return;
            }
            inAppNotification.listener = inAppControllerWeakReference.get();
            inAppNotification.prepareForDisplay();
        }
    }

    private enum InAppState {
        DISCARDED(-1),
        SUSPENDED(0),
        RESUMED(1);

        final int state;

        InAppState(final int inAppState) {
            state = inAppState;
        }

        int intValue() {
            return state;
        }
    }

    private static CTInAppNotification currentlyDisplayingInApp = null;

    private static final List<CTInAppNotification> pendingNotifications = Collections
            .synchronizedList(new ArrayList<CTInAppNotification>());

    private final AnalyticsManager analyticsManager;

    private final BaseCallbackManager callbackManager;

    private final CleverTapInstanceConfig config;

    private final Context context;

    private final ControllerManager controllerManager;

    private final CoreMetaData coreMetaData;

    private InAppState inAppState;

    private HashSet<String> inappActivityExclude = null;

    private final Logger logger;

    private final MainLooperHandler mainLooperHandler;

    public InAppController(Context context,
            CleverTapInstanceConfig config,
            MainLooperHandler mainLooperHandler,
            ControllerManager controllerManager,
            BaseCallbackManager callbackManager,
            AnalyticsManager analyticsManager,
            CoreMetaData coreMetaData) {

        this.context = context;
        this.config = config;
        logger = this.config.getLogger();
        this.mainLooperHandler = mainLooperHandler;
        this.controllerManager = controllerManager;
        this.callbackManager = callbackManager;
        this.analyticsManager = analyticsManager;
        this.coreMetaData = coreMetaData;
        this.inAppState = InAppState.RESUMED;
    }

    public void checkExistingInAppNotifications(Activity activity) {
        final boolean canShow = canShowInAppOnActivity();
        if (canShow) {
            if (currentlyDisplayingInApp != null && ((System.currentTimeMillis() / 1000) < currentlyDisplayingInApp
                    .getTimeToLive())) {
                Fragment inAppFragment = ((FragmentActivity) activity).getSupportFragmentManager()
                        .getFragment(new Bundle(), currentlyDisplayingInApp.getType());
                if (CoreMetaData.getCurrentActivity() != null && inAppFragment != null) {
                    FragmentTransaction fragmentTransaction = ((FragmentActivity) activity)
                            .getSupportFragmentManager()
                            .beginTransaction();
                    Bundle bundle = new Bundle();
                    bundle.putParcelable("inApp", currentlyDisplayingInApp);
                    bundle.putParcelable("config", config);
                    inAppFragment.setArguments(bundle);
                    fragmentTransaction.setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out);
                    fragmentTransaction.add(android.R.id.content, inAppFragment, currentlyDisplayingInApp.getType());
                    Logger.v(config.getAccountId(),
                            "calling InAppFragment " + currentlyDisplayingInApp.getCampaignId());
                    fragmentTransaction.commit();
                }
            }
        }
    }

    public void checkPendingInAppNotifications(Activity activity) {
        final boolean canShow = canShowInAppOnActivity();
        if (canShow) {
            if (mainLooperHandler.getPendingRunnable() != null) {
                logger.verbose(config.getAccountId(), "Found a pending inapp runnable. Scheduling it");
                mainLooperHandler.postDelayed(mainLooperHandler.getPendingRunnable(), 200);
                mainLooperHandler.setPendingRunnable(null);
            } else {
                showNotificationIfAvailable(context);
            }
        } else {
            Logger.d("In-app notifications will not be shown for this activity ("
                    + (activity != null ? activity.getLocalClassName() : "") + ")");
        }
    }

    public void promptPushPrimer(CTHalfInterstitialLocalInAppBuilder halfInterstitialLocalInAppBuilder){
        prepareNotificationForDisplay(halfInterstitialLocalInAppBuilder,null);
    }

    public void promptPushPrimer(CTAlertLocalInAppBuilder alertLocalInAppBuilder){
        prepareNotificationForDisplay(null,alertLocalInAppBuilder);
    }

    public void promptPermission(){
        InAppNotificationActivity.startPrompt(Objects.requireNonNull(CoreMetaData.getCurrentActivity()),
                config);
    }

    public void discardInApps() {
        this.inAppState = InAppState.DISCARDED;
        logger.verbose(config.getAccountId(), "InAppState is DISCARDED");
    }

    @Override
    public void inAppNotificationDidClick(CTInAppNotification inAppNotification, Bundle formData,
            HashMap<String, String> keyValueMap) {
        analyticsManager.pushInAppNotificationStateEvent(true, inAppNotification, formData);
        if (keyValueMap != null && !keyValueMap.isEmpty()) {
            if (callbackManager.getInAppNotificationButtonListener() != null) {
                callbackManager.getInAppNotificationButtonListener().onInAppButtonClick(keyValueMap);
            }
        }
    }

    @Override
    public void inAppNotificationDidDismiss(final Context context, final CTInAppNotification inAppNotification,
            final Bundle formData) {
        inAppNotification.didDismiss();
        if (controllerManager.getInAppFCManager() != null) {
            controllerManager.getInAppFCManager().didDismiss(inAppNotification);
            logger.verbose(config.getAccountId(), "InApp Dismissed: " + inAppNotification.getCampaignId());
        } else {
            logger.verbose(config.getAccountId(), "Not calling InApp Dismissed: " + inAppNotification.getCampaignId()
                    + " because InAppFCManager is null");
        }
        try {
            final InAppNotificationListener listener = callbackManager.getInAppNotificationListener();
            if (listener != null) {
                final HashMap<String, Object> notifKVS;

                if (inAppNotification.getCustomExtras() != null) {
                    notifKVS = Utils.convertJSONObjectToHashMap(inAppNotification.getCustomExtras());
                } else {
                    notifKVS = new HashMap<>();
                }

                Logger.v("Calling the in-app listener on behalf of " + coreMetaData.getSource());

                if (formData != null) {
                    listener.onDismissed(notifKVS, Utils.convertBundleObjectToHashMap(formData));
                } else {
                    listener.onDismissed(notifKVS, null);
                }
            }
        } catch (Throwable t) {
            logger.verbose(config.getAccountId(), "Failed to call the in-app notification listener", t);
        }

        // Fire the next one, if any
        Task<Void> task = CTExecutorFactory.executors(config).postAsyncSafelyTask(Constants.TAG_FEATURE_IN_APPS);
        task.execute("InappController#inAppNotificationDidDismiss", new Callable<Void>() {
            @Override
            public Void call() {
                inAppDidDismiss(context, config, inAppNotification, InAppController.this);
                _showNotificationIfAvailable(context);
                return null;
            }
        });
    }

    //InApp
    @Override
    public void inAppNotificationDidShow(CTInAppNotification inAppNotification, Bundle formData) {
        analyticsManager.pushInAppNotificationStateEvent(false, inAppNotification, formData);

        //Fire onShow() callback when InApp is shown.
        try {
            final InAppNotificationListener listener = callbackManager.getInAppNotificationListener();
            if (listener != null) {
                listener.onShow(inAppNotification);
            }
        } catch (Throwable t) {
            Logger.v(config.getAccountId(), "Failed to call the in-app notification listener", t);
        }
    }

    //InApp
    @Override
    public void notificationReady(final CTInAppNotification inAppNotification) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainLooperHandler.post(new Runnable() {
                @Override
                public void run() {
                    notificationReady(inAppNotification);
                }
            });
            return;
        }

        if (inAppNotification.getError() != null) {
            logger
                    .debug(config.getAccountId(),
                            "Unable to process inapp notification " + inAppNotification.getError());
            return;
        }
        logger.debug(config.getAccountId(), "Notification ready: " + inAppNotification.getJsonDescription());
        displayNotification(inAppNotification);
    }

    @Override
    public void onAccept() {
        final PushPermissionNotificationResponseListener listener = callbackManager.getPushPermissionNotificationResponseListener();
        if (listener != null){
            listener.response(true);
        }
    }

    @Override
    public void onReject() {
        final PushPermissionNotificationResponseListener listener = callbackManager.getPushPermissionNotificationResponseListener();
        if (listener != null){
            listener.response(false);
        }
    }

    public void resumeInApps() {
        this.inAppState = InAppState.RESUMED;
        logger.verbose(config.getAccountId(), "InAppState is RESUMED");
        logger.verbose(config.getAccountId(), "Resuming InApps by calling showInAppNotificationIfAny()");
        showInAppNotificationIfAny();
    }

    //InApp
    public void showNotificationIfAvailable(final Context context) {
        if (!config.isAnalyticsOnly()) {
            Task<Void> task = CTExecutorFactory.executors(config).postAsyncSafelyTask(Constants.TAG_FEATURE_IN_APPS);
            task.execute("InappController#showNotificationIfAvailable", new Callable<Void>() {
                @Override
                public Void call() {
                    _showNotificationIfAvailable(context);
                    return null;
                }
            });
        }
    }

    public void suspendInApps() {
        this.inAppState = InAppState.SUSPENDED;
        logger.verbose(config.getAccountId(), "InAppState is SUSPENDED");
    }

    //InApp
    private void _showNotificationIfAvailable(Context context) {
        SharedPreferences prefs = StorageHelper.getPreferences(context);
        try {
            if (!canShowInAppOnActivity()) {
                Logger.v("Not showing notification on blacklisted activity");
                return;
            }

            if (this.inAppState == InAppState.SUSPENDED) {
                logger.debug(config.getAccountId(),
                        "InApp Notifications are set to be suspended, not showing the InApp Notification");
                return;
            }

            checkPendingNotifications(context,
                    config, this);  // see if we have any pending notifications

            JSONArray inapps = new JSONArray(
                    StorageHelper.getStringFromPrefs(context, config, Constants.INAPP_KEY, "[]"));
            if (inapps.length() < 1) {
                return;
            }

            if (this.inAppState != InAppState.DISCARDED) {
                JSONObject inapp = inapps.getJSONObject(0);
                prepareNotificationForDisplay(inapp);
            } else {
                logger.debug(config.getAccountId(),
                        "InApp Notifications are set to be discarded, dropping the InApp Notification");
            }

            // JSON array doesn't have the feature to remove a single element,
            // so we have to copy over the entire array, but the first element
            JSONArray inappsUpdated = new JSONArray();
            for (int i = 0; i < inapps.length(); i++) {
                if (i == 0) {
                    continue;
                }
                inappsUpdated.put(inapps.get(i));
            }
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(StorageHelper.storageKeyWithSuffix(config, Constants.INAPP_KEY),
                            inappsUpdated.toString());
            StorageHelper.persist(editor);
        } catch (Throwable t) {
            // We won't get here
            logger.verbose(config.getAccountId(), "InApp: Couldn't parse JSON array string from prefs", t);
        }
    }

    private boolean canShowInAppOnActivity() {
        updateBlacklistedActivitySet();

        for (String blacklistedActivity : inappActivityExclude) {
            String currentActivityName = CoreMetaData.getCurrentActivityName();
            if (currentActivityName != null && currentActivityName.contains(blacklistedActivity)) {
                return false;
            }
        }

        return true;
    }

    //InApp
    private void displayNotification(final CTInAppNotification inAppNotification) {

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainLooperHandler.post(new Runnable() {
                @Override
                public void run() {
                    displayNotification(inAppNotification);
                }
            });
            return;
        }

        if (controllerManager.getInAppFCManager() != null) {
            if (!controllerManager.getInAppFCManager().canShow(inAppNotification)) {
                logger.verbose(config.getAccountId(),
                        "InApp has been rejected by FC, not showing " + inAppNotification.getCampaignId());
                showInAppNotificationIfAny();
                return;
            }

            controllerManager.getInAppFCManager().didShow(context, inAppNotification);
        } else {
            logger.verbose(config.getAccountId(),
                    "getCoreState().getInAppFCManager() is NULL, not showing " + inAppNotification.getCampaignId());
            return;
        }

        final InAppNotificationListener listener = callbackManager.getInAppNotificationListener();

        final boolean goFromListener;

        if (listener != null) {
            final HashMap<String, Object> kvs;

            if (inAppNotification.getCustomExtras() != null) {
                kvs = Utils.convertJSONObjectToHashMap(inAppNotification.getCustomExtras());
            } else {
                kvs = new HashMap<>();
            }

            goFromListener = listener.beforeShow(kvs);
        } else {
            goFromListener = true;
        }

        if (!goFromListener) {
            logger.verbose(config.getAccountId(),
                    "Application has decided to not show this in-app notification: " + inAppNotification
                            .getCampaignId());
            showInAppNotificationIfAny();
            return;
        }
        showInApp(context, inAppNotification, config, this);

    }

    //InApp
    private void prepareNotificationForDisplay(final JSONObject jsonObject) {
        logger.debug(config.getAccountId(), "Preparing In-App for display: " + jsonObject.toString());
        Task<Void> task = CTExecutorFactory.executors(config).postAsyncSafelyTask(Constants.TAG_FEATURE_IN_APPS);
        task.execute("InappController#prepareNotificationForDisplay", new Callable<Void>() {
            @Override
            public Void call() {
                new NotificationPrepareRunnable(InAppController.this, jsonObject).run();
                return null;
            }
        });
    }

    private void prepareNotificationForDisplay(CTHalfInterstitialLocalInAppBuilder
               halfInterstitialLocalInAppBuilder, CTAlertLocalInAppBuilder alertLocalInAppBuilder){
        logger.debug(config.getAccountId(), "Preparing local In-App for display");
        Task<Void> task = CTExecutorFactory.executors(config).postAsyncSafelyTask(Constants.TAG_FEATURE_IN_APPS);
        task.execute("InappController#prepareLocalNotificationForDisplay", new Callable<Void>() {
            @Override
            public Void call() {
                if (halfInterstitialLocalInAppBuilder != null) {
                    new NotificationPrepareRunnable(InAppController.this,
                            halfInterstitialLocalInAppBuilder).run();
                }else if (alertLocalInAppBuilder != null){
                    new NotificationPrepareRunnable(InAppController.this,
                            alertLocalInAppBuilder).run();
                }
                return null;
            }
        });
    }

    private void showInAppNotificationIfAny() {
        if (!config.isAnalyticsOnly()) {
            Task<Void> task = CTExecutorFactory.executors(config).postAsyncSafelyTask(Constants.TAG_FEATURE_IN_APPS);
            task.execute("InAppController#showInAppNotificationIfAny", new Callable<Void>() {
                @Override
                public Void call() {
                    _showNotificationIfAvailable(context);
                    return null;
                }
            });
        }
    }

    private void updateBlacklistedActivitySet() {
        if (inappActivityExclude == null) {
            inappActivityExclude = new HashSet<>();
            try {
                String activities = ManifestInfo.getInstance(context).getExcludedActivities();
                if (activities != null) {
                    String[] split = activities.split(",");
                    for (String a : split) {
                        inappActivityExclude.add(a.trim());
                    }
                }
            } catch (Throwable t) {
                // Ignore
            }
            logger.debug(config.getAccountId(),
                    "In-app notifications will not be shown on " + Arrays.toString(inappActivityExclude.toArray()));
        }
    }

    private static void checkPendingNotifications(final Context context, final CleverTapInstanceConfig config,
            final InAppController inAppController) {
        Logger.v(config.getAccountId(), "checking Pending Notifications");
        if (pendingNotifications != null && !pendingNotifications.isEmpty()) {
            try {
                final CTInAppNotification notification = pendingNotifications.get(0);
                pendingNotifications.remove(0);
                MainLooperHandler mainHandler = new MainLooperHandler();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        showInApp(context, notification, config, inAppController);
                    }
                });
            } catch (Throwable t) {
                // no-op
            }
        }
    }

    //InApp
    private static void inAppDidDismiss(Context context, CleverTapInstanceConfig config,
            CTInAppNotification inAppNotification, InAppController inAppController) {
        Logger.v(config.getAccountId(), "Running inAppDidDismiss");
        if (currentlyDisplayingInApp != null && (currentlyDisplayingInApp.getCampaignId()
                .equals(inAppNotification.getCampaignId()))) {
            currentlyDisplayingInApp = null;
            checkPendingNotifications(context, config, inAppController);
        }
    }

    //InApp
    private static void showInApp(Context context, final CTInAppNotification inAppNotification,
            CleverTapInstanceConfig config, InAppController inAppController) {

        Logger.v(config.getAccountId(), "Attempting to show next In-App");

        if (!CoreMetaData.isAppForeground()) {
            pendingNotifications.add(inAppNotification);
            Logger.v(config.getAccountId(), "Not in foreground, queueing this In App");
            return;
        }

        if (currentlyDisplayingInApp != null) {
            pendingNotifications.add(inAppNotification);
            Logger.v(config.getAccountId(), "In App already displaying, queueing this In App");
            return;
        }

        if ((System.currentTimeMillis() / 1000) > inAppNotification.getTimeToLive()) {
            Logger.d("InApp has elapsed its time to live, not showing the InApp");
            return;
        }

        currentlyDisplayingInApp = inAppNotification;

        CTInAppBaseFragment inAppFragment = null;
        CTInAppType type = inAppNotification.getInAppType();
        switch (type) {
            case CTInAppTypeCoverHTML:
            case CTInAppTypeInterstitialHTML:
            case CTInAppTypeHalfInterstitialHTML:
            case CTInAppTypeCover:
            case CTInAppTypeHalfInterstitial:
            case CTInAppTypeInterstitial:
            case CTInAppTypeAlert:
            case CTInAppTypeInterstitialImageOnly:
            case CTInAppTypeHalfInterstitialImageOnly:
            case CTInAppTypeCoverImageOnly:

                Intent intent = new Intent(context, InAppNotificationActivity.class);
                intent.putExtra("inApp", inAppNotification);
                Bundle configBundle = new Bundle();
                configBundle.putParcelable("config", config);
                intent.putExtra("configBundle", configBundle);
                try {
                    Activity currentActivity = CoreMetaData.getCurrentActivity();
                    if (currentActivity == null) {
                        throw new IllegalStateException("Current activity reference not found");
                    }
                    config.getLogger().verbose(config.getAccountId(),
                            "calling InAppActivity for notification: " + inAppNotification.getJsonDescription());
                    currentActivity.startActivity(intent);
                    Logger.d("Displaying In-App: " + inAppNotification.getJsonDescription());

                } catch (Throwable t) {
                    Logger.v("Please verify the integration of your app." +
                            " It is not setup to support in-app notifications yet.", t);
                }
                break;
            case CTInAppTypeFooterHTML:
                inAppFragment = new CTInAppHtmlFooterFragment();
                break;
            case CTInAppTypeHeaderHTML:
                inAppFragment = new CTInAppHtmlHeaderFragment();
                break;
            case CTInAppTypeFooter:
                inAppFragment = new CTInAppNativeFooterFragment();
                break;
            case CTInAppTypeHeader:
                inAppFragment = new CTInAppNativeHeaderFragment();
                break;
            default:
                Logger.d(config.getAccountId(), "Unknown InApp Type found: " + type);
                currentlyDisplayingInApp = null;
                return;
        }

        if (inAppFragment != null) {
            Logger.d("Displaying In-App: " + inAppNotification.getJsonDescription());
            try {
                //noinspection Constant Conditions
                FragmentTransaction fragmentTransaction = ((FragmentActivity) CoreMetaData.getCurrentActivity())
                        .getSupportFragmentManager()
                        .beginTransaction();
                Bundle bundle = new Bundle();
                bundle.putParcelable("inApp", inAppNotification);
                bundle.putParcelable("config", config);
                inAppFragment.setArguments(bundle);
                fragmentTransaction.setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out);
                fragmentTransaction.add(android.R.id.content, inAppFragment, inAppNotification.getType());
                Logger.v(config.getAccountId(), "calling InAppFragment " + inAppNotification.getCampaignId());
                fragmentTransaction.commit();

            } catch (ClassCastException e) {
                Logger.v(config.getAccountId(),
                        "Fragment not able to render, please ensure your Activity is an instance of AppCompatActivity"
                                + e.getMessage());
            } catch (Throwable t) {
                Logger.v(config.getAccountId(), "Fragment not able to render", t);
            }
        }
    }
}