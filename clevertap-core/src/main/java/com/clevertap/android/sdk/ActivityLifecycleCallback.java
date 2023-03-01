package com.clevertap.android.sdk;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import java.util.HashSet;

/**
 * Class for handling activity lifecycle events
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public final class ActivityLifecycleCallback {

    public static boolean registered = false;
    /**
     * Enables lifecycle callbacks for Android devices
     *
     * @param application App's Application object
     * @param cleverTapID Custom CleverTap ID
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public static synchronized void register(android.app.Application application, final String cleverTapID) {
        if (application == null) {
            Logger.i("Application instance is null/system API is too old");
            return;
        }

        if (registered) {
            Logger.v("Lifecycle callbacks have already been registered");
            return;
        }

        registered = true;
        application.registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {

                    @Override
                    public void onActivityCreated(Activity activity, Bundle bundle) {
                        Logger.v("ctv","ActivityLifecycleCallbacks#onActivityCreated called for activity#"+activity+",bundle#"+bundle);
                        if (cleverTapID != null) {
                            CleverTapAPI.onActivityCreated(activity, cleverTapID);
                        } else {
                            CleverTapAPI.onActivityCreated(activity);
                        }
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                        Logger.v("ctv","ActivityLifecycleCallbacks#onActivityDestroyed called for activity#"+activity);

                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                        Logger.v("ctv","ActivityLifecycleCallbacks#onActivityPaused called for activity#"+activity);
                        CleverTapAPI.onActivityPaused();
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {
                        Logger.v("ctv","ActivityLifecycleCallbacks#onActivityResumed called for activity#"+activity);
                        if (cleverTapID != null) {
                            CleverTapAPI.onActivityResumed(activity, cleverTapID);
                        } else {
                            CleverTapAPI.onActivityResumed(activity);
                        }
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
                    }

                    @Override
                    public void onActivityStarted(Activity activity) {
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {
                    }
                }

        );
        Logger.i("Activity Lifecycle Callback successfully registered");
    }

    /**
     * Enables lifecycle callbacks for Android devices
     *
     * @param application App's Application object
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public static synchronized void register(android.app.Application application) {
        register(application, null);
    }
}
