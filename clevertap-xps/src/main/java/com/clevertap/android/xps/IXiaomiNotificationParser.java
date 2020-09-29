package com.clevertap.android.xps;

import android.os.Bundle;
import com.xiaomi.mipush.sdk.MiPushMessage;

public interface IXiaomiNotificationParser {

    /**
     * @param message - Xiaomi message
     * @return bundle with the message content, in case of invalid message returns null
     */
    Bundle toBundle(MiPushMessage message);
}