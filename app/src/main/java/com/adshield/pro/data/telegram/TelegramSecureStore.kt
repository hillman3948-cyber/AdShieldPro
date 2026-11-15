package com.adshield.pro.data.telegram

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/** The Android Keystore key is gone (device restore, key invalidation, or after a disconnect). */
class TelegramKeyUnavailable(message: String) : Exception(message)

/** Ciphertext-only key/value surface. SharedPreferences is the Android implementation. */
interface TelegramKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clear()
    fun keys(): Set<String>
}

/** AES-256-GCM envelope: 12-byte IV || ciphertext || 16-byte authentication tag. */
interface TelegramAesGcm {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(envelope: ByteArray): ByteArray
    fun deleteKey()
}

/**
 * All Telegram session state, the file index and the transfer book are stored as a single
 * Keystore-encrypted JSON blob per key. Nothing is written in plaintext and nothing is logged.
 *
 * The cipher and the key/value surface are injected, which is what lets the JVM suite verify
 * the envelope format, tamper detection and full disconnect wipe without Android Keystore.
 */
class TelegramSecureStore(
    private val kv: TelegramKeyValueStore,
    private val cipher: TelegramAesGcm,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    fun loadSession(): TelegramSession? = read(KEY_SESSION)
    fun saveSession(session: TelegramSession) = write(KEY_SESSION, session)
    fun clearSession() = kv.remove(KEY_SESSION)

    fun loadFiles(): List<TelegramStoredFile> = read<TelegramFileIndex>(KEY_FILES)?.files ?: emptyList()
    fun saveFiles(files: List<TelegramStoredFile>) = write(KEY_FILES, TelegramFileIndex(files))

    fun loadTransfers(): List<TelegramTransfer> =
        read<TelegramTransferBook>(KEY_TRANSFERS)?.transfers ?: emptyList()
    fun saveTransfers(transfers: List<TelegramTransfer>) =
        write(KEY_TRANSFERS, TelegramTransferBook(transfers))

    /** Exposed for the "stored data contains no plaintext secret" assertion in tests/CI. */
    fun rawValues(): Map<String, String?> = kv.keys().associateWith { kv.get(it) }

    /**
     * Wipe every encrypted blob and destroy the Keystore key. Callers must also delete the TDLib
     * database/files directory and the staged local copies (see AndroidTelegramFileIo.wipe).
     */
    fun clearAll() {
        kv.clear()
        runCatching { cipher.deleteKey() }
    }

    private inline fun <reified T> read(key: String): T? {
        val raw = kv.get(key) ?: return null
        return try {
            val envelope = Base64.getDecoder().decode(raw)
            require(envelope.size > IV_BYTES + TAG_BYTES) { "Truncated encrypted record" }
            val plaintext = cipher.decrypt(envelope)
            json.decodeFromString<T>(plaintext.toString(Charsets.UTF_8))
        } catch (failure: TelegramKeyUnavailable) {
            throw failure
        } catch (failure: Exception) {
            // A damaged or re-keyed record is unreadable; never fall back to a fake session.
            throw TelegramKeyUnavailable("Telegram session data cannot be decrypted: ${failure.message}")
        }
    }

    private inline fun <reified T> write(key: String, value: T) {
        val plaintext = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        val envelope = cipher.encrypt(plaintext)
        kv.put(key, Base64.getEncoder().encodeToString(envelope))
    }

    companion object {
        const val KEY_SESSION = "session"
        const val KEY_FILES = "files"
        const val KEY_TRANSFERS = "transfers"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
