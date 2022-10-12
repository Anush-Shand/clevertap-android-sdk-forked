package com.clevertap.android.sdk;

import static com.clevertap.android.sdk.Utils.isAndroid13;
import static com.clevertap.android.sdk.inapp.InAppController.IS_FIRST_TIME_PERMISSION_REQUEST;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.clevertap.android.sdk.inapp.AlertDialogPromptForSettings;
import com.clevertap.android.sdk.inapp.CTInAppBaseFullFragment;
import com.clevertap.android.sdk.inapp.CTInAppHtmlCoverFragment;
import com.clevertap.android.sdk.inapp.CTInAppHtmlHalfInterstitialFragment;
import com.clevertap.android.sdk.inapp.CTInAppHtmlInterstitialFragment;
import com.clevertap.android.sdk.inapp.CTInAppNativeCoverFragment;
import com.clevertap.android.sdk.inapp.CTInAppNativeCoverImageFragment;
import com.clevertap.android.sdk.inapp.CTInAppNativeHalfInterstitialFragment;
import com.clevertap.android.sdk.inapp.CTInAppNativeHalfInterstitialImageFragment;
import com.clevertap.android.sdk.inapp.CTInAppNativeInterstitialFragment;
import com.clevertap.android.sdk.inapp.CTInAppNativeInterstitialImageFragment;
import com.clevertap.android.sdk.inapp.CTInAppNotification;
import com.clevertap.android.sdk.inapp.CTInAppType;
import com.clevertap.android.sdk.inapp.InAppListener;

import java.lang.ref.WeakReference;
import java.util.HashMap;

public final class InAppNotificationActivity extends FragmentActivity implements InAppListener {

    private static boolean isAlertVisible = false;

    private CleverTapInstanceConfig config;

    private CTInAppNotification inAppNotification;

    private WeakReference<InAppListener> listenerWeakReference;

    private WeakReference<PermissionCallback> permissionCallbackWeakReference;

    private static final int PERMISSION_REQUEST_CODE = 2;

    public static final String ANDROID_PERMISSION_STRING = "android.permission.POST_NOTIFICATIONS";

    public interface PermissionCallback {
        void onAccept();
        void onReject();
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int orientation = this.getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        try {
            Bundle notif = getIntent().getExtras();
            if (notif == null) {
                throw new IllegalArgumentException();
            }
            inAppNotification = notif.getParcelable("inApp");
            boolean displayHardNotificationDialog = notif.getBoolean("displayHardPermissionDialog",
                    false); // Using this boolean for a directly showing hard permission dialog flow
            Bundle configBundle = notif.getBundle("configBundle");
            if (configBundle != null) {
                config = configBundle.getParcelable("config");
            }
            setListener(CleverTapAPI.instanceWithConfig(this, config).getCoreState().getInAppController());

            setPermissionCallback(CleverTapAPI.instanceWithConfig(this, config).getCoreState().getInAppController());
            if (displayHardNotificationDialog){
                prompt();
                return;
            }
        } catch (Throwable t) {
            Logger.v("Cannot find a valid notification bundle to show!", t);
            finish();
            return;
        }
        if (inAppNotification == null) {
            finish();
            return;
        }

        //Allow rotation for all InApps but respect the flags sent from dashboard
        if (inAppNotification.isPortrait() && !inAppNotification.isLandscape()) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Logger.d("App in Landscape, dismissing portrait InApp Notification");
                finish();
                didDismiss(null);
                return;
            } else {
                Logger.d("App in Portrait, displaying InApp Notification anyway");
            }
        }

        if (!inAppNotification.isPortrait() && inAppNotification.isLandscape()) {
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                Logger.d("App in Portrait, dismissing landscape InApp Notification");
                finish();
                didDismiss(null);
                return;
            } else {
                Logger.d("App in Landscape, displaying InApp Notification anyway");
            }
        }

        CTInAppBaseFullFragment contentFragment;
        if (savedInstanceState == null) {
            contentFragment = createContentFragment();
            if (contentFragment != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable("inApp", inAppNotification);
                bundle.putParcelable("config", config);
                contentFragment.setArguments(bundle);
                getSupportFragmentManager().beginTransaction()
                        .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                        .add(android.R.id.content, contentFragment, getFragmentTag())
                        .commit();
            }
        } else if (isAlertVisible) {
            createContentFragment();
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void inAppNotificationDidClick(CTInAppNotification inAppNotification, Bundle formData,
            HashMap<String, String> keyValueMap, int btnClickIndex) {
        didClick(formData, keyValueMap, btnClickIndex);
    }

    @Override
    public void inAppNotificationDidDismiss(final Context context, final CTInAppNotification inAppNotification,
            Bundle formData) {
        didDismiss(formData);
    }

    @Override
    public void inAppNotificationDidShow(CTInAppNotification inAppNotification, Bundle formData) {
        didShow(formData);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
        didDismiss(null);
    }

    @Override
    public void setTheme(int resid) {
        super.setTheme(android.R.style.Theme_Translucent_NoTitleBar);
    }

    void didClick(Bundle data, HashMap<String, String> keyValueMap, int btnClickIndex) {
        InAppListener listener = getListener();
        if (listener != null) {
            listener.inAppNotificationDidClick(inAppNotification, data, keyValueMap,btnClickIndex);
        }
    }

    public void prompt(){
        if (isAndroid13(InAppNotificationActivity.this)){
            requestPermission();
        }
    }

    @RequiresApi(api = 33)
    public void requestPermission() {
        int permissionStatus = ContextCompat.checkSelfPermission(InAppNotificationActivity.this,
                Manifest.permission.POST_NOTIFICATIONS);

        if (permissionStatus == PackageManager.PERMISSION_DENIED){
            if (!StorageHelper.getBoolean(InAppNotificationActivity.this,
                    IS_FIRST_TIME_PERMISSION_REQUEST,true)) {
                boolean neverAskAgainClicked = ActivityCompat.shouldShowRequestPermissionRationale(
                        InAppNotificationActivity.this, ANDROID_PERMISSION_STRING);
                if (neverAskAgainClicked && inAppNotification.fallBackToNotificationSettings()) {
                    showFallbackAlertDialog();
                }
            }else{
                ActivityCompat.requestPermissions(InAppNotificationActivity.this,
                        new String[]{ANDROID_PERMISSION_STRING}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        StorageHelper.putBoolean(InAppNotificationActivity.this,IS_FIRST_TIME_PERMISSION_REQUEST,false);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] ==
                    PackageManager.PERMISSION_GRANTED;
            if (granted) {
                permissionCallbackWeakReference.get().onAccept();
            }else {
                permissionCallbackWeakReference.get().onReject();
            }
            didDismiss(null);
        }
    }

    public void showFallbackAlertDialog() {
        AlertDialogPromptForSettings.INSTANCE.show(InAppNotificationActivity.this, new AlertDialogPromptForSettings.Callback() {
            @Override
            public void onAccept() {
                Utils.navigateToAndroidSettingsForNotifications(InAppNotificationActivity.this);
                didDismiss(null);
            }

            @Override
            public void onDecline() {
                didDismiss(null);
            }
        });
    }

    void didDismiss(Bundle data) {
        if (isAlertVisible) {
            isAlertVisible = false;
        }
        finish();
        InAppListener listener = getListener();
        if (listener != null && getBaseContext() != null && inAppNotification != null) {
            listener.inAppNotificationDidDismiss(getBaseContext(), inAppNotification, data);
        }
    }

    void didShow(Bundle data) {
        InAppListener listener = getListener();
        if (listener != null) {
            listener.inAppNotificationDidShow(inAppNotification, data);
        }
    }

    void fireUrlThroughIntent(String url, Bundle formData) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.replace("\n", "").replace("\r", "")));
            startActivity(intent);
        } catch (Throwable t) {
            // Ignore
        }
        didDismiss(formData);
    }

    InAppListener getListener() {
        InAppListener listener = null;
        try {
            listener = listenerWeakReference.get();
        } catch (Throwable t) {
            // no-op
        }
        if (listener == null) {
            config.getLogger().verbose(config.getAccountId(),
                    "InAppActivityListener is null for notification: " + inAppNotification.getJsonDescription());
        }
        return listener;
    }

    void setListener(InAppListener listener) {
        listenerWeakReference = new WeakReference<>(listener);
    }

    public void setPermissionCallback(PermissionCallback callback){
        permissionCallbackWeakReference = new WeakReference<>(callback);
    }

    private CTInAppBaseFullFragment createContentFragment() {
        CTInAppType type = inAppNotification.getInAppType();
        CTInAppBaseFullFragment viewFragment = null;
        switch (type) {
            case CTInAppTypeCoverHTML: {
                viewFragment = new CTInAppHtmlCoverFragment();
                break;
            }
            case CTInAppTypeInterstitialHTML: {
                viewFragment = new CTInAppHtmlInterstitialFragment();
                break;
            }
            case CTInAppTypeHalfInterstitialHTML: {
                viewFragment = new CTInAppHtmlHalfInterstitialFragment();
                break;
            }
            case CTInAppTypeCover: {
                viewFragment = new CTInAppNativeCoverFragment();
                break;
            }
            case CTInAppTypeInterstitial: {
                viewFragment = new CTInAppNativeInterstitialFragment();
                break;
            }
            case CTInAppTypeHalfInterstitial: {
                viewFragment = new CTInAppNativeHalfInterstitialFragment();
                break;
            }
            case CTInAppTypeCoverImageOnly: {
                viewFragment = new CTInAppNativeCoverImageFragment();
                break;
            }
            case CTInAppTypeInterstitialImageOnly: {
                viewFragment = new CTInAppNativeInterstitialImageFragment();
                break;
            }
            case CTInAppTypeHalfInterstitialImageOnly: {
                viewFragment = new CTInAppNativeHalfInterstitialImageFragment();
                break;
            }
            case CTInAppTypeAlert: {
                AlertDialog alertDialog = null;
                if (inAppNotification.getButtons().size() > 0) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        alertDialog = new AlertDialog.Builder(InAppNotificationActivity.this,
                                android.R.style.Theme_Material_Light_Dialog_Alert)
                                .setCancelable(false)
                                .setTitle(inAppNotification.getTitle())
                                .setMessage(inAppNotification.getMessage())
                                .setPositiveButton(inAppNotification.getButtons().get(0).getText(),
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialogInterface, int i) {
                                                Bundle data = new Bundle();
                                                data.putString(Constants.NOTIFICATION_ID_TAG,
                                                        inAppNotification.getCampaignId());
                                                data.putString("wzrk_c2a",
                                                        inAppNotification.getButtons().get(0).getText());
                                                didClick(data, null,0);
                                                String actionUrl = inAppNotification.getButtons().get(0)
                                                        .getActionUrl();
                                                if (actionUrl != null) {
                                                    fireUrlThroughIntent(actionUrl, data);
                                                    return;
                                                }
                                               if (inAppNotification.isLocalInApp()) {
                                                   prompt();
                                               }else {
                                                   didDismiss(data);
                                               }
                                            }
                                        })
                                .create();
                        if (inAppNotification.getButtons().size() == 2) {
                            alertDialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                                    inAppNotification.getButtons().get(1).getText(),
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            Bundle data = new Bundle();
                                            data.putString(Constants.NOTIFICATION_ID_TAG,
                                                    inAppNotification.getCampaignId());
                                            data.putString("wzrk_c2a",
                                                    inAppNotification.getButtons().get(1).getText());
                                            didClick(data, null,1);
                                            String actionUrl = inAppNotification.getButtons().get(1).getActionUrl();
                                            if (actionUrl != null) {
                                                fireUrlThroughIntent(actionUrl, data);
                                                return;
                                            }
                                            didDismiss(data);
                                        }
                                    });
                        }
                    } else {
                        alertDialog = new AlertDialog.Builder(InAppNotificationActivity.this)
                                .setCancelable(false)
                                .setTitle(inAppNotification.getTitle())
                                .setMessage(inAppNotification.getMessage())
                                .setPositiveButton(inAppNotification.getButtons().get(0).getText(),
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialogInterface, int i) {
                                                Bundle data = new Bundle();
                                                data.putString(Constants.NOTIFICATION_ID_TAG,
                                                        inAppNotification.getCampaignId());
                                                data.putString("wzrk_c2a",
                                                        inAppNotification.getButtons().get(0).getText());
                                                didClick(data, null,0);
                                                String actionUrl = inAppNotification.getButtons().get(0)
                                                        .getActionUrl();
                                                if (actionUrl != null) {
                                                    fireUrlThroughIntent(actionUrl, data);
                                                    return;
                                                }
                                                didDismiss(data);

                                            }
                                        }).create();
                        if (inAppNotification.getButtons().size() == 2) {
                            alertDialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                                    inAppNotification.getButtons().get(1).getText(),
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            Bundle data = new Bundle();
                                            data.putString(Constants.NOTIFICATION_ID_TAG,
                                                    inAppNotification.getCampaignId());
                                            data.putString("wzrk_c2a",
                                                    inAppNotification.getButtons().get(1).getText());
                                            didClick(data, null,1);
                                            String actionUrl = inAppNotification.getButtons().get(1).getActionUrl();
                                            if (actionUrl != null) {
                                                fireUrlThroughIntent(actionUrl, data);
                                                return;
                                            }
                                            didDismiss(data);
                                        }
                                    });
                        }
                    }
                    //By default, we will allow 2 button alerts and set a third button if it is configured
                    if (inAppNotification.getButtons().size() > 2) {
                        alertDialog.setButton(DialogInterface.BUTTON_NEUTRAL,
                                inAppNotification.getButtons().get(2).getText(),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int i) {
                                        Bundle data = new Bundle();
                                        data.putString(Constants.NOTIFICATION_ID_TAG,
                                                inAppNotification.getCampaignId());
                                        data.putString("wzrk_c2a", inAppNotification.getButtons().get(2).getText());
                                        didClick(data, null,2);
                                        String actionUrl = inAppNotification.getButtons().get(2).getActionUrl();
                                        if (actionUrl != null) {
                                            fireUrlThroughIntent(actionUrl, data);
                                            return;
                                        }
                                        didDismiss(data);
                                    }
                                });
                    }
                }
                if(alertDialog != null){
                    alertDialog.show();
                    isAlertVisible = true;
                    didShow(null);
                }else{
                    config.getLogger().debug("InAppNotificationActivity: Alert Dialog is null, not showing Alert InApp");
                }
                break;
            }
            default: {
                config.getLogger().verbose("InAppNotificationActivity: Unhandled InApp Type: " + type);
                break;
            }
        }
        return viewFragment;
    }

    private String getFragmentTag() {
        return config.getAccountId() + ":CT_INAPP_CONTENT_FRAGMENT";
    }
}
