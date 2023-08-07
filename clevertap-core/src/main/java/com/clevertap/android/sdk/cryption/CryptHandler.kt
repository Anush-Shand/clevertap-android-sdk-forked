package com.clevertap.android.sdk.cryption

import com.clevertap.android.sdk.Constants

class CryptHandler(encryptionLevel: Int, encryptionType: EncryptionAlgorithm, accountID: String) {
    private var encryptionLevel: EncryptionLevel
    private var encryptionType: EncryptionAlgorithm
    private var crypt: Crypt
    private var accountID: String
    var encryptionFlagStatus: Int

    enum class EncryptionAlgorithm {
        AES
    }

    enum class EncryptionLevel(private val value: Int) {
        NONE(0), MEDIUM(1);

        fun intValue(): Int {
            return value
        }
    }

    init {
        this.encryptionLevel = EncryptionLevel.values()[encryptionLevel]
        this.encryptionType = encryptionType
        this.accountID = accountID
        this.encryptionFlagStatus = 0b00
        this.crypt = CryptFactory.getCrypt(encryptionType)
    }

    /**
     * This method returns the encrypted text if the key is a part of the current encryption level and is not already encrypted
     *
     * @param plainText - plainText to be encrypted
     * @param key       - key of the plainText to be encrypted
     * @return encrypted text
     */
    fun encrypt(plainText: String, key: String): String? {
        when (encryptionLevel) {
            EncryptionLevel.MEDIUM ->
                if (key in Constants.MEDIUM_CRYPT_KEYS && !isTextEncrypted(plainText))
                    return crypt.encryptInternal(plainText, accountID)
            else -> return plainText
        }
        return plainText
    }

    /**
     * This method returns the decrypted text if the key is a part of the current encryption level
     *
     * @param cipherText - cipherText to be decrypted
     * @param key        - key of the cipherText that needs to be decrypted
     * @return decrypted text
     */
    fun decrypt(cipherText: String, key: String): String? {
        if (isTextEncrypted(cipherText)) {
            when (encryptionLevel) {
                EncryptionLevel.MEDIUM -> {
                    if (key.lowercase() in Constants.MEDIUM_CRYPT_KEYS)
                        return crypt.decryptInternal(cipherText, accountID)
                }
                else -> {
                    return crypt.decryptInternal(cipherText, accountID)
                }
            }
        }
        return cipherText
    }

    private fun isTextEncrypted(plainText: String): Boolean {
        return plainText.startsWith('[') && plainText.endsWith(']') && !plainText.startsWith("[ \"")
    }
}