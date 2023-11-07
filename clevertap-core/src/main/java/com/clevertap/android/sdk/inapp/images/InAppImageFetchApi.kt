package com.clevertap.android.sdk.inapp.images

import com.clevertap.android.sdk.bitmap.BitmapDownloadRequest
import com.clevertap.android.sdk.bitmap.HttpBitmapLoader
import com.clevertap.android.sdk.network.DownloadedBitmap

object InAppImageFetchApi {
    fun makeApiCallForInAppBitmap(url: String): DownloadedBitmap {
        val request = BitmapDownloadRequest(url)
        return HttpBitmapLoader.getHttpBitmap(
            bitmapOperation = HttpBitmapLoader.HttpBitmapOperation.DOWNLOAD_INAPP_BITMAP,
            bitmapDownloadRequest = request
        )
    }
}