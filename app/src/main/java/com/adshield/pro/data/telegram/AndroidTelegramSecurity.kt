package com.adshield.pro.data.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM key held in Android Keystore. The key never leaves the secure hardware-backed
 * keystore and is destroyed on disconnect so any surviving ciphertext is unreadable.
 *
 * app:allowBackup=false and res/xml/data_extraction_rules.xml exclude this SharedPreferences file
 * from cloud backup and device-to-device transfer, so a restored device cannot reuse a session.
 */
@Singleton
class AndroidKeystoreCipher @Inject constructor(
    @ApplicationContext private val context: Context
) : TelegramAesGcm {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = runCatching { store.getKey(ALIAS, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing
        return runCatching {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }.getOrElse { failure ->
            throw TelegramKeyUnavailable("Android Keystore is unavailable: ${failure.message}")
        }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size > TelegramSecureStore.IV_BYTES + TelegramSecureStore.TAG_BYTES) {
            "Truncated encrypted record"
        }
        val iv = envelope.copyOfRange(0, TelegramSecureStore.IV_BYTES)
        val body = envelope.copyOfRange(TelegramSecureStore.IV_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return try {
            cipher.doFinal(body)
        } catch (failure: Exception) {
            throw TelegramKeyUnavailable("Encrypted Telegram session record failed authentication")
        }
    }

    override fun deleteKey() {
        runCatching {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "adshield.telegram.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

@Singleton
class SharedPreferencesTelegramStore @Inject constructor(
    @ApplicationContext context: Context
) : TelegramKeyValueStore {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun clear() {
        preferences.edit().clear().commit()
    }

    override fun keys(): Set<String> = preferences.all.keys.toSet()

    private companion object {
        const val PREFS_NAME = "adshield_telegram_encrypted_v1"
    }
}
