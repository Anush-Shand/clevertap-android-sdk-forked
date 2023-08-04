package com.clevertap.android.sdk.cryption

import android.content.Context
import com.clevertap.android.sdk.CleverTapInstanceConfig
import com.clevertap.android.sdk.Constants
import com.clevertap.android.sdk.StorageHelper
import com.clevertap.android.sdk.utils.CTJsonConverter
import org.json.JSONObject
import java.io.File
import java.util.Objects

object CryptUtils {

    private const val MIGRATION_FAILED = 0x00
    private const val MIGRATION_CACHED_GUID_SUCCESS = 0x01
    private const val MIGRATION_ARP_SUCCESS = 0x10
    private const val MIGRATION_SUCCESS = 0x11

    /**
     * This method migrates the encryption level of the stored data for the current account ID
     *
     * @param context - The Android context
     * @param config  - The [CleverTapInstanceConfig] object
     */
    @JvmStatic
    fun migrateEncryptionLevel(
        context: Context,
        config: CleverTapInstanceConfig,
        cryptHandler: CryptHandler
    ) {
        val configEncryptionLevel = config.encryptionLevel
        val storedEncryptionLevel = StorageHelper.getInt(
            context,
            StorageHelper.storageKeyWithSuffix(config, Constants.KEY_ENCRYPTION_LEVEL),
            0
        )
        if (storedEncryptionLevel == configEncryptionLevel) {
            return
        }

        val storedMigrationStatus = StorageHelper.getInt(
            context,
            StorageHelper.storageKeyWithSuffix(config, Constants.KEY_MIGRATION_STATUS),
            MIGRATION_FAILED
        )

        // TODO:@Anush: complete rest of the logic

        config.logger.verbose(
            config.accountId,
            "Migrating encryption level from $storedEncryptionLevel to $configEncryptionLevel"
        )
        // If configEncryptionLevel is greater than storedEncryptionLevel, encryption level has increased. Hence perform encryption
        // Otherwise decryption

        val migrateEncryptionStatus = migrateEncryption(
            configEncryptionLevel > storedEncryptionLevel,
            context,
            config,
            cryptHandler
        )

        StorageHelper.putInt(
            context,
            StorageHelper.storageKeyWithSuffix(config, Constants.KEY_MIGRATION_STATUS),
            migrateEncryptionStatus
        )
        if (migrateEncryptionStatus == MIGRATION_SUCCESS) {
            StorageHelper.putInt(
                context,
                StorageHelper.storageKeyWithSuffix(config, Constants.KEY_ENCRYPTION_LEVEL),
                configEncryptionLevel
            )
        }

    }

    private fun migrateEncryption(
        encrypt: Boolean,
        context: Context,
        config: CleverTapInstanceConfig,
        cryptHandler: CryptHandler
    ): Int {
        val migrateCachedGuidsKeyPrefStatus =
            migrateCachedGuidsKeyPref(encrypt, config, context, cryptHandler)
        val migrateARPPreferenceFilesStatus =
            migrateARPPreferenceFiles(encrypt, config, context, cryptHandler)
        return migrateCachedGuidsKeyPrefStatus or migrateARPPreferenceFilesStatus
    }

    /**
     * This method migrates the encryption level of the value under cachedGUIDsKey stored in the shared preference file
     * Only the value of the identifier(eg: johndoe@gmail.com) is encrypted/decrypted for this key throughout the sdk
     *
     * @param encrypt - Flag to indicate the task to be either encryption or decryption
     * @param config  - The [CleverTapInstanceConfig] object
     * @param context - The Android context
     * @return isMigrationSuccess - Int
     */
    private fun migrateCachedGuidsKeyPref(
        encrypt: Boolean,
        config: CleverTapInstanceConfig,
        context: Context,
        cryptHandler: CryptHandler
    ): Int {
        var isMigrationSuccess = MIGRATION_FAILED
        config.logger.verbose(
            config.accountId,
            "Migrating encryption level for cachedGUIDsKey prefs"
        )
        val json =
            StorageHelper.getStringFromPrefs(context, config, Constants.CACHED_GUIDS_KEY, null)
        val cachedGuidJsonObj = CTJsonConverter.toJsonObject(json, config.logger, config.accountId)
        val newGuidJsonObj = JSONObject()
        try {
            val i = cachedGuidJsonObj.keys()
            while (i.hasNext()) {
                val nextJSONObjKey = i.next()
                val key = nextJSONObjKey.substring(0, nextJSONObjKey.indexOf("_") + 1)
                val identifier = nextJSONObjKey.substring(nextJSONObjKey.indexOf("_") + 1)
                val crypted: String =
                    if (encrypt)
                        cryptHandler.encrypt(identifier, Constants.CACHED_GUIDS_KEY)
                    else
                        cryptHandler.decrypt(identifier, Constants.KEY_ENCRYPTION_MIGRATION)
                val cryptedKey = key + crypted
                newGuidJsonObj.put(cryptedKey, cachedGuidJsonObj[nextJSONObjKey])
            }
            if (cachedGuidJsonObj.length() > 0) {
                val cachedGuid = newGuidJsonObj.toString()
                StorageHelper.putString(
                    context,
                    StorageHelper.storageKeyWithSuffix(config, Constants.CACHED_GUIDS_KEY),
                    cachedGuid
                )
                config.logger.verbose(
                    config.accountId,
                    "setCachedGUIDs after migration:[$cachedGuid]"
                )
            }

            isMigrationSuccess = MIGRATION_CACHED_GUID_SUCCESS
        } catch (t: Throwable) {
            config.logger.verbose(config.accountId, "Error migrating cached guids: $t")
        }

        return isMigrationSuccess
    }

    /**
     * This method migrates the encryption level of the value under the key k_n. This data is stored in the ARP related shared preference files
     *
     * @param encrypt - Flag to indicate the task to be either encryption or decryption
     * @param config  - The [CleverTapInstanceConfig] object
     * @param context - The Android context
     * @return isMigrationSuccess - Int
     */
    private fun migrateARPPreferenceFiles(
        encrypt: Boolean,
        config: CleverTapInstanceConfig,
        context: Context,
        cryptHandler: CryptHandler
    ): Int {
        var isMigrationSuccess = MIGRATION_FAILED

        config.logger.verbose(config.accountId, "Migrating encryption level for ARP related prefs")
        try {
            // Gets all the files present in the shared_prefs directory
            val dataDir = context.applicationInfo.dataDir
            val prefsDir = File(dataDir, "shared_prefs")
            val path = Constants.CLEVERTAP_STORAGE_TAG + "_ARP:" + config.accountId
            for (prefName in Objects.requireNonNull(prefsDir.list())) {

                //Checks if the file name of the preference is an ARP file for the current accountID
                if (prefName.startsWith(path)) {
                    val prefFile = prefName.substring(0, prefName.length - 4)
                    val prefs = context.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
                    val value = prefs.getString(Constants.KEY_k_n, "")

                    // If key k_n is present then it is encrypted/decrypted and persisted
                    if (value != "") {
                        val crypted: String =
                            if (encrypt)
                                cryptHandler.encrypt(value!!, Constants.KEY_k_n)
                            else
                                cryptHandler.decrypt(value!!, Constants.KEY_k_n)
                        val editor = prefs.edit().putString(Constants.KEY_k_n, crypted)
                        StorageHelper.persist(editor)
                    }
                }
            }

            isMigrationSuccess = MIGRATION_ARP_SUCCESS
        } catch (e: Exception) {
            config.logger.verbose(config.accountId, "Error migrating ARP Preference Files: $e")
        }

        return isMigrationSuccess
    }

    private fun isMigrationSuccess(flagSet: Int, flag: Int) = (flagSet or flag) == flagSet
}

