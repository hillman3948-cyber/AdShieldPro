package com.adshield.pro.data.telegram

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min

/**
 * Pure, testable rules for the Telegram provider: Telegram's documented limits, the caption
 * envelope used to recognise our own files, SHA-256 duplicate detection, and retry policy.
 * Nothing in this file simulates a network call.
 */
object TelegramLimits {
    /**
     * Telegram allows 2 GB per file for non-Premium accounts and 4 GB for Premium. We enforce the
     * conservative 2 GB ceiling for every account so a transfer is never rejected by the server
     * because of a subscription tier. Splitting larger files is up to the user.
     */
    const val MAX_UPLOAD_BYTES = 2000L * 1024L * 1024L
    const val MAX_CAPTION_CHARS = 900
    const val HISTORY_PAGE_SIZE = 100
    const val PRIORITY = 16 // TDLib transfer priority, 1..32
    const val TRANSFER_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    const val MAX_RETRY_ATTEMPTS = 5
    const val CHANNEL_TITLE = "AdShield Pro - Private Storage"
    const val CHANNEL_DESCRIPTION = "Private file storage created by AdShield Pro. Only this account can read it."
    const val STAGED_DIRECTORY = "staging"
}

class TelegramTdlibException(val code: Int, message: String) : Exception(message)

class TelegramInterruptedTransfer(message: String) : Exception(message)

class TelegramDuplicateFile(val existing: TelegramStoredFile) : Exception(
    "An identical file (SHA-256 ${existing.sha256.take(12)}…) was already uploaded as ${existing.name}"
)

object TelegramPolicy {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun validateUploadSize(sizeBytes: Long) {
        require(sizeBytes > 0L) { "The selected document is empty" }
        require(sizeBytes <= TelegramLimits.MAX_UPLOAD_BYTES) {
            "Telegram accepts at most 2 GB per file for non-Premium accounts (4 GB for Premium). " +
                "This file is ${formatTelegramBytes(sizeBytes)}; split it before uploading."
        }
    }

    fun validateApiCredentials(apiId: Int, apiHash: String) {
        require(apiId > 0) { "Enter the api_id you obtained from https://my.telegram.org" }
        require(apiHash.isNotBlank() && apiHash.length >= 16) {
            "Enter the api_hash you obtained from https://my.telegram.org"
        }
    }

    /** The caption is the only index we own inside Telegram; a fixed marker means we never
     * mistake a user's unrelated Saved Messages document for one of our stored files. */
    fun encodeCaption(sha256: String, sizeBytes: Long, label: String): String {
        val safeLabel = label.trim().take(80).replace('\n', ' ')
        val body = json.encodeToString(
            TelegramCaption(sha256 = sha256, size = sizeBytes, label = safeLabel)
        )
        val caption = CAPTION_MARKER + body
        require(caption.length <= TelegramLimits.MAX_CAPTION_CHARS) { "File metadata caption is too long" }
        return caption
    }

    fun decodeCaption(caption: String?): TelegramCaption? {
        if (caption == null || !caption.startsWith(CAPTION_MARKER)) return null
        return runCatching {
            json.decodeFromString<TelegramCaption>(caption.removePrefix(CAPTION_MARKER))
        }.getOrNull()?.takeIf { it.v == CAPTION_VERSION }
    }

    fun isOurCaption(caption: String?): Boolean = decodeCaption(caption) != null

    /** SHA-256 and exact byte count of a stream. Used for duplicate detection and download
     * verification; Telegram does not expose a hash for arbitrary uploaded documents. */
    fun hashStream(input: InputStream, limitBytes: Long = TelegramLimits.MAX_UPLOAD_BYTES): TelegramHashResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > limitBytes) error("File exceeds the ${formatTelegramBytes(limitBytes)} Telegram limit")
                digest.update(buffer, 0, read)
            }
        }
        return TelegramHashResult(
            total,
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        )
    }

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace('\\', '_')
            .replace('/', '_')
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .trim()
            .take(120)
        return cleaned.ifBlank { "upload-${System.currentTimeMillis()}" }
    }

    /** Never persist a full phone number: only the last four digits are useful for the UI. */
    fun maskPhone(phone: String): String {
        val digits = phone.filter(Char::isDigit)
        if (digits.length < 4) return "••••"
        return "+••••••${digits.takeLast(4)}"
    }

    fun progressFraction(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    /** Bounded exponential backoff: 2s, 4s, 8s, 16s, 30s. */
    fun retryDelayMs(attempts: Int): Long {
        val exponent = attempts.coerceIn(0, 4)
        return min(30_000L, 2_000L shl exponent)
    }

    fun formatTelegramBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
        return String.format(Locale.ROOT, if (unit == 0) "%.0f %s" else "%.2f %s", value, units[unit])
    }
}
