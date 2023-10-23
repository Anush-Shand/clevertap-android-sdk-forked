package com.clevertap.android.sdk.inapp

import androidx.annotation.WorkerThread
import com.clevertap.android.sdk.CleverTapInstanceConfig
import com.clevertap.android.sdk.LegacyInAppStore
import com.clevertap.android.sdk.Logger
import org.json.JSONArray
import org.json.JSONObject

class InAppQueue(
    private val config: CleverTapInstanceConfig,
    private val legacyInAppStore: LegacyInAppStore
) {

    @WorkerThread
    @Synchronized
    fun enqueue(jsonObject: JSONObject) {
        val currentQueue = getQueue()
        currentQueue.put(jsonObject)
        saveQueue(currentQueue)
    }

    @WorkerThread
    @Synchronized
    fun enqueueAll(jsonArray: JSONArray) {
        val currentQueue = getQueue()
        for (i in 0 until jsonArray.length()) {
            try {
                currentQueue.put(jsonArray.getJSONObject(i))
            } catch (e: Exception) {
                Logger.d(
                    config.accountId,
                    "InAppController: Malformed InApp notification: " + e.message
                )
            }
        }
        saveQueue(currentQueue)
    }

    @WorkerThread
    @Synchronized
    fun dequeue(): JSONObject? {
        val currentQueue = getQueue()
        if (currentQueue.length() == 0) {
            return null
        }
        val removedObject = currentQueue.remove(0)
        saveQueue(currentQueue)
        return removedObject as JSONObject
    }

    @WorkerThread
    @Synchronized
    fun getQueueLength(): Int {
        val currentQueue = getQueue()
        return currentQueue.length()
    }

    private fun getQueue(): JSONArray = legacyInAppStore.readInApps()

    private fun saveQueue(queue: JSONArray) = legacyInAppStore.storeInApps(queue)
}
